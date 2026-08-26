# AGENTS.md — arc-core (architecture canon)

**Read first.** Platform-agnostic Kotlin framework for ARC (Paper) and ProxyARC (Velocity).

Before writing plugin infrastructure, route the behavior through
[`docs/shared-primitives.md`](docs/shared-primitives.md). Reimplementing a
listed mechanism in ARC, ProxyARC, or another plugin is an architecture defect.

Migration history: [`docs/INDEX.md`](docs/INDEX.md)

## Repository map

| Repo | Role | Agent entry |
|------|------|-------------|
| **arc-core** (this) | Shared framework | This file — **canon** |
| [ARC](https://github.com/alexey-va/ARC) | Paper gameplay plugin | `ARC/AGENTS.md` |
| [ProxyARC](https://github.com/alexey-va/ProxyARC) | Velocity proxy plugin | `ProxyARC/AGENTS.md` |
| [mcserver](https://github.com/alexey-va/arserver-plugins) | Runtime YAML, deploy | `mcserver/AGENTS.md` + `TASKS.md` |

```
mcserver (ops, runtime YAML)
    │
arc-core ─────┬───── ARC (Paper: Event DSL, GUI, gameplay)
              └───── ProxyARC (Velocity: join, discord, antibot)
```

## Gradle modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `arc-core/` | `ru.arc:arc-core` | Config, lifecycle, identifiers, persistence, locale, diagnostics |
| `arc-core-logging/` | `ru.arc:arc-core-logging` | Loki, ArcJsonLayout, LogContext |
| `arc-core-metrics/` | `ru.arc:arc-core-metrics` | Prometheus registry, cached JVM/OS/disk metrics, scrape HTTP |
| `arc-core-redis/` | `ru.arc:arc-core-redis` | Redis plus strict codecs, CAS, origin and replay safety |
| `arc-core-sql/` | `ru.arc:arc-core-sql` | Optional MySQL/Hikari runtime, async JDBC and migrations |
| `arc-core-paper/` | `ru.arc:arc-core-paper` | Paper scheduling, transfer/teleport and player-state escrow |
| `arc-core-paper-testing/` | `ru.arc:arc-core-paper-testing` | Canonical published MockBukkit test runtime and fixtures |
| `arc-core-velocity/` | `ru.arc:arc-core-velocity` | Velocity scheduling, snapshots, and connection counters |
| `arc-core-ai/` | `ru.arc:arc-core-ai` | OpenRouter LLM, moderation, tool RPC |

Composite build: `includeBuild("../arc-core")` in ARC/ProxyARC `settings.gradle.kts`.

## Boundary rules (non-negotiable)

1. **No platform imports in `arc-core`** — no Bukkit, no Velocity API in `arc-core` / `arc-core-redis` / `arc-core-logging`.
2. **Scheduling:** feature code uses `Tasks.*` / `TaskScheduler` only — never `BukkitTaskScheduler` or `VelocityTaskScheduler` directly.
3. **Event DSL** stays in ARC plugin (`EventDsl.kt`) — not extracted to arc-core.
4. **Config:** `get()` accessor pattern + `Test*Config(EmptyConfig)` for tests.
5. **Tests:** Kotest + MockK; no `@Ignore` / `@Disabled`; no JUnit assertions in Kotlin tests.
6. **Shared mechanisms:** consult `docs/shared-primitives.md`; extend its typed
   owner instead of adding a feature-local near-duplicate.
7. **Agent-facing API:** give each mechanism one searchable owner, typed
   outcomes, KDoc for thread/lifecycle/failure invariants, and bounded
   diagnostics without raw payloads.
8. **Paper platform tests:** depend on `arc-core-paper-testing`, open one
   `MockBukkitTestRuntime` per test, and close it with `use`. Never repeat the
   MockBukkit coordinate or weaken production behavior for an unsupported mock.

## Decision tree — where to put new code

| Question | Target |
|----------|--------|
| Shared, no Bukkit/Velocity? | `arc-core` or new `arc-core-*` module |
| Shared Redis transport, codec, CAS, or replay rule? | `arc-core-redis/ru.arc.redis.safety` |
| Paper API only (Material, Sound)? | `arc-core-paper` |
| Reusable Paper test fixture or MockBukkit lifecycle? | `arc-core-paper-testing` |
| Gameplay feature (treasure, stock, …)? | `ARC/src/main/kotlin/ru/arc/{feature}/` |
| Proxy feature (join, discord, …)? | `ProxyARC/src/main/kotlin/ru/arc/` |
| Runtime YAML on prod? | `mcserver/*/plugins/ARC/modules/` or `velocity/plugins/ProxyARC/` |

## Module pattern

```kotlin
class MyFeatureModule : PluginModule {
    override val priority = 80
    override fun init() { /* wire services */ }
    override fun reload() { /* hot-reload config */ }
    override fun shutdown() { /* cleanup */ }
}

open class MyFeatureConfig(private val config: Config) {
    open val enabled: Boolean get() = config.bool("enabled", true)
    companion object {
        fun load(dataPath: Path) = MyFeatureConfig(ConfigManager.of(dataPath, "modules/my-feature.yml"))
    }
}
```

Register in plugin bootstrap via `ModuleRegistry.registerAll(...)`.

## Platform bootstrap

Call **before** `ModuleRegistry.initAll()`:

```kotlin
// Paper (ARC.kt)
PaperArcRuntime.installScheduling(this)

// Velocity (Velocity.kt)
VelocityArcRuntime.installScheduling(server, this)
```

## Migration status

| Component | Status | Spec |
|-----------|--------|------|
| Config (SnakeYAML Engine) | done | framework-design |
| PluginModule + ModuleRegistry | done | framework-design |
| TaskScheduler + TaskDsl | done | scheduling-design |
| Logging | done | — |
| Redis | done | redis-design |
| arc-core-ai (LLM + tools) | done | arc-core-ai-design |
| Paper / Velocity runtime | done | scheduling-design, proxyarc-modules |
| Event DSL | stays in ARC | framework-design |
| CachedRepository / xserver | Phase B | framework-design |
| PlayerProvider / domain events | Phase C | framework-design |

## Agent workflow

The mcserver checkout exposes focused project skills under
`mcserver/.agents/skills/`. Core ARC/ProxyARC development selects the
`ruscrafting-server-ops` development reference, which routes new modules,
migrations, and Kotlin tests back to this canonical file without duplicating
these boundary rules. Specialized workflows trigger directly from their own
skill metadata. Production deployment remains in the mcserver operations
reference; CMI kit details remain in the plugin-local
`classic/plugins/CMI/AGENTS.md`.

For a shared change, start from the behavior name in
[`docs/shared-primitives.md`](docs/shared-primitives.md), open that owner and its
same-named test, and preserve the existing typed outcomes. If no owner fits,
prove that the mechanism is reusable before adding it here and update the index
in the same commit. Do not make agents infer a required call order from an
implementation body: encode it in types where possible and in KDoc plus tests
where ordering crosses storage or platform boundaries.

## Related docs

| Doc | Purpose |
|-----|---------|
| [`README.md`](README.md) | Build, composite build, dependencies |
| [`docs/shared-primitives.md`](docs/shared-primitives.md) | Shared API routing, contracts, examples, verification |
| [`docs/paper-testing.md`](docs/paper-testing.md) | MockBukkit dependency, lifecycle, test layers, limitations |
| [`docs/INDEX.md`](docs/INDEX.md) | Superpowers specs and plans |
| `ARC/AGENTS.md` | Paper-specific delta |
| `ProxyARC/AGENTS.md` | Velocity-specific delta |
| `mcserver/AGENTS.md` | Deploy, MCP, server roles |
| `ARC/src/main/kotlin/ru/arc/gui/GUI.md` | GuiDsl patterns |
| `ARC/src/main/kotlin/ru/arc/ops/AGENTS.md` | Ops HTTP, CMI kits API |

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew testAll publishToMavenLocal
```
