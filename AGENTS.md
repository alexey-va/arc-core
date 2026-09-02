# AGENTS.md — arc-core (architecture canon)

**Read first.** Platform-agnostic Kotlin framework for ARC (Paper) and ProxyARC (Velocity).

Before writing plugin infrastructure, route the behavior through
[`docs/shared-primitives.md`](docs/shared-primitives.md). Reimplementing a
listed mechanism in ARC, ProxyARC, or another plugin is an architecture defect.
Every new sibling plugin must also adopt the executable
[`docs/new-plugin-contract.md`](docs/new-plugin-contract.md) from its first
commit and keep its consumer verifier green in CI.

Migration history: [`docs/INDEX.md`](docs/INDEX.md)

## Repository map

| Repo | Role | Agent entry |
|------|------|-------------|
| **arc-core** (this) | Shared framework | This file — **canon** |
| [ARC](https://github.com/alexey-va/ARC) | Paper gameplay plugin | `ARC/AGENTS.md` |
| [ProxyARC](https://github.com/alexey-va/ProxyARC) | Velocity proxy plugin | `ProxyARC/AGENTS.md` |
| [ruscrafting-ops](https://github.com/alexey-va/ruscrafting-ops) | Runtime YAML, deploy | `ruscrafting-ops/AGENTS.md` + `TASKS.md` |

```
ruscrafting-ops (ops, runtime YAML)
    │
arc-core ─────┬───── ARC (Paper: Event DSL, GUI, gameplay)
              └───── ProxyARC (Velocity: join, discord, antibot)
```

## Gradle modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `arc-core/` | `ru.arc:arc-core` | Config, lifecycle, identifiers, one-time-use protocol, durable recovery workflows, leased directories, locale, diagnostics, nameplate composition |
| `arc-core-logging/` | `ru.arc:arc-core-logging` | Loki, ArcJsonLayout, LogContext |
| `arc-core-metrics/` | `ru.arc:arc-core-metrics` | Prometheus registry, cached JVM/OS/disk metrics, scrape HTTP |
| `arc-core-redis/` | `ru.arc:arc-core-redis` | Redis plus strict codecs, CAS, validated topics, bounded request/reply and presence leases |
| `arc-core-sql/` | `ru.arc:arc-core-sql` | Optional MySQL/Hikari runtime, async JDBC and migrations |
| `arc-core-paper/` | `ru.arc:arc-core-paper` | Paper scheduling, audience/teleport delivery, chunk-ticket lifecycle, player-state escrow, and transient player nameplates |
| `arc-core-menu/` | `ru.arc:arc-core-menu` | Platform-neutral validated menu layouts, contracts, catalog generations, pagination, and feedback state |
| `arc-core-paper-menu/` | `ru.arc:arc-core-paper-menu` | Paper item templates and lifecycle-safe Inventory Framework rendering without exposing IF types to consumers |
| `arc-core-testing/` | `ru.arc:arc-core-testing` | Platform-neutral deterministic clocks, executors, and failure injection |
| `arc-core-paper-testing/` | `ru.arc:arc-core-paper-testing` | Canonical published MockBukkit test runtime and fixtures |
| `arc-core-integration-testing/` | `ru.arc:arc-core-integration-testing` | Canonical Redis/MySQL Testcontainers services for real storage seams |
| `arc-core-velocity/` | `ru.arc:arc-core-velocity` | Velocity scheduling, snapshots, and connection counters |
| `arc-core-ai/` | `ru.arc:arc-core-ai` | OpenRouter LLM, moderation, tool RPC |

Composite build: `includeBuild("../arc-core")` in ARC/ProxyARC `settings.gradle.kts`.
Public release artifacts use `ru.ruscrafting.arc:<module>:<release>` from
`https://repo.rus-crafting.ru/grocermc/`; `arc-core` and every `*-testing`
module targets Java 25 bytecode. A published GitHub release
runs `scripts/publish-release.sh`, which discovers every Maven publication,
stages complete Gradle metadata and rejects conflicting remote files before
upload.

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
9. **Typed platform ports:** keep feature meaning in a plugin-owned, narrowly
   named interface. Put an exact reusable Paper mechanism in `arc-core-paper`
   and its recorder or compatibility body in `arc-core-paper-testing`. Never
   replace either layer with a callback bag, god context, or production fallback
   added only to satisfy MockBukkit.
10. **Real storage tests:** depend on `arc-core-integration-testing` and own one
   `RedisTestService` or `MySqlTestService` with `use`. Keep image choice
   explicit when version-sensitive, but never repeat container wiring locally.
11. **Trusted config:** operator-controlled configuration remains expressive.
    Core may bootstrap its bundled default or a consumer-supplied settings
    snapshot; it must not guess legacy file locations or add broad allowlists.
12. **New consumers:** keep `arc-core-consumer.toml` at repository root and run
    `scripts/verify_consumer_architecture.py` locally and through the pinned
    central GitHub action. Declare a capability before implementing it; do not
   delete the declaration to hide a verifier failure.
13. **Configured menus:** code owns semantic actions and domain state; YAML owns
    rows, slots, patterns, regions, background, and safe item presentation.
    Never route arbitrary commands from menu YAML. Replace only a completely
    validated catalog generation and close `PaperMenuService` on shutdown.

## Decision tree — where to put new code

| Question | Target |
|----------|--------|
| Shared, no Bukkit/Velocity? | `arc-core` or new `arc-core-*` module |
| Shared Redis transport, codec, CAS, or replay rule? | `arc-core-redis/ru.arc.redis.safety` |
| Redis topic, request/reply, or hash-backed presence lifecycle? | `arc-core-redis/ru.arc.redis.network` |
| Paper API only (Material, Sound)? | `arc-core-paper` |
| Reusable layered player nameplate? | `arc-core` composer + one `arc-core-paper` display lifecycle |
| Reusable exact Paper call/lifecycle with a stable contract? | `arc-core-paper` plus its `arc-core-paper-testing` double when needed |
| Configurable Paper inventory menu? | `arc-core-menu` contract/layout + `arc-core-paper-menu` renderer; domain handlers remain in the plugin |
| Platform-neutral deterministic test fixture? | `arc-core-testing` |
| Reusable Paper test fixture or MockBukkit lifecycle? | `arc-core-paper-testing` |
| Disposable real Redis/MySQL fixture? | `arc-core-integration-testing` |
| Gameplay feature (treasure, stock, …)? | `ARC/src/main/kotlin/ru/arc/{feature}/` |
| Feature-specific Paper semantics or domain DTOs? | A narrow interface and native adapter in the owning plugin |
| Proxy feature (join, discord, …)? | `ProxyARC/src/main/kotlin/ru/arc/` |
| Runtime YAML on prod? | `ruscrafting-ops/*/plugins/ARC/modules/` or `velocity/plugins/ProxyARC/` |

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

The ruscrafting-ops checkout exposes focused project skills under
`ruscrafting-ops/.agents/skills/`. Core ARC/ProxyARC development selects the
`ruscrafting-server-ops` development reference, which routes new modules,
migrations, and Kotlin tests back to this canonical file without duplicating
these boundary rules. Specialized workflows trigger directly from their own
skill metadata. Production deployment remains in the ruscrafting-ops operations
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
| [`docs/player-nameplates.md`](docs/player-nameplates.md) | Layer ownership, Paper visibility/lifecycle, consumer example, testing |
| [`docs/paper-testing.md`](docs/paper-testing.md) | MockBukkit dependency, lifecycle, test layers, limitations |
| [`docs/paper-menus.md`](docs/paper-menus.md) | Configurable layouts, templates, reload generations, IF sessions, clicks, pagination, and feedback |
| [`docs/integration-testing.md`](docs/integration-testing.md) | Shared disposable Redis/MySQL services and integration-test contract |
| [`docs/new-plugin-contract.md`](docs/new-plugin-contract.md) | Mandatory capability manifest, baseline modules, verifier, and CI gate for new plugins |
| [`docs/redis-networking.md`](docs/redis-networking.md) | Validated topic, request/reply, and presence application layer |
| [`docs/INDEX.md`](docs/INDEX.md) | Superpowers specs and plans |
| `ARC/AGENTS.md` | Paper-specific delta |
| `ProxyARC/AGENTS.md` | Velocity-specific delta |
| `ruscrafting-ops/AGENTS.md` | Deploy, MCP, server roles |
| `ARC/src/main/kotlin/ru/arc/gui/GUI.md` | GuiDsl patterns |
| `ARC/src/main/kotlin/ru/arc/ops/AGENTS.md` | Ops HTTP, CMI kits API |

## Build

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
./gradlew testAll publishToMavenLocal
./scripts/publish-release.sh <version> --dry-run
```
