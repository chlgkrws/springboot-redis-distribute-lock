# Spring Boot Redis Distributed Lock
A distributed locking implementation using Redisson's ReentrantLock with Kotlin coroutines and circuit breaker pattern.

## Features
- **Coroutine Integration**
    - Successfully bridges Redisson's thread-based ReentrantLock with Kotlin's suspension functions
    - Non-blocking operations with coroutine support
- **Fair Lock Implementation**
    - Guarantees thread execution order using Redisson's fair lock
    - Prevents thread starvation
-  **Resilient Architecture**
    - Circuit breaker pattern implementation using Resilience4j
- **Lock Management**
    - Automatic lock release 
    - retry parameters
## Prerequisites
- JDK 17
- Docker and Docker Compose

## Getting Started
### Running Redis
Use Docker Compose to start Redis and RedisInsight:
```bash
docker-compose up -d
```

This will start:
- Redis server on port 6379
- RedisInsight (GUI) on port 8001

### Running the Application
```bash
./gradlew build
./gradlew bootRun
```
## Usage

### Basic Lock Operation

```kotlin
@Service
class YourService(
    private val lockService: LockService
) { 
    suspend fun performCriticalOperation() {
        lockService.lock("your-lock-key") {
        // Your critical section code here
        }
    }
}
```

### Custom Lock Specifications
```kotlin
val lockSpec = LockSpec(
    waitMillis = 5000, // Maximum wait time for lock acquisition
    maxRetry = 3, // Maximum retry attempts
    leaseMillis = 3000 // Lock lease time
)
lockService.lock("your-lock-key", lockSpec) {
    // Your critical section code here
}
```

### Complete Lock Operation
```kotlin
lockService.lockUntilComplete("your-lock-key") {
    // Operation that needs to run until completion
}
```

### Test Scenarios Coverage
- Fair lock ordering verification
- Concurrent lock acquisition with multiple requests
- Lock timeout and retry behavior
- Circuit breaker state transitions
- Error handling and recovery

## Project Structure
```
src
├── main
│   ├── kotlin
│   │   └── com/lock/redis
│   │       ├── config
│   │       │   ├── RedissonConfig.kt
│   │       │   └── ResilienceConfig.kt
│   │       └── service
│   │           └── LockService.kt
│   └── resources
│       └── application.yml
└── test
    └── kotlin
        └── com/lock/redis/service
            ├── LockServiceTest.kt
            └── LockServiceCircuitBreakerTest.kt
```

## References
- [Redis Docs](https://redis.io/docs/latest/develop/use/patterns/distributed-locks/)
- [Redisson Docs](https://redisson.org/docs/data-and-services/locks-and-synchronizers/)
- [Resilience4j Docs](https://resilience4j.readme.io/docs/getting-started-4)
