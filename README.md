# arc-core

Platform-agnostic **Kotlin-only** framework for **ARC** (Paper) and **ProxyARC** (Velocity).

**Repository:** [github.com/alexey-va/arc-core](https://github.com/alexey-va/arc-core)

**Architecture for agents:** [`AGENTS.md`](AGENTS.md) — canon for layers and boundaries.
**Shared API routing:** [`docs/shared-primitives.md`](docs/shared-primitives.md) — searchable owner, contract, examples, and tests for reusable plugin mechanisms.

> Старый [ARCCore](https://github.com/alexey-va/ARCCore) не используем — развиваем только этот проект.

## Requirements

- **Java 25** (Temurin) to build the complete multi-module repository
- published `arc-core` and `*-testing` artifacts target Java 21; the remaining
  internal runtime modules target Java 25
- **Kotlin 2.3**
- Исходники только `.kt` — Gradle task `assertKotlinOnly` падает на `.java`

## Multi-module layout

Gradle root project: **`ArcCore`** (имя важно для composite build — не совпадает с subproject `arc-core`).

| Module | Composite artifact | Purpose |
|--------|----------|---------|
| `arc-core/` | `ru.arc:arc-core` | Config, lifecycle tasks, identifiers, atomic files, locale, diagnostics |
| `arc-core-logging/` | `ru.arc:arc-core-logging` | Loki appender (Tjahzi), ArcJsonLayout, MDC LogContext |
| `arc-core-metrics/` | `ru.arc:arc-core-metrics` | Cached Prometheus endpoint, JVM/OS/process/disk metrics |
| `arc-core-redis/` | `ru.arc:arc-core-redis` | Redis manager/storage plus strict codecs, CAS, origin and replay safety |
| `arc-core-sql/` | `ru.arc:arc-core-sql` | Optional MySQL/Hikari runtime, async JDBC and migrations |
| `arc-core-paper/` | `ru.arc:arc-core-paper` | Paper scheduling, transfer/teleport boundaries and complete player-state escrow |
| `arc-core-paper-testing/` | `ru.arc:arc-core-paper-testing` | Published MockBukkit runtime and fixtures for Paper plugin tests |
| `arc-core-integration-testing/` | `ru.arc:arc-core-integration-testing` | Published disposable Redis/MySQL Testcontainers fixtures |
| `arc-core-velocity/` | `ru.arc:arc-core-velocity` | Scheduling plus proxy/backend/event metrics |

### Packages (arc-core)

| Package | Contents |
|---------|----------|
| `ru.arc.config` | `Config`, `ConfigManager`, `ConfigHelpers`, `EmptyConfig` |
| `ru.arc.core` | `TaskScheduler`, `Tasks`, `TaskDsl`, `PluginModule`, `ModuleRegistry` |
| `ru.arc.core.platform` | `ArcPlatform` |
| `ru.arc.network` | Typed cross-server player and backend identifiers |
| `ru.arc.persistence` | Bounded atomic files and coalescing async writes |
| `ru.arc.observability` | Stable bounded QA/debug lines |
| `ru.arc.text` | Validated localized MiniMessage rendering |
| `ru.arc.util` | `TextUtils` |

Logging packages live in `arc-core-logging`; Redis in `arc-core-redis`; MySQL
and blocking JDBC infrastructure in `arc-core-sql`.

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew testAll publishToMavenLocal
```

## Published releases

Tagged GitHub releases publish every module to the public RusCrafting Maven
repository under `ru.ruscrafting.arc`:

```kotlin
repositories {
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content { includeGroup("ru.ruscrafting.arc") }
    }
}

dependencies {
    implementation("ru.ruscrafting.arc:arc-core:<release>")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:<release>")
}
```

`ru.arc:*:1.0-SNAPSHOT` remains the source-composite coordinate used by ARC,
ProxyARC, and local sibling checkouts. Release publishing is resumable and
immutable: `scripts/publish-release.sh` rejects a conflicting remote file and
validates the publisher through Reposilite's read-only auth endpoint before any
write. It then publicly reads every uploaded POM, Gradle module, binary JAR,
and sources JAR back by SHA-256.

## Use in Gradle (composite build)

```kotlin
// settings.gradle.kts
includeBuild("../arc-core")  // or ~/RusCrafting/arc-core

// build.gradle.kts — ProxyARC
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-metrics:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-sql:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-velocity:1.0-SNAPSHOT")
}

// build.gradle.kts — ARC Paper
dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-metrics:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-sql:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-paper:1.0-SNAPSHOT")
    testImplementation("ru.arc:arc-core-paper-testing:1.0-SNAPSHOT")
}
```

Paper tests use the shared MockBukkit test-kit rather than declaring MockBukkit
directly. Redis/MySQL integration tests use the shared container harness rather
than repeating images, ports, credentials, waits, and cleanup. See
[`docs/paper-testing.md`](docs/paper-testing.md) and
[`docs/integration-testing.md`](docs/integration-testing.md).

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

## Shared infrastructure

New ARC, ProxyARC, and sibling-plugin code must consult
[`docs/shared-primitives.md`](docs/shared-primitives.md) before introducing a
local infrastructure helper. The index documents the stable APIs and the
security/order invariants that a caller still owns. Feature-specific gameplay,
GUI composition, and persistence schemas remain in their plugin.

See [`docs/INDEX.md`](docs/INDEX.md) for the documentation index and historical migration specs.
