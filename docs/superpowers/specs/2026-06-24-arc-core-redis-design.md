# arc-core-redis Design

**Date:** 2026-06-24  
**Status:** Approved  
**Consumers:** ARC (Paper), ProxyARC (Velocity)  
**Depends on:** `arc-core` (Phase A complete), `arc-core-logging` (optional SLF4J only)

## Goal

Extract shared Redis transport into `arc-core-redis`: one Jedis-based `RedisManager`, one in-memory test double, wire-compatible pub/sub and hash ops. Delete ProxyARC legacy `xserver.RedisManager` duplicate.

## Decision Summary

| Choice | Decision |
|--------|----------|
| Approach | Lift Paper `RedisManager` + inject dependencies (not full interface hierarchy) |
| Test infra | `InMemoryRedis` implements `RedisOperations` (port of `TestRedisManager`) |
| Package | `ru.arc.redis.*` in module `arc-core-redis` |
| Config | Stay in plugin YAML (`misc.yml` / `config.yml`) — no `redis.yml` in v1 |
| Wire protocol | Unchanged: `{serverName}<>#<>#<>{payload}` |
| Logging | SLF4J in core (no `ArcLogging` / Bukkit) |
| Server name | `ServerIdentity` interface injected at construction |

## Repository Layout

```
arc-core/
├── arc-core-redis/
│   src/main/kotlin/ru/arc/redis/
│   │   RedisOperations.kt       # Public API (unchanged contract)
│   │   ChannelListener.kt       # fun interface
│   │   ServerIdentity.kt        # fun interface { val name: String }
│   │   RedisConnection.kt         # data class host/port/user/password
│   │   RedisWire.kt               # SERVER_DELIMITER, encode/decode
│   │   RedisManager.kt            # Jedis + coroutines (from ARC Paper)
│   │   InMemoryRedis.kt           # Test double (from TestRedisManager)
│   └── src/test/kotlin/ru/arc/redis/
│       RedisWireTest.kt
│       InMemoryRedisTest.kt
```

**Gradle:**

```
include("arc-core-redis")   # settings.gradle.kts

arc-core-redis/build.gradle.kts:
  api(project(":arc-core"))   # minimal; may be implementation-only if no Config usage
  implementation jedis
  implementation kotlinx-coroutines-core
  implementation slf4j-api
  testImplementation mockk + kotest
```

**Plugin dependencies:**

```
ARC Paper     → arc-core-redis (via mavenLocal / composite)
ProxyARC      → arc-core-redis
```

## Public API

### RedisOperations

Same contract as ARC today:

- `publish(channel, message)`
- `saveMap`, `saveMapEntries`, `loadMap`, `loadMapEntries`
- `registerChannelUnique`, `unregisterChannel`, `init`, `close`

`loadMapEntries` returns `CompletableFuture<List<String?>>` (nullable = missing hash field). ProxyARC legacy returned `List<String>` — migrate call sites to nullable list.

### RedisManager (production)

```kotlin
class RedisManager(
    connection: RedisConnection,
    serverIdentity: ServerIdentity,
    logger: Logger = LoggerFactory.getLogger(RedisManager::class.java),
) : JedisPubSub(), RedisOperations
```

- `connect(connection: RedisConnection)` — reload / reconnect
- `getChannelCount()`, `getChannels()`, `isConnected()`, `isSubscriptionActive()`, `healthCheck()`
- No static `ARC.serverName` / `Velocity.serverName`

**Publish:** `RedisWire.encode(serverIdentity.name, message)` → Jedis publish.

**Subscribe:** decode with `RedisWire.decode`; invalid format → log error, skip (Paper behavior).

**Reconnect:** coroutine `scope.launch { init() }` on subscription failure (replaces ProxyARC `ProxyScheduler` hack).

**init():** explicit call after all `registerChannelUnique` — no auto-init on register (Paper behavior).

### InMemoryRedis (tests)

Port of `ARC/src/test/.../TestRedisManager.kt`:

- In-memory hash storage
- Local pub/sub delivery on `publish`
- `simulateExternalMessage(channel, message, originServer)` for cross-server tests
- Tracking: `publishedMessages`, `saveOperations`, `loadOperations`
- Controls: `failOnSave`, `failOnLoad`, `saveDelay`, `loadDelay`, `clear()`

### ServerIdentity

```kotlin
fun interface ServerIdentity {
    val name: String
}
```

Plugins:

```kotlin
// Paper
ServerIdentity { ARC.serverName ?: "unknown" }

// Velocity
ServerIdentity { Velocity.serverName ?: "proxy" }
```

## Wire Protocol

```kotlin
object RedisWire {
    const val SERVER_DELIMITER = "<>#<>#<>"
    fun encode(serverName: String, payload: String): String
    fun decode(full: String): Pair<String, String>?  // originServer, payload
}
```

Must remain byte-identical to production traffic on Gercena.

## Migration

### ARC Paper

1. Add `implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")` to `build.gradle.kts`
2. Create `ru/arc/network/RedisReexports.kt` (optional) or update imports:
   - `typealias RedisManager = ru.arc.redis.RedisManager`
   - `typealias ChannelListener = ru.arc.redis.ChannelListener`
   - `typealias RedisOperations = ru.arc.redis.RedisOperations`
3. Update `RedisModule` to pass `RedisConnection` + `ServerIdentity`
4. Delete `network/RedisManager.kt`, `ChannelListener.kt`, `RedisOperations.kt`
5. Replace `TestRedisManager` → `InMemoryRedis` in tests
6. Move/port behavior tests to `arc-core-redis` where possible

### ProxyARC

1. Add `arc-core-redis` dependency + shadowJar include (if needed)
2. Replace `ru.arc.xserver.RedisManager` with `ru.arc.redis.RedisManager`
3. Replace `ru.arc.xserver.ChannelListener` imports in messagers
4. Delete `xserver/RedisManager.kt`, `xserver/ChannelListener.kt`
5. `NetworkRegistry` stays in ProxyARC (registers proxy-specific channels)

### Backward compatibility

- Same channel names, hash keys, delimiter
- Self-echo behavior unchanged (local server receives own publishes)
- No Redis config file moves in v1

## Testing Strategy

| Layer | Tool | Location |
|-------|------|----------|
| Wire encode/decode | Kotest | `arc-core-redis` |
| InMemoryRedis hash/pub/sub | Kotest | `arc-core-redis` |
| Repository/sync unit tests | InMemoryRedis | ARC `src/test` |
| Live Redis integration | Testcontainers | ARC `integrationTest` (unchanged) |

**Rules:** Kotest + MockK in core; no `@Ignore`; ≥80% coverage on `RedisWire`, `InMemoryRedis`, critical `RedisManager` paths.

## Non-Goals (v1)

- `CachedRepository`, `SyncService`, `RedisStorage` interfaces
- Bundled `modules/redis.yml`
- Testcontainers inside `arc-core-redis`
- `NetworkRegistry` in core
- Self-echo filtering / origin-server skip logic

## Success Criteria

1. `./gradlew :arc-core-redis:test` passes
2. ARC + ProxyARC compile; ARC unit tests using `InMemoryRedis` pass
3. Prod deploy: spawn, survival, velocity — pub/sub (player list, auction, tools) works
4. ProxyARC legacy Redis classes deleted
5. Grafana/Loki shows no new Redis connection errors after restart
