package com.lock.redis.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class LockServiceTest {
    @Autowired
    private lateinit var lockService: LockService

    private var counter = 0

    @Test
    fun `동시 요청 10개 lock 수행`() =
        runBlocking {
            // given
            val numberOfConcurrentRequests = 10
            counter = 0

            // when
            val jobs =
                (1..numberOfConcurrentRequests).map {
                    delay(30)
                    async(Dispatchers.IO) {
                        println("[#$it] start")
                        lockService.lock("test") {
                            println("[#$it] acquired lock")
                            val currentValue = counter
                            delay(100)
                            counter = currentValue + 1
                            println("[#$it] release lock")
                        }
                    }
                }

            jobs.awaitAll()

            // then
            assertEquals(numberOfConcurrentRequests, counter)
        }

    @Test
    fun `동시 요청 100개 lock 수행`() =
        runBlocking {
            // given
            val numberOfConcurrentRequests = 100
            counter = 0

            // when
            val jobs =
                (1..numberOfConcurrentRequests).map {
                    delay(30)
                    async(Dispatchers.IO) {
                        println("[#$it] start")
                        lockService.lock("test") {
                            println("[#$it] acquired lock")
                            val currentValue = counter
                            delay(100)
                            counter = currentValue + 1
                            println("[#$it] release lock")
                        }
                    }
                }
            jobs.awaitAll()

            // then
            assertEquals(numberOfConcurrentRequests, counter)
        }

    @Test
    fun `동기 제어 테스트`() =
        runBlocking {
            // given
            println("method start")
            var count = 0

            // when
            lockService.lock("test") {
                println("acquired lock")
                delay(1000)
                count++
                println("release lock")
            }
            println("method end")

            // then
            assertEquals(1, count)
        }

    @Test
    fun `동기 제어 + 일시중단 함수 중첩 호출 테스트`() =
        runBlocking {
            // given
            println("method start")
            var count = 0

            // when
            lockService.lock("test") {
                println("acquired lock")
                delay(1000)
                count = sum(count)
                println("release lock")
            }
            println("method end")

            // then
            assertEquals(1, count)
        }

    suspend fun sum(num: Int): Int {
        delay(100)
        return num + 1
    }

    @Test
    fun `락 획득 실패 후 재시도 성공 테스트`() =
        runBlocking {
            // given
            var count = 0
            val lockSpec1 = LockSpec(waitMillis = 3000, maxRetry = 3, leaseMillis = 10000) // 최대 10초 동안 락 보유
            val lockSpec2 = LockSpec(waitMillis = 4000, maxRetry = 3) // 4초 동안 대기 * 3번 재시도

            // when
            val jobs =
                listOf(
                    async(Dispatchers.IO) {
                        lockService.lock("test", lockSpec1) {
                            println("First task acquired lock")
                            delay(9000) // 9초 동안 작업 수행
                            count++
                            println("First task released lock")
                        }
                    },
                    async(Dispatchers.IO) {
                        delay(500) // 첫 번째 작업이 락을 획득한 후 시도
                        lockService.lock("test", lockSpec2) {
                            println("Second task acquired lock")
                            count++
                            println("Second task released lock")
                        }
                    },
                )
            jobs.awaitAll()

            // then
            assertEquals(2, count)
        }

    @Test
    fun `락 획득 재시도 횟수 초과로 실패 테스트`() =
        runBlocking {
            // given
            var count = 0
            val lockSpec = LockSpec(waitMillis = 1000, maxRetry = 2, leaseMillis = 5000)

            // when
            val result =
                async(Dispatchers.IO) {
                    lockService.lock("test") {
                        println("First task acquired lock")
                        delay(3000) // 3초 동안 락 보유
                        count++
                        println("First task released lock")
                    }
                }

            delay(100) // 첫 번째 작업이 락을 획득할 시간을 줌

            val result2 =
                async(Dispatchers.IO) {
                    lockService.lock("test", lockSpec) {
                        println("Second task acquired lock")
                        count++
                        println("Second task released lock")
                    }
                }

            val results = awaitAll(result, result2)

            // then
            assertEquals(1, count)
            assertEquals(null, results[1]) // 두 번째 작업은 null을 반환해야 함 (락 획득 실패)
        }

    @Test
    fun `lease time 이후에도 작업은 완료되어야 함`() =
        runBlocking {
            // given
            var count = 0
            val lockSpec = LockSpec(waitMillis = 3000, maxRetry = 1, leaseMillis = 1000) // lease time 1초

            // when
            val jobs =
                listOf(
                    async(Dispatchers.IO) {
                        lockService.lock("test", lockSpec) {
                            println("First task acquired lock")
                            delay(2000) // lease time보다 오래 작업 수행하더라도
                            count++ // 작업은 완료되어야 함
                            println("First task completed")
                        }
                    },
                    async(Dispatchers.IO) {
                        delay(1500) // 첫 번째 작업이 아직 실행 중일 때
                        lockService.lock("test", lockSpec) {
                            println("Second task acquired lock")
                            count++
                            println("Second task completed")
                        }
                    },
                )
            jobs.awaitAll()

            // then
            assertEquals(2, count) // 두 작업 모두 성공해야 함
        }

    @Test
    fun `여러 키에 대한 동시 락 획득 테스트`() =
        runBlocking {
            // given
            var count1 = 0
            var count2 = 0

            // when
            val jobs =
                listOf(
                    async(Dispatchers.IO) {
                        lockService.lock("test1") {
                            println("Task 1 acquired lock for test1")
                            delay(1000)
                            count1++
                        }
                    },
                    async(Dispatchers.IO) {
                        lockService.lock("test2") {
                            println("Task 2 acquired lock for test2")
                            delay(1000)
                            count2++
                        }
                    },
                )
            jobs.awaitAll()

            // then
            assertEquals(1, count1)
            assertEquals(1, count2)
        }

    @Test
    fun `lockUntilComplete - 기본 동작 테스트`() =
        runBlocking {
            // given
            var count = 0

            // when
            val result =
                lockService.lockUntilComplete("test") {
                    println("Task acquired lock")
                    delay(1000)
                    count++
                    println("Task completed")
                }

            // then
            assertEquals(1, count)
        }

    @Test
    fun `lockUntilComplete - 동시 요청 처리 테스트`() =
        runBlocking {
            // given
            var count = 0
            val numberOfRequests = 5

            // when
            val jobs =
                (1..numberOfRequests).map { taskId ->
                    async(Dispatchers.IO) {
                        println("[#$taskId] Attempting to acquire lock")
                        delay(100)
                        lockService.lockUntilComplete("test") {
                            println("[#$taskId] Acquired lock")
                            val currentValue = count
                            delay(500) // 작업 시간 시뮬레이션
                            count = currentValue + 1
                            println("[#$taskId] Released lock")
                        }
                    }
                }
            jobs.awaitAll()

            // then
            assertEquals(numberOfRequests, count)
        }

    @Test
    fun `lockUntilComplete - 장시간 작업 락 유지 테스트`() =
        runBlocking {
            // given
            var firstTaskCompleted = false
            var secondTaskStarted = false

            // when
            val jobs =
                listOf(
                    async(Dispatchers.IO) {
                        lockService.lockUntilComplete("test") {
                            println("First task acquired lock")
                            delay(40000) // 긴 작업 시간
                            firstTaskCompleted = true
                            println("First task completed")
                        }
                    },
                    async(Dispatchers.IO) {
                        delay(1000) // 첫 번째 작업 시작 후 대기
                        secondTaskStarted = true
                        lockService.lockUntilComplete("test") {
                            // 첫 번째 작업이 완료된 후에만 실행되어야 함
                            println("Second task acquired lock")
                            assert(firstTaskCompleted) { "Second task started before first task completion" }
                        }
                    },
                )
            jobs.awaitAll()

            // then
            assert(firstTaskCompleted) { "First task was not completed" }
            assert(secondTaskStarted) { "Second task was not started" }
        }
} 
