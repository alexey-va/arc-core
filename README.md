# arc-core

Platform-agnostic **Kotlin-only** framework for **ARC** (Paper) and **ProxyARC** (Velocity).

**Repository:** [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core)

**Architecture for agents:** [`AGENTS.md`](AGENTS.md) — canon for layers, boundaries, migration.

> Старый [ARCCore](https://github.com/alexey-va/ARCCore) не используем — развиваем только этот проект.

## Requirements

- **Java 25** (Temurin)
- **Kotlin 2.3**
- Исходники только `.kt` — Gradle task `assertKotlinOnly` падает на `.java`

## Multi-module layout

Gradle root project: **`ArcCore`** (имя важно для composite build — не совпадает с subproject `arc-core`).

| Module | Artifact | Purpose |
|--------|----------|---------|
| `arc-core/` | `ru.arc:arc-core` | Config, PluginModule, TaskScheduler, Tasks, EventBus |
| `arc-core-logging/` | `ru.arc:arc-core-logging` | Loki appender (Tjahzi), ArcJsonLayout, MDC LogContext |
| `arc-core-metrics/` | `ru.arc:arc-core-metrics` | Cached Prometheus endpoint, JVM/OS/process/disk metrics |
| `arc-core-redis/` | `ru.arc:arc-core-redis` | RedisManager, pub/sub, storage |
| `arc-core-paper/` | `ru.arc:arc-core-paper` | Scheduling plus Paper world/tick/entity snapshots |
| `arc-core-velocity/` | `ru.arc:arc-core-velocity` | Scheduling plus proxy/backend/event metrics |

### Packages (arc-core)

| Package | Contents |
|---------|----------|
| `ru.arc.config` | `Config`, `ConfigManager`, `ConfigHelpers`, `EmptyConfig` |
| `ru.arc.core` | `TaskScheduler`, `Tasks`, `TaskDsl`, `PluginModule`, `ModuleRegistry` |
| `ru.arc.core.platform` | `ArcPlatform` |
| `ru.arc.util` | `TextUtils` |

Logging packages live in `arc-core-logging`; Redis in `arc-core-redis`.

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew testAll publishToMavenLocal
```

## Use in Gradle (composite build)

```kotlin
// settings.gradle.kts
includeBuild("../arc-core")  // or ~/IdeaProjects/arc-core

// build.gradle.kts — ProxyARC
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-metrics:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-velocity:1.0-SNAPSHOT")
}

// build.gradle.kts — ARC Paper
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-metrics:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-paper:1.0-SNAPSHOT")
}
```

Metrics architecture and catalog: [`arc-core-metrics/README.md`](arc-core-metrics/README.md).

## Platform binding

Call **before** `ModuleRegistry.initAll()`:

```kotlin
// Paper
PaperArcRuntime.installScheduling(plugin)

// Velocity
VelocityArcRuntime.installScheduling(server, plugin)
```

Feature code uses `Tasks.delayed`, `Tasks.repeating`, etc. — never platform schedulers directly.

Tests: `Tasks.withScheduler(TestTaskScheduler()) { ... }`

## Status

- [x] Multi-module skeleton (core, logging, redis, paper, velocity)
- [x] Config ported from ARC (SnakeYAML Engine)
- [x] PluginModule + ModuleRegistry
- [x] TaskScheduler + TaskDsl + subtick
- [x] ProxyARC + ARC wired via composite build
- [ ] Phase B: CachedRepository / xserver extraction
- [ ] Phase C: PlayerProvider, domain events

See [`docs/INDEX.md`](docs/INDEX.md) for migration specs.
