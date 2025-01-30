package com.lock.redis.service

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext
import org.redisson.api.RLockReactive
import org.redisson.api.RedissonReactiveClient
import org.redisson.client.RedisConnectionException
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class LockService(
    private val redissonClient: RedissonReactiveClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {
    private companion object {
        private const val DEFAULT_LOCK_WAIT_MILLIS = 5_000L
        private const val DEFAULT_MAX_RETRY = 3
        private const val DEFAULT_LOCK_LEASE_MILLIS = 3_000L
        private const val CIRCUIT_BREAKER_NAME = "redisLock"
        private val log = LoggerFactory.getLogger(LockService::class.java)
    }

    private val circuitBreaker: CircuitBreaker by lazy {
        circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME)
    }

    /**
     * Executes the given block with distributed locking mechanism using Redis.
     * This method ensures thread-safe execution of the provided code block across multiple instances/threads.
     *
     * @param key The unique key for the distributed lock
     * @param spec Optional [LockSpec] to customize lock behavior:
     *             - waitMillis: Maximum time to wait for lock acquisition (default: 5000ms)
     *             - maxRetry: Maximum number of retry attempts for lock acquisition (default: 3)
     *             - leaseMillis: Maximum time to hold the lock (default: 3000ms)
     * @param block The suspend function to execute within the lock
     * @return The result of the block execution, or null if lock acquisition fails after max retries
     *
     * @throws LockBlockInternalException if the block execution fails with an exception
     *
     * Example usage:
     * ```
     * lockService.lock("user:123") {
     *     // Your critical section code here
     * }
     * ```
     */
    suspend fun <T> lock(
        key: String,
        spec: LockSpec? = null,
        block: suspend () -> T,
    ): T? {
        return lock(
            key = key,
            acquireStrategy = { lock, lockId ->
                acquireLock(
                    lock = lock,
                    lockId = lockId,
                    waitMillis = spec?.waitMillis ?: DEFAULT_LOCK_WAIT_MILLIS,
                    leaseMillis = spec?.leaseMillis ?: DEFAULT_LOCK_LEASE_MILLIS,
                )
            },
            maxRetry = spec?.maxRetry ?: DEFAULT_MAX_RETRY,
            block = block,
        )
    }

    /**
     * Executes the given block with distributed locking mechanism using Redis.
     * This method attempts to acquire a lock and holds it until the block completes execution.
     *
     * @param key The unique key for the distributed lock
     * @param block The suspend function to execute within the lock
     * @return The result of the block execution, or null if lock acquisition fails
     *
     * @throws LockBlockInternalException if the block execution fails with an exception
     *
     * Example usage:
     * ```
     * lockService.lockUntilComplete("user:123") {
     *     // Your critical section code here that needs to run until completion
     * }
     * ```
     */
    suspend fun <T> lockUntilComplete(
        key: String,
        block: suspend () -> T,
    ): T? {
        return lock(
            key = key,
            acquireStrategy = { lock, lockId -> acquireLock(lock, lockId) },
            maxRetry = 0,
            block = block,
        )
    }

    private suspend fun <T> lock(
        key: String,
        acquireStrategy: suspend (RLockReactive, Long) -> Boolean,
        maxRetry: Int,
        block: suspend () -> T,
    ): T? {
        var currentRetry = 0
        val lockId = UUID.randomUUID().mostSignificantBits

        return withContext(Dispatchers.Default) {
            try {
                val lock =
                    circuitBreaker.executeSuspendFunction {
                        val lock = redissonClient.getFairLock(key)

                        while (!acquireStrategy(lock, lockId)) {
                            if (++currentRetry == maxRetry) {
                                return@executeSuspendFunction null
                            }
                        }
                        lock
                    }

                if (lock == null) {
                    return@withContext null
                }

                try {
                    return@withContext run(block)
                } finally {
                    unlock(lock, lockId)
                }
            } catch (e: LockBlockInternalException) {
                throw e
            } catch (e: Exception) {
                return@withContext handleLockFailure(key, e, block)
            }
        }
    }

    private suspend fun <T> run(block: suspend () -> T): T? {
        try {
            return block()
        } catch (e: Exception) {
            throw LockBlockInternalException("Failed to execute block within lock", e)
        }
    }

    private suspend fun acquireLock(
        lock: RLockReactive,
        lockId: Long,
        waitMillis: Long,
        leaseMillis: Long,
    ): Boolean {
        return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS, lockId).awaitFirstOrNull() ?: false
    }

    private suspend fun acquireLock(
        lock: RLockReactive,
        lockId: Long,
    ): Boolean {
        return lock.tryLock(lockId).awaitFirstOrNull() ?: false
    }

    private suspend fun unlock(
        lock: RLockReactive,
        lockId: Long,
    ) {
        try {
            lock.unlock(lockId).awaitFirstOrNull()
        } catch (e: Exception) {
            log.warn("Failed to release lock (lockId: $lockId)", e)
        }
    }

    private suspend fun <T> handleLockFailure(
        key: String,
        e: Exception,
        block: suspend () -> T,
    ): T? {
        log.error("Failed to acquire lock for key: $key", e)
        return when (e) {
            is RedisConnectionException,
            is RedisTimeoutException,
            -> {
                log.error("Redis connection failed, executing without lock for key: $key (error: ${e.message})")
                run(block)
            }
            is RedisException -> {
                log.error("Redis operation failed, executing without lock for key: $key (error: ${e.message})")
                run(block)
            }
            is CallNotPermittedException -> {
                log.error("Circuit breaker is OPEN, executing without lock for key: $key (error: ${e.message})")
                run(block)
            }

            else -> {
                log.error("Unexpected error occurred while acquiring lock", e)
                null
            }
        }
    }
}

class LockBlockInternalException(message: String, cause: Throwable) : RuntimeException(message, cause)

data class LockSpec(
    val waitMillis: Long? = null,
    val maxRetry: Int? = null,
    val leaseMillis: Long? = null,
)
