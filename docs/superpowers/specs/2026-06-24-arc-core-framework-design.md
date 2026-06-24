# arc-core Framework Design

**Date:** 2026-06-24  
**Status:** Approved  
**Consumers:** ProxyARC (Velocity), ARC (Paper)  
**Deprecated:** ARCCore (`alexey-va/ARCCore`) — not used

## Goal

Turn `arc-core` into a convenient, Kotlin-only (Java 25) multi-module framework shared by ProxyARC and ARC Paper — without platform leaks in the common module.

## Phased Roadmap

| Phase | Focus | Outcome |
|-------|--------|---------|
| **A — DX Foundation** | Config, modules, tasks, events; both plugins wired | New features use core API |
| **B — Shared Runtime** | Redis, repositories, logging | CommonCore/xserver extracted |
| **C — Platform Polish** | Domain events, full Event DSL, PlayerProvider | Parity with ARC EventDsl/TaskDsl |

**Phase A constraint:** ProxyARC **and** ARC Paper both consume `arc-core` from the start (dual validation).

## Repository Layout

```
arc-core/                          ← GitHub: alexey-va/arc-core
├── settings.gradle.kts
├── build.gradle.kts               ← shared Kotlin 2.3, Kotest, jacoco, Java 25
│
├── arc-core/                      ← platform-agnostic
│   ru.arc.config.*                Config (SnakeYAML Engine v2), ConfigManager, ConfigProperty, EmptyConfig
│   ru.arc.core.*                  PluginModule, ModuleRegistry, TaskScheduler, EventBus, Time, Tasks, Events
│   ru.arc.util.*                  TextUtils, Logging facade (slf4j)
│   ru.arc.core.platform.*         ArcPlatform
│
├── arc-core-paper/                compileOnly paper-api
│   BukkitTaskScheduler, BukkitEventBus
│   ConfigPaperExtensions          material(), sound(), particle(), TagResolverBuilder
│
└── arc-core-velocity/             compileOnly velocity-api
    VelocityTaskScheduler
    VelocityEventBridge            minimal in A; domain events in C
```

**Plugin dependencies:**

```
ProxyARC  → arc-core + arc-core-velocity
ARC Paper → arc-core + arc-core-paper
```

**Local dev:** Gradle composite build (`includeBuild`) from `~/IdeaProjects/arc-core` or `mcserver/arc-core` submodule.

## Config Strategy

**Decision:** Port ARC Paper `Config` (SnakeYAML Engine v2) into `arc-core`, not the legacy ProxyARC SnakeYAML v1 config.

**Rationale:** Comment preservation, `ConfigProperty`, `duration`, hot-reload version — already production-tested on Paper.

**Split:**

| Layer | Module | Contents |
|-------|--------|----------|
| Base YAML engine + typed accessors | `arc-core` | string, bool, int, duration, component (Adventure), ConfigProperty, reload |
| Bukkit-specific accessors | `arc-core-paper` | Material, Sound, Particle, NamespacedKey helpers |

**Module config pattern (unchanged from ARC):**

```kotlin
open class MyModuleConfig(private val config: Config) {
    open val enabled: Boolean get() = config.bool("enabled", true)
    companion object {
        fun load(dataPath: Path) = MyModuleConfig(ConfigManager.of(dataPath, "my.yml"))
    }
}

class TestMyModuleConfig(override val enabled: Boolean = false) : MyModuleConfig(EmptyConfig)
```

## Module System

Port from ARC `PluginModule` + `ModuleRegistry`:

- Priority-ordered `initAll()` / `shutdownAll()` (reverse)
- `reloadAll()` after `ConfigManager.reloadAll()`
- No Bukkit in registry — logging via slf4j facade

## Tasks & Events

| API | Module | Notes |
|-----|--------|-------|
| `TaskScheduler`, `Tasks`, `TestTaskScheduler` | `arc-core` | Ticks = 50 ms |
| `BukkitTaskScheduler` | `arc-core-paper` | Mirrors ARC `TaskDsl` |
| `VelocityTaskScheduler` | `arc-core-velocity` | Move from ProxyARC |
| `EventBus`, `Events`, `SimpleEventBus` | `arc-core` | Generic `on<T>()` |
| `BukkitEventBus` | `arc-core-paper` | Phase A: basic register; Phase C: priority, once, scope |
| Velocity bridge | `arc-core-velocity` | Phase A: optional; Phase C: domain events |

## ArcPlatform

Extend minimal interface:

```kotlin
interface ArcPlatform {
    fun sendMessageToAll(component: Component)
    fun onlinePlayerNames(): Collection<String>
    val dataPath: Path
    val serverName: String
}
```

## Phase B Preview (not in scope for A)

- New module: `arc-core-redis` — `RedisManager`, pub/sub, `CachedRepository` from ARC + ProxyARC xserver
- Logging with optional Loki appender
- `CommonCore` decomposition in ProxyARC

## Phase C Preview

- Domain events: `PlayerJoin`, `ProxyChat`, etc.
- `PlayerProvider`, `CommandSender` abstractions
- Full Event DSL parity with ARC
- `USAGE-*.md` documentation in repo

## Testing Rules

- Kotest + MockK in all modules
- MockBukkit only in `arc-core-paper` tests
- `assertKotlinOnly` on root + each subproject
- ≥80% coverage on `arc-core` config/modules/scheduler logic
- No `@Ignore` / `@Disabled`

## Non-Goals

- Migrating ARCCore repo
- Big-bang rewrite of all ARC modules in phase A
- Publishing to Maven Central (composite build + `publishToMavenLocal` sufficient for now)

## Success Criteria — Phase A

1. Multi-module Gradle build passes on Java 25
2. ProxyARC uses `arc-core` + `arc-core-velocity` (scheduler moved out of plugin)
3. ARC Paper has ≥1 pilot module on `arc-core` + `arc-core-paper` config
4. Config tests pass with comment preservation
5. Both plugins CI: `./gradlew test` with composite arc-core
