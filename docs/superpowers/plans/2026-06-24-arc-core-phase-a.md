# arc-core Phase A (DX Foundation) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Multi-module arc-core framework with ported Config, module lifecycle, and platform adapters — consumed by both ProxyARC and ARC Paper.

**Architecture:** Split repo into `arc-core` (agnostic), `arc-core-paper`, `arc-core-velocity`. Port ARC SnakeYAML Engine v2 Config into common module; move VelocityTaskScheduler into velocity module; add Bukkit adapters for Paper. Wire both plugins via composite build.

**Tech Stack:** Kotlin 2.3, Java 25, Gradle 9.2, Kotest 6, MockK, SnakeYAML Engine v2, Adventure 4.17, Paper/Velocity compileOnly in platform modules.

**Spec:** `docs/superpowers/specs/2026-06-24-arc-core-framework-design.md`

---

## File map (Phase A end state)

| File / module | Responsibility |
|---------------|----------------|
| `settings.gradle.kts` | `include("arc-core", "arc-core-paper", "arc-core-velocity")` |
| `build.gradle.kts` (root) | Shared toolchain Java 25, Kotest, jacoco, assertKotlinOnly |
| `arc-core/build.gradle.kts` | Common deps: adventure, slf4j, snakeyaml-engine |
| `arc-core-paper/build.gradle.kts` | `compileOnly(paper-api)`, depends on `:arc-core` |
| `arc-core-velocity/build.gradle.kts` | `compileOnly(velocity-api)`, depends on `:arc-core` |
| `arc-core/.../config/Config.kt` | Port from ARC `ru.arc.configs.Config` (strip Bukkit) |
| `arc-core/.../config/ConfigHelpers.kt` | ConfigProperty, EmptyConfig, TagResolverBuilder (text-only tags) |
| `arc-core/.../core/PluginModule.kt` | Lifecycle interface |
| `arc-core/.../core/ModuleRegistry.kt` | Init/reload/shutdown ordering |
| `arc-core-paper/.../BukkitTaskScheduler.kt` | Port from ARC `TaskScheduler.kt` |
| `arc-core-paper/.../BukkitEventBus.kt` | Port minimal from ARC `EventDsl.kt` |
| `arc-core-paper/.../ConfigPaperExtensions.kt` | material(), sound(), particle() |
| `arc-core-velocity/.../VelocityTaskScheduler.kt` | Move from ProxyARC |
| `ProxyARC/build.gradle.kts` | `arc-core` + `arc-core-velocity` composite deps |
| `ARC/build.gradle.kts` | Add `includeBuild` + `arc-core` + `arc-core-paper` |

---

## Chunk 1: Multi-module skeleton

### Task 1: Restructure Gradle to multi-module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `build.gradle.kts` (root aggregator — move shared config from current single module)
- Move: current `src/` → `arc-core/src/`
- Move: current `arc-core/build.gradle.kts` content → `arc-core/build.gradle.kts` (subproject)
- Create: `arc-core-paper/build.gradle.kts`, `arc-core-velocity/build.gradle.kts`

- [ ] **Step 1:** Create `settings.gradle.kts`:
```kotlin
rootProject.name = "arc-core"
include("arc-core", "arc-core-paper", "arc-core-velocity")
```

- [ ] **Step 2:** Move existing sources to `arc-core/src/...` (config, core, util — already there)

- [ ] **Step 3:** Root `build.gradle.kts` — subprojects block: Kotlin 2.3, JVM 25, Kotest, `assertKotlinOnly` per subproject

- [ ] **Step 4:** `arc-core-paper` — `compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")`, `implementation(project(":arc-core"))`

- [ ] **Step 5:** `arc-core-velocity` — `compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")`, `implementation(project(":arc-core"))`

- [ ] **Step 6:** Run `./gradlew :arc-core:test` — must PASS (existing tests)

- [ ] **Step 7:** Commit: `git commit -m "refactor: multi-module skeleton (core, paper, velocity)"`

---

## Chunk 2: Port Config from ARC

### Task 2: Add SnakeYAML Engine v2 dependency

**Files:**
- Modify: `arc-core/build.gradle.kts`

- [ ] **Step 1:** Replace `org.yaml:snakeyaml:2.2` with `org.snakeyaml:snakeyaml-engine:2.7` (match ARC version)

- [ ] **Step 2:** Run build — expect FAIL until Config ported

### Task 3: Port Config core (TDD)

**Files:**
- Create: `arc-core/src/main/kotlin/ru/arc/config/ConfigHelpers.kt` (from ARC, no Bukkit imports)
- Replace: `arc-core/src/main/kotlin/ru/arc/config/Config.kt` (port from `ARC/src/main/kotlin/ru/arc/configs/Config.kt`)
- Modify: `arc-core/src/main/kotlin/ru/arc/config/ConfigManager.kt` (add `reloadAll()` version bump)
- Create: `arc-core/src/main/kotlin/ru/arc/config/EmptyConfig.kt`
- Test: `arc-core/src/test/kotlin/ru/arc/config/ConfigTest.kt` — expand
- Test: `arc-core/src/test/kotlin/ru/arc/config/ConfigCommentTest.kt` (new — comment preservation)

- [ ] **Step 1:** Write failing test — YAML comment survives save/reload:
```kotlin
"should preserve inline comments after save" {
    // copy pattern from ARC ConfigTest / ConfigCommentTest
}
```

- [ ] **Step 2:** Run `./gradlew :arc-core:test --tests "*ConfigComment*"` — FAIL

- [ ] **Step 3:** Port Config.kt from ARC — remove Bukkit methods (material, sound, particle → defer to paper module)

- [ ] **Step 4:** Port ConfigProperty, EmptyConfig, duration helpers to ConfigHelpers.kt

- [ ] **Step 5:** Replace println with slf4j in Config

- [ ] **Step 6:** Run `./gradlew :arc-core:test` — PASS

- [ ] **Step 7:** Commit: `feat(config): port SnakeYAML Engine v2 from ARC`

### Task 4: Paper config extensions

**Files:**
- Create: `arc-core-paper/src/main/kotlin/ru/arc/config/ConfigPaperExtensions.kt`
- Test: `arc-core-paper/src/test/kotlin/ru/arc/config/ConfigPaperExtensionsTest.kt` (MockBukkit if needed, or unit test with mocked Registry)

- [ ] **Step 1:** Move Bukkit accessors from ARC Config as extension functions on `Config`
- [ ] **Step 2:** Test material()/sound() parsing
- [ ] **Step 3:** `./gradlew :arc-core-paper:test` — PASS
- [ ] **Step 4:** Commit

---

## Chunk 3: Module system

### Task 5: PluginModule + ModuleRegistry

**Files:**
- Create: `arc-core/src/main/kotlin/ru/arc/core/PluginModule.kt` (copy from ARC)
- Create: `arc-core/src/main/kotlin/ru/arc/core/ModuleRegistry.kt` (copy from ARC, use slf4j not ARC Logging)
- Test: `arc-core/src/test/kotlin/ru/arc/core/ModuleRegistryTest.kt`

- [ ] **Step 1:** Write test — modules init in priority order, shutdown reverse
- [ ] **Step 2:** Run test — FAIL
- [ ] **Step 3:** Implement PluginModule + ModuleRegistry
- [ ] **Step 4:** Run test — PASS
- [ ] **Step 5:** Commit

---

## Chunk 4: Platform modules

### Task 6: Move VelocityTaskScheduler

**Files:**
- Move: `ProxyARC/.../VelocityTaskScheduler.kt` → `arc-core-velocity/src/main/kotlin/ru/arc/velocity/core/VelocityTaskScheduler.kt`
- Test: `arc-core-velocity/src/test/kotlin/ru/arc/velocity/core/VelocityTaskSchedulerTest.kt` (mock ProxyServer if feasible, or interface wrapper)

- [ ] **Step 1:** Copy file, fix package, add module dependency
- [ ] **Step 2:** Update ProxyARC to import from `arc-core-velocity`
- [ ] **Step 3:** `./gradlew :arc-core-velocity:test` + ProxyARC test — PASS
- [ ] **Step 4:** Commit in arc-core; commit ProxyARC separately

### Task 7: BukkitTaskScheduler + BukkitEventBus

**Files:**
- Create: `arc-core-paper/src/main/kotlin/ru/arc/core/BukkitTaskScheduler.kt` (port from ARC)
- Create: `arc-core-paper/src/main/kotlin/ru/arc/core/BukkitEventBus.kt` (minimal: register/unregister)
- Test: `arc-core-paper/src/test/kotlin/ru/arc/core/BukkitTaskSchedulerTest.kt` (TestTaskScheduler pattern + MockBukkit smoke)

- [ ] **Step 1:** Port BukkitTaskScheduler from ARC `TaskScheduler.kt`
- [ ] **Step 2:** Port minimal BukkitEventBus from ARC `EventDsl.kt` (no awaitEvent in A)
- [ ] **Step 3:** Tests PASS
- [ ] **Step 4:** Commit

### Task 8: Align Tasks DSL with ARC

**Files:**
- Modify: `arc-core/src/main/kotlin/ru/arc/core/Tasks.kt` — add `sync`, `async`, `delayedAsync`, `repeatingAsync` parity
- Modify: `arc-core/src/main/kotlin/ru/arc/core/TaskScheduler.kt` — verify API matches ARC interface
- Test: expand `TaskSchedulerTest.kt`

- [ ] **Step 1:** Compare method signatures with `ARC/src/main/kotlin/ru/arc/core/TaskScheduler.kt`
- [ ] **Step 2:** Align + tests
- [ ] **Step 3:** Commit

---

## Chunk 5: Wire consumers

### Task 9: ProxyARC integration

**Files:**
- Modify: `ProxyARC/build.gradle.kts` — `implementation("ru.arc:arc-core-velocity:1.0-SNAPSHOT")` via composite
- Modify: `ProxyARC/settings.gradle.kts` — composite includes all three modules or root arc-core repo
- Delete: `ProxyARC/src/.../VelocityTaskScheduler.kt` (moved)
- Modify: `ProxyARC/.../Velocity.kt` — import from arc-core-velocity

- [ ] **Step 1:** Update dependencies
- [ ] **Step 2:** `./gradlew test` in ProxyARC — PASS
- [ ] **Step 3:** Commit + push ProxyARC

### Task 10: ARC Paper pilot module

**Files:**
- Modify: `ARC/settings.gradle.kts` — add `includeBuild` for arc-core (path: `../arc-core` or env)
- Modify: `ARC/build.gradle.kts` — `implementation("ru.arc:arc-core:1.0-SNAPSHOT")`, `implementation("ru.arc:arc-core-paper:1.0-SNAPSHOT")`
- Modify: one pilot — e.g. `RestartConfig.kt` or `ScheduledCommandsConfig.kt` — extend config from arc-core `Config` via composition OR migrate imports

**Pilot choice:** `ScheduledCommandsConfig` — small, self-contained, uses get() pattern already.

- [ ] **Step 1:** Add composite build to ARC settings
- [ ] **Step 2:** Migrate `ScheduledCommandsConfig` to use `ru.arc.config.Config` from arc-core (adapter layer if needed)
- [ ] **Step 3:** Wire `Tasks.scheduler = BukkitTaskScheduler(plugin)` in module init
- [ ] **Step 4:** `./gradlew test --tests "*ScheduledCommands*"` — PASS
- [ ] **Step 5:** Commit ARC (separate repo)

---

## Chunk 6: Docs & CI

### Task 11: README + usage stubs

**Files:**
- Modify: `README.md` — multi-module layout, phase roadmap
- Create: `docs/USAGE-CONFIG.md`, `docs/USAGE-TASKS.md` (short, Phase A scope)

- [ ] **Step 1:** Update README
- [ ] **Step 2:** Add usage docs
- [ ] **Step 3:** Commit

### Task 12: Verify full matrix

- [ ] **Step 1:** `cd arc-core && ./gradlew testAll` (or `test` on all subprojects)
- [ ] **Step 2:** `cd ProxyARC && ./gradlew test`
- [ ] **Step 3:** `cd ARC && JAVA_HOME=temurin-25 ./gradlew test --tests "*ScheduledCommands*"`
- [ ] **Step 4:** Push arc-core, update mcserver/arc-core submodule pointer

---

## Out of scope (Phase B/C)

- Redis / xserver extraction
- Domain events / VelocityEventBridge full
- ARC full config migration (only pilot in A)
- GitHub Packages publish

---

## Estimated order of execution

1. Chunk 1 (skeleton) — **must be first**
2. Chunk 2 (config) — **blocks pilot**
3. Chunk 3 (modules)
4. Chunk 4 (platform) — can parallelize paper vs velocity after Chunk 1
5. Chunk 5 (wire consumers)
6. Chunk 6 (docs)

**Plan complete.** Ready to execute Phase A?
