# arc-core-redis Implementation Plan

**Goal:** Shared Redis transport in `arc-core-redis` with production `RedisManager`, test `InMemoryRedis`, and both plugins migrated off duplicates.

**Architecture:** New Gradle module `arc-core-redis` under `ru.arc.redis`. Port Paper `RedisManager` with `ServerIdentity` + SLF4J injection. Port `TestRedisManager` → `InMemoryRedis`. ARC keeps thin typealiases in `ru.arc.network`; ProxyARC deletes `xserver.RedisManager`.

**Tech Stack:** Kotlin 2.3, Java 25, Jedis 5.x, kotlinx-coroutines, Kotest 6, MockK, SLF4J.

**Spec:** `docs/superpowers/specs/2026-06-24-arc-core-redis-design.md`

**Repos:** `~/IdeaProjects/arc-core`, `~/IdeaProjects/ARC`, `~/mcserver/ProxyARC`

---

## File map

| Action | Path |
|--------|------|
| Modify | `arc-core/settings.gradle.kts` — `include("arc-core-redis")` |
| Create | `arc-core/arc-core-redis/build.gradle.kts` |
| Create | `arc-core-redis/.../RedisOperations.kt` |
| Create | `arc-core-redis/.../ChannelListener.kt` |
| Create | `arc-core-redis/.../ServerIdentity.kt` |
| Create | `arc-core-redis/.../RedisConnection.kt` |
| Create | `arc-core-redis/.../RedisWire.kt` |
| Create | `arc-core-redis/.../InMemoryRedis.kt` |
| Create | `arc-core-redis/.../RedisManager.kt` (port from ARC) |
| Create | `arc-core-redis/src/test/.../RedisWireTest.kt` |
| Create | `arc-core-redis/src/test/.../InMemoryRedisTest.kt` |
| Modify | `ARC/build.gradle.kts` — dep + shadowJar if needed |
| Create | `ARC/.../network/RedisAliases.kt` — typealiases |
| Modify | `ARC/.../core/modules/CoreModules.kt` — RedisModule wiring |
| Delete | `ARC/.../network/RedisManager.kt`, `ChannelListener.kt`, `RedisOperations.kt` |
| Modify | `ARC/src/test/**` — `TestRedisManager` → `InMemoryRedis` |
| Modify | `ProxyARC/build.gradle.kts` — dep |
| Modify | `ProxyARC/.../InfrastructureModules.kt` — RedisModule |
| Delete | `ProxyARC/.../xserver/RedisManager.kt`, `ChannelListener.kt` |
| Modify | ProxyARC messagers — import `ru.arc.redis.ChannelListener` |

---

## Chunk 1: arc-core-redis module skeleton

### Task 1: Gradle module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `arc-core-redis/build.gradle.kts`

- [ ] **Step 1:** Add to `settings.gradle.kts`:
```kotlin
include("arc-core-redis")
```

- [ ] **Step 2:** Create `arc-core-redis/build.gradle.kts` (mirror `arc-core-logging`, minus log4j):
```kotlin
plugins {
    kotlin("jvm")
    `maven-publish`
}
description = "ARC Core Redis — pub/sub, hash ops, in-memory test double"
java { withSourcesJar() }
dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("redis.clients:jedis:5.2.0")
    implementation("org.slf4j:slf4j-api:2.0.16")
    testImplementation("io.mockk:mockk:1.14.7")
}
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "arc-core-redis"
        }
    }
}
```

- [ ] **Step 3:** Run `JAVA_HOME=... ./gradlew :arc-core-redis:compileKotlin` — PASS

- [ ] **Step 4:** Commit arc-core: `feat(redis): add arc-core-redis module skeleton`

---

## Chunk 2: Wire + interfaces (TDD)

### Task 2: RedisWire

**Files:**
- Create: `arc-core-redis/src/main/kotlin/ru/arc/redis/RedisWire.kt`
- Create: `arc-core-redis/src/test/kotlin/ru/arc/redis/RedisWireTest.kt`

- [ ] **Step 1:** Write failing test:
```kotlin
class RedisWireTest : FreeSpec({
    "encode" { RedisWire.encode("spawn", "hello") shouldBe "spawn<>#<>#<>hello" }
    "decode" {
        RedisWire.decode("spawn<>#<>#<>hello") shouldBe ("spawn" to "hello")
    }
    "decode invalid" { RedisWire.decode("no-delimiter") shouldBe null }
})
```

- [ ] **Step 2:** Run `./gradlew :arc-core-redis:test --tests RedisWireTest` — FAIL

- [ ] **Step 3:** Implement `RedisWire` with `SERVER_DELIMITER = "<>#<>#<>"`

- [ ] **Step 4:** Run test — PASS

- [ ] **Step 5:** Commit: `feat(redis): add RedisWire encode/decode`

### Task 3: Public interfaces

**Files:**
- Create: `RedisOperations.kt`, `ChannelListener.kt`, `ServerIdentity.kt`, `RedisConnection.kt`

- [ ] **Step 1:** Copy interfaces from `ARC/.../RedisOperations.kt` and `ChannelListener.kt` into `ru.arc.redis` (adjust package)

- [ ] **Step 2:** Add:
```kotlin
fun interface ServerIdentity { val name: String }
data class RedisConnection(val host: String, val port: Int, val username: String? = null, val password: String? = null)
```

- [ ] **Step 3:** Compile — PASS

- [ ] **Step 4:** Commit: `feat(redis): add RedisOperations and connection types`

---

## Chunk 3: InMemoryRedis (TDD)

### Task 4: Port test double

**Files:**
- Create: `InMemoryRedis.kt`
- Create: `InMemoryRedisTest.kt`
- Reference: `ARC/src/test/kotlin/ru/arc/network/repos/TestRedisManager.kt`

- [ ] **Step 1:** Write tests: hash save/load, delete via null value, publish delivers to listener, `simulateExternalMessage`, `clear()`

- [ ] **Step 2:** Run tests — FAIL

- [ ] **Step 3:** Port `TestRedisManager` → `InMemoryRedis` implementing `RedisOperations`; use `RedisWire.encode` in `publish` for tracking but deliver decoded payload to listeners

- [ ] **Step 4:** Run `./gradlew :arc-core-redis:test` — PASS

- [ ] **Step 5:** Commit: `feat(redis): add InMemoryRedis test double`

---

## Chunk 4: RedisManager (production)

### Task 5: Port Jedis implementation

**Files:**
- Create: `arc-core-redis/.../RedisManager.kt`
- Source: `ARC/src/main/kotlin/ru/arc/network/RedisManager.kt`

- [ ] **Step 1:** Copy `RedisManager.kt` to core; change package to `ru.arc.redis`

- [ ] **Step 2:** Replace `ARC.serverName` → `serverIdentity.name`

- [ ] **Step 3:** Replace `ru.arc.util.Logging.*` → `logger.info/warn/error/debug` (SLF4J)

- [ ] **Step 4:** Remove `withContext(module=...)` MDC calls (or optional debug-only)

- [ ] **Step 5:** Use `RedisWire.encode/decode` in publish/onMessage

- [ ] **Step 6:** Constructor: `(connection: RedisConnection, serverIdentity: ServerIdentity, logger: Logger = ...)`

- [ ] **Step 7:** Run `./gradlew :arc-core-redis:compileKotlin` — PASS

- [ ] **Step 8:** Commit: `feat(redis): port RedisManager from ARC Paper`

### Task 6: Core unit tests for RedisManager contract

**Files:**
- Create: `RedisManagerContractTest.kt` (optional — test registerChannelUnique + init doesn't throw when Redis unreachable)

- [ ] **Step 1:** Characterization: `RedisManager("invalid", 1, ...)` may fail connect — test `close()` idempotent, `getChannelCount()` after register

- [ ] **Step 2:** Run `:arc-core-redis:test` — PASS

- [ ] **Step 3:** Commit if added

- [ ] **Step 4:** Run `./gradlew publishToMavenLocal` in arc-core

---

## Chunk 5: ARC Paper migration

### Task 7: Wire dependency

**Files:**
- Modify: `ARC/build.gradle.kts`

- [ ] **Step 1:** Add `implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")`

- [ ] **Step 2:** Publish arc-core locally; compile ARC — expect duplicate class errors until old files removed

### Task 8: Typealiases + RedisModule

**Files:**
- Create: `ARC/src/main/kotlin/ru/arc/network/RedisAliases.kt`
- Modify: `ARC/src/main/kotlin/ru/arc/core/modules/CoreModules.kt`

- [ ] **Step 1:** Create typealiases:
```kotlin
package ru.arc.network
typealias RedisManager = ru.arc.redis.RedisManager
typealias RedisOperations = ru.arc.redis.RedisOperations
typealias ChannelListener = ru.arc.redis.ChannelListener
```

- [ ] **Step 2:** Update `RedisModule.init()`:
```kotlin
ARC.redisManager = RedisManager(
    ru.arc.redis.RedisConnection(ip, port, username, password),
    ru.arc.redis.ServerIdentity { ARC.serverName ?: "unknown" },
)
```

- [ ] **Step 3:** Delete old `RedisManager.kt`, `RedisOperations.kt`, `ChannelListener.kt`

- [ ] **Step 4:** Compile ARC — fix any direct `ru.arc.redis` imports if needed

### Task 9: ARC tests

**Files:**
- Delete: `ARC/src/test/.../TestRedisManager.kt`
- Modify: all imports `TestRedisManager` → `ru.arc.redis.InMemoryRedis`

- [ ] **Step 1:** Replace test double usages

- [ ] **Step 2:** Run `JAVA_HOME=... ./gradlew test --tests "*Redis*"` — PASS

- [ ] **Step 3:** Run full `./gradlew test` (or subset if slow)

- [ ] **Step 4:** Commit ARC: `refactor: migrate Redis to arc-core-redis`

---

## Chunk 6: ProxyARC migration

### Task 10: Dependency + RedisModule

**Files:**
- Modify: `ProxyARC/build.gradle.kts`
- Modify: `ProxyARC/.../InfrastructureModules.kt`

- [ ] **Step 1:** Add `implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")` + `mavenLocal()` if missing

- [ ] **Step 2:** Update `RedisModule`:
```kotlin
Velocity.redisManager = RedisManager(
    RedisConnection(host, port, username, password),
    ServerIdentity { Velocity.serverName ?: "proxy" },
)
```

- [ ] **Step 3:** Delete `xserver/RedisManager.kt`, `xserver/ChannelListener.kt`

- [ ] **Step 4:** Update imports in `AuctionMessager`, `ToolsMessager`, `RedisRepoMessager` → `ru.arc.redis.ChannelListener`

- [ ] **Step 5:** Fix `loadMapEntries` call sites if they assumed non-null `List<String>`

- [ ] **Step 6:** `./gradlew compileKotlin` in ProxyARC — PASS

- [ ] **Step 7:** Commit ProxyARC: `refactor: migrate Redis to arc-core-redis`

---

## Chunk 7: Verification & deploy

### Task 11: Smoke test prod

- [ ] **Step 1:** `publishToMavenLocal` arc-core; build ARC shadowJar + ProxyARC

- [ ] **Step 2:** Deploy: `./scripts/mc arc --no-build classic classic_survival` and `./scripts/mc proxyarc --no-build --fast`

- [ ] **Step 3:** Verify Grafana Loki: `{service_name="spawn"} |= "Redis"` — connected, channel subscribe lines

- [ ] **Step 4:** `arc_ops_plugins` on classic — ARC ok; check cross-server player list / no Redis errors

- [ ] **Step 5:** Commit arc-core docs if not yet committed

---

## Notes for implementer

- **Do not** change `SERVER_DELIMITER` or channel names
- **Do not** add `redis.yml` to core in this plan
- ProxyARC `println` in old Redis — remove, use SLF4J
- If shadow JAR omits classes, add explicit `implementation arc-core-redis` to shadow (like logging.yml pattern — usually not needed for classes)
- Integration tests in ARC (`RedisManagerIntegrationTest`) keep using real `RedisManager` + Testcontainers — update import only
