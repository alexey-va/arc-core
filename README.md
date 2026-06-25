# arc-core

Platform-agnostic **Kotlin-only** framework for **ARC** (Paper) and **ProxyARC** (Velocity).

**Repository:** [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core)

> Старый [ARCCore](https://github.com/alexey-va/ARCCore) не используем — развиваем только этот проект.

## Requirements

- **Java 25** (Temurin)
- **Kotlin 2.3**
- Исходники только `.kt` — Gradle task `assertKotlinOnly` падает на `.java`

## Multi-module layout

Gradle root project: **`ArcCore`** (имя важно для composite build — не совпадает с subproject `arc-core`).

| Module | Artifact | Purpose |
|--------|----------|---------|
| `arc-core/` | `ru.arc:arc-core` | Config, PluginModule, TaskScheduler, EventBus |
| `arc-core-logging/` | `ru.arc:arc-core-logging` | Loki appender (Tjahzi), ArcJsonLayout, MDC LogContext |
| `arc-core-paper/` | `ru.arc:arc-core-paper` | BukkitTaskScheduler, Config Paper extensions |
| `arc-core-velocity/` | `ru.arc:arc-core-velocity` | VelocityTaskScheduler |

### Packages (arc-core)

| Package | Contents |
|---------|----------|
| `ru.arc.config` | `Config`, `ConfigManager`, `ConfigHelpers`, `EmptyConfig` |
| `ru.arc.logging` | `LokiLogging`, `ArcJsonLayout`, `LogContext`, `QuietDebugFilter` |
| `ru.arc.util` | `TextUtils` |
| `ru.arc.core` | `TaskScheduler`, `EventBus`, `Tasks`, `PluginModule`, `ModuleRegistry` |
| `ru.arc.core.platform` | `ArcPlatform` |

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
    implementation("ru.arc:arc-core-velocity:1.0-SNAPSHOT")
}

// build.gradle.kts — ARC Paper
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-paper:1.0-SNAPSHOT")
}
```

## Platform binding

- **ProxyARC:** `VelocityTaskScheduler` from `arc-core-velocity` + `Velocity : ArcPlatform`
- **ARC Paper:** `BukkitTaskScheduler` from `arc-core-paper` (planned full migration)

```kotlin
Tasks.scheduler = VelocityTaskScheduler(server, plugin)

Tasks.withScheduler(TestTaskScheduler()) {
    delayed(20) { /* ... */ }
}
```

## Phase A status

- [x] Multi-module skeleton
- [x] Config ported from ARC (SnakeYAML Engine)
- [x] PluginModule + ModuleRegistry
- [x] ProxyARC wired via composite build
- [x] ARC pilot: `ScheduledCommands` uses `ru.arc.config.*` from arc-core
