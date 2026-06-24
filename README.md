# arc-core

Platform-agnostic **Kotlin-only** library for **ARC** (Paper) and **ProxyARC** (Velocity).

**Repository:** [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core)

> Старый [ARCCore](https://github.com/alexey-va/ARCCore) не используем — развиваем только этот проект.

## Requirements

- **Java 25** (Temurin)
- **Kotlin 2.3**
- Исходники только `.kt` — Gradle task `assertKotlinOnly` падает на `.java`

## Packages

| Package | Contents |
|---------|----------|
| `ru.arc.config` | `Config`, `ConfigManager` |
| `ru.arc.util` | `TextUtils` (MiniMessage) |
| `ru.arc.core` | `TaskScheduler`, `EventBus`, `TimeProvider`, `Tasks`, `Events` |
| `ru.arc.core.platform` | `ArcPlatform` |

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew test publishToMavenLocal
```

## Use in Gradle (composite build)

```kotlin
// settings.gradle.kts
includeBuild("../arc-core")

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
