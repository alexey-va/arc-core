# arc-core

Platform-agnostic library for **ARC** (Paper) and **ProxyARC** (Velocity): YAML config, task scheduling, events, Adventure text utils.

**Repository:** [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core)

Related projects:

| Project | Role |
|---------|------|
| [ARC](https://github.com/alexey-va/ARC) | Paper plugin |
| [ProxyARC](https://github.com/alexey-va/ProxyARC) | Velocity proxy plugin |
| [ARCCore](https://github.com/alexey-va/ARCCore) | Larger shared framework (redis, metrics, AI) — long-term merge target |

## Packages

| Package | Contents |
|---------|----------|
| `ru.arc.config` | `Config`, `ConfigManager` |
| `ru.arc.util` | `TextUtils` (MiniMessage) |
| `ru.arc.core` | `TaskScheduler`, `EventBus`, `TimeProvider`, `Tasks`, `Events` |
| `ru.arc.core.platform` | `ArcPlatform` |

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/yandex-jdk-21  # or Java 21+
./gradlew test publishToMavenLocal
```

## Use in Gradle (composite build)

```kotlin
// settings.gradle.kts
includeBuild("../arc-core")  // or path to clone

// build.gradle.kts
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
}
```

## Platform binding

- **ProxyARC:** `VelocityTaskScheduler` + `Velocity : ArcPlatform`
- **ARC Paper:** `BukkitTaskScheduler` + `BukkitEventBus` (planned)

```kotlin
Tasks.scheduler = VelocityTaskScheduler(server, plugin)

Tasks.withScheduler(TestTaskScheduler()) {
    delayed(20) { /* ... */ }
}
```

## License

Private / same as ARC monorepo.
