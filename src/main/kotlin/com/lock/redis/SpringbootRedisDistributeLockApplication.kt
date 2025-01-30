package com.lock.redis

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SpringbootRedisDistributeLockApplication

fun main(args: Array<String>) {
    runApplication<SpringbootRedisDistributeLockApplication>(*args)
}
