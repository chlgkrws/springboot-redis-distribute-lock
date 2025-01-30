package com.lock.redis.service

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.redisson.api.RLockReactive
import org.redisson.api.RedissonReactiveClient
import org.redisson.client.RedisException
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeUnit

class LockServiceCircuitBreakerTest {
    private lateinit var lockService: LockService
    private lateinit var redissonClient: RedissonReactiveClient
    private lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    private lateinit var lock: RLockReactive

    @BeforeEach
    fun setUp() {
        // Circuit Breaker 설정
        val circuitBreakerConfig =
            CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build()

        circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig)

        // Mocks 설정
        redissonClient = mockk()
        lock = mockk()

        lockService = LockService(redissonClient, circuitBreakerRegistry)
    }

    @Test
    fun `정상 상태에서 lock 획득 성공`(): Unit =
        runBlocking {
            // given
            every { redissonClient.getFairLock("test-key") } returns lock
            every {
                lock.tryLock(
                    any(),
                    any(),
                    TimeUnit.MILLISECONDS,
                    any(),
                )
            } returns Mono.just(true)
            every { lock.unlock() } returns Mono.empty()

            // when
            val result =
                lockService.lock("test-key") {
                    "success"
                }

            // then
            assertThat(result).isEqualTo("success")
            assertThat(getCircuitBreaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
        }

    @Test
    fun `연속 실패로 Circuit Breaker OPEN 상태 전환`(): Unit =
        runBlocking {
            // given
            every { redissonClient.getFairLock("test-key") } throws RedisException("Connection failed")

            // when & then
            repeat(7) {
                val result =
                    lockService.lock("test-key") {
                        "success"
                    }
                assertThat(result).isEqualTo("success")
            }

            assertThat(getCircuitBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)
        }

    @Test
    fun `OPEN 상태에서 요청 즉시 실패`(): Unit =
        runBlocking {
            // given
            every { redissonClient.getFairLock("test-key") } throws RedisException("Connection failed")

            // Circuit Breaker를 OPEN 상태로 만듦
            repeat(4) {
                lockService.lock("test-key") { "success" }
            }

            // when
            val result =
                lockService.lock("test-key") {
                    "success"
                }

            // then
            assertThat(result).isEqualTo("success")
            assertThat(getCircuitBreaker().state).isEqualTo(CircuitBreaker.State.OPEN)
        }

    @Test
    fun `HALF_OPEN 상태에서 성공하면 CLOSED 상태로 복구`(): Unit =
        runBlocking {
            // given
            every { redissonClient.getFairLock("test-key") } throws RedisException("Connection failed")

            // Circuit Breaker를 OPEN 상태로 만듦
            repeat(4) {
                lockService.lock("test-key") {
                    println("task progress")
                    "success"
                }
            }

            // Wait for the circuit breaker to transition to HALF_OPEN
            delay(5000)

            // 성공 응답으로 변경
            every { redissonClient.getFairLock("test-key") } returns lock
            every {
                lock.tryLock(
                    any(),
                    any(),
                    TimeUnit.MILLISECONDS,
                    any(),
                )
            } returns Mono.just(true)
            every { lock.unlock(any()) } returns Mono.empty()

            // when
            repeat(2) {
                val result =
                    lockService.lock("test-key") {
                        println("task progress")
                        "success"
                    }
                assertThat(result).isEqualTo("success")
            }

            // then
            assertThat(getCircuitBreaker().state).isEqualTo(CircuitBreaker.State.CLOSED)
        }

    private fun getCircuitBreaker(): CircuitBreaker {
        return circuitBreakerRegistry.circuitBreaker("redisLock")
    }
} 
