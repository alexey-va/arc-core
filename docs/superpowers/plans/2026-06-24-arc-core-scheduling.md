# arc-core-scheduling Implementation Plan

**Goal:** Platform-agnostic TaskScheduler + TaskDsl in `arc-core` with Paper/Velocity wiring via `Tasks.install()`; ARC feature code uses only the interface (no `BukkitTaskScheduler` references).

**Architecture:** Port ARC `TaskDsl.kt` into `arc-core`, add `SubtickScheduler` + `SchedulingModuleConfig` + bundled `scheduling.yml`. Paper/Velocity install concrete schedulers once at bootstrap. Remove duplicate `Tasks` object and all direct `BukkitTaskScheduler` construction from ARC.

**Tech Stack:** Kotlin 2.3, Java 25, Kotest 6, MockK, Paper API (compileOnly in arc-core-paper), Velocity API (compileOnly in arc-core-velocity).

**Spec:** `docs/superpowers/specs/2026-06-24-arc-core-scheduling-design.md`

**Repos:** `~/IdeaProjects/arc-core`, `~/IdeaProjects/ARC`, `~/mcserver/ProxyARC`

---

## File map

| Action | Path |
|--------|------|
| Create | `arc-core/.../TickConstants.kt` |
| Create | `arc-core/.../SubtickScheduler.kt` |
| Create | `arc-core/.../scheduling/SchedulingModuleConfig.kt` |
| Create | `arc-core/.../scheduling/SchedulingConfigBootstrap.kt` |
| Create | `arc-core/src/main/resources/modules/scheduling.yml` |
| Modify | `arc-core/.../Tasks.kt` — merge install API, remove duplicate with TaskDsl |
| Create | `arc-core/.../TaskDsl.kt` — port from ARC (no Bukkit/ARC refs) |
| Modify | `arc-core/.../TestTaskScheduler.kt` — add `advanceMs()` |
| Create | `arc-core/src/test/.../SchedulingModuleConfigTest.kt` |
| Create | `arc-core/src/test/.../TaskDslTest.kt` |
| Create | `arc-core-paper/.../PaperSubtickScheduler.kt` |
| Modify | `arc-core-paper/.../PaperArcRuntime.kt` — `installScheduling(plugin)` |
| Modify | `arc-core-paper/.../BukkitTaskScheduler.kt` — use `TickConstants` |
| Create | `arc-core-velocity/.../VelocitySubtickScheduler.kt` |
| Modify | `arc-core-velocity/.../VelocityArcRuntime.kt` — `installScheduling()` |
| Modify | `arc-core-velocity/.../VelocityTaskScheduler.kt` — use `TickConstants` |
| Modify | `ARC/build.gradle.kts` — shadowJar bundle `scheduling.yml` if needed |
| Modify | `ARC/ARC.kt` — call `PaperArcRuntime.installScheduling(this)` before modules |
| Delete | `ARC/.../core/TaskDsl.kt` |
| Modify | 9 ARC files — replace `BukkitTaskScheduler(ARC.instance)` → `Tasks.scheduler` |
| Modify | `ProxyARC/.../Velocity.kt` — use `VelocityArcRuntime.installScheduling()` |
| Modify | ARC tests using `TaskDsl` / scheduler |

---

## Chunk 1: Config + tick constants

### Task 1: scheduling.yml + SchedulingModuleConfig

**Files:**
- Create: `arc-core/src/main/resources/modules/scheduling.yml`
- Create: `arc-core/src/main/kotlin/ru/arc/core/scheduling/SchedulingModuleConfig.kt`
- Create: `arc-core/src/test/kotlin/ru/arc/core/scheduling/SchedulingModuleConfigTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
class SchedulingModuleConfigTest : FreeSpec({
    "TestSchedulingModuleConfig" - {
        "should default tick-ms to 50" {
            TestSchedulingModuleConfig().tickMs shouldBe 50
        }
    }
})
```

- [ ] **Step 2: Run test** — `cd arc-core && ./gradlew :arc-core:test --tests "*SchedulingModuleConfig*"` → FAIL

- [ ] **Step 3: Implement** `SchedulingModuleConfig` (mirror `RedisModuleConfig` pattern), `scheduling.yml`, `TestSchedulingModuleConfig`

- [ ] **Step 4: Run test** → PASS

- [ ] **Step 5: Commit** `feat(scheduling): add SchedulingModuleConfig + scheduling.yml`

### Task 2: TickConstants

**Files:**
- Create: `arc-core/src/main/kotlin/ru/arc/core/TickConstants.kt`

- [ ] **Step 1:** Add `object TickConstants { const val TICK_MS = 50L; fun ticksToMillis(ticks: Long) = ticks * TICK_MS }`

- [ ] **Step 2:** Replace `ExecutorTaskScheduler.ticksToMillis` to delegate to `TickConstants`

- [ ] **Step 3:** Commit `refactor: centralize tick-ms in TickConstants`

---

## Chunk 2: Tasks.install + merge Tasks objects

### Task 3: Unified Tasks accessor

**Files:**
- Modify: `arc-core/src/main/kotlin/ru/arc/core/Tasks.kt`

- [ ] **Step 1: Write failing test** in `TasksTest.kt`:

```kotlin
"should throw when scheduler not installed" {
    Tasks.reset()
    shouldThrow<IllegalStateException> { Tasks.scheduler }
}
"should return installed scheduler" {
    val test = TestTaskScheduler()
    Tasks.install(test)
    Tasks.scheduler shouldBe test
}
```

- [ ] **Step 2: Run test** → FAIL

- [ ] **Step 3: Implement**

```kotlin
object Tasks {
    @Volatile private var installed: TaskScheduler? = null
    fun install(scheduler: TaskScheduler) { installed = scheduler }
    val scheduler: TaskScheduler get() = installed ?: error("Tasks.install() not called")
    fun reset() { installed?.cancelAll(); installed = null }
    // keep withScheduler from existing Tasks.kt
}
```

Remove duplicate top-level `delayed`/`repeating` Runnable overloads from old `Tasks.kt` (will live in TaskDsl).

- [ ] **Step 4: Run arc-core tests** → PASS

- [ ] **Step 5: Commit** `feat: Tasks.install() for platform scheduler wiring`

---

## Chunk 3: Port TaskDsl to arc-core

### Task 4: Move TaskDsl without platform leaks

**Files:**
- Create: `arc-core/src/main/kotlin/ru/arc/core/TaskDsl.kt`
- Delete later: `ARC/src/main/kotlin/ru/arc/core/TaskDsl.kt`

- [ ] **Step 1: Copy** ARC `TaskDsl.kt` → arc-core

- [ ] **Step 2: Remove** from arc-core copy:
  - `import ru.arc.ARC`
  - `LazyBukkitScheduler` object entirely
  - duplicate `object Tasks` block (use unified `Tasks` from Tasks.kt)
  - `import ru.arc.core.BukkitTaskScheduler` if any

- [ ] **Step 3: Write TaskDslTest** — delayed/repeating with TestTaskScheduler:

```kotlin
Tasks.withScheduler(TestTaskScheduler()) {
    var ran = false
    delayed(20.ticks) { ran = true }
    // advance via test scheduler tick(20)
    ran shouldBe true
}
```

- [ ] **Step 4: Run** `./gradlew :arc-core:test --tests "*TaskDsl*"` → PASS

- [ ] **Step 5: Commit** `feat: port TaskDsl to arc-core`

### Task 5: TestTaskScheduler advanceMs

**Files:**
- Modify: `arc-core/src/main/kotlin/ru/arc/core/TestTaskScheduler.kt`

- [ ] **Step 1: Test** `advanceMs(25)` fires tasks scheduled at 0.5 tick equivalent

- [ ] **Step 2: Implement** ms-granularity tracking alongside tick counter OR convert internal model to ms using `TickConstants.TICK_MS`

- [ ] **Step 3: Commit** `feat: TestTaskScheduler.advanceMs for subtick tests`

---

## Chunk 4: SubtickScheduler + platform impls

### Task 6: SubtickScheduler interface

**Files:**
- Create: `arc-core/src/main/kotlin/ru/arc/core/SubtickScheduler.kt`

- [ ] **Step 1:** Define interface with Duration methods; default tick methods in companion delegating object optional

- [ ] **Step 2: Commit** `feat: SubtickScheduler interface`

### Task 7: PaperSubtickScheduler

**Files:**
- Create: `arc-core-paper/src/main/kotlin/ru/arc/core/PaperSubtickScheduler.kt`
- Modify: `arc-core-paper/src/main/kotlin/ru/arc/core/PaperArcRuntime.kt`

- [ ] **Step 1: Implement** wrapping `BukkitTaskScheduler`:
  - Duration ≥ 1 tick and aligned → Bukkit
  - else → `ScheduledExecutorService` + `runSync` hop

- [ ] **Step 2: Add** `PaperArcRuntime.installScheduling(plugin: Plugin)`:

```kotlin
fun installScheduling(plugin: Plugin) {
    Tasks.install(PaperSubtickScheduler(BukkitTaskScheduler(plugin), plugin))
}
```

- [ ] **Step 3: Paper module test** (optional MockBukkit-free unit test with mocked Plugin if too heavy — skip MockBukkit, test ms routing logic in isolation)

- [ ] **Step 4: Commit** `feat(paper): PaperSubtickScheduler + installScheduling`

### Task 8: VelocitySubtickScheduler

**Files:**
- Create: `arc-core-velocity/src/main/kotlin/ru/arc/velocity/core/VelocitySubtickScheduler.kt`
- Modify: `arc-core-velocity/src/main/kotlin/ru/arc/core/VelocityArcRuntime.kt`

- [ ] **Step 1:** Wrap `VelocityTaskScheduler`; Duration methods use `duration.inWholeMilliseconds` directly

- [ ] **Step 2:** `VelocityArcRuntime.installScheduling(server, plugin)` → `Tasks.install(...)`

- [ ] **Step 3: Commit** `feat(velocity): VelocitySubtickScheduler + installScheduling`

---

## Chunk 5: Wire plugins

### Task 9: ARC bootstrap

**Files:**
- Modify: `ARC/src/main/kotlin/ru/arc/ARC.kt`
- Delete: `ARC/src/main/kotlin/ru/arc/core/TaskDsl.kt`

- [ ] **Step 1:** In `onEnable()`, before `registerModules()`:

```kotlin
PaperArcRuntime.installScheduling(this)
```

- [ ] **Step 2:** Delete ARC `TaskDsl.kt` (now in arc-core dependency)

- [ ] **Step 3:** Verify compile: `cd ARC && JAVA_HOME=... ./gradlew compileKotlin`

- [ ] **Step 4: Commit** ARC repo `refactor: use PaperArcRuntime.installScheduling, drop local TaskDsl`

### Task 10: ProxyARC bootstrap

**Files:**
- Modify: `ProxyARC/src/main/kotlin/ru/arc/velocity/Velocity.kt`

- [ ] **Step 1:** Replace `Tasks.scheduler = VelocityTaskScheduler(...)` with `VelocityArcRuntime.installScheduling(proxyServer!!, this)`

- [ ] **Step 2:** Remove direct import of `VelocityTaskScheduler` from Velocity.kt if unused

- [ ] **Step 3: Commit** ProxyARC `refactor: VelocityArcRuntime.installScheduling`

---

## Chunk 6: Remove BukkitTaskScheduler from ARC feature code

### Task 11: Replace 9 call sites

**Files:** (each: remove `import ru.arc.core.BukkitTaskScheduler`, use `Tasks.scheduler`)

- `CooldownManager.kt`
- `RestartModule.kt`
- `ScheduledCommandsModule.kt`
- `TreasureHuntRegistry.kt`
- `CoreModules.kt`
- `LocationPoolEditor.kt`
- `AuditManager.kt`
- `FarmManager.kt`
- `MobSpawnManager.kt`

- [ ] **Step 1:** Replace each `BukkitTaskScheduler(ARC.instance)` with `Tasks.scheduler`

- [ ] **Step 2:** Grep verify:

```bash
rg 'BukkitTaskScheduler' ARC/src/main/kotlin
```

Expected: **no matches** (except none — paper class not in ARC sources)

- [ ] **Step 3:** Run ARC tests: `JAVA_HOME=... ./gradlew test`

- [ ] **Step 4: Commit** `refactor: use Tasks.scheduler in all feature modules`

---

## Chunk 7: Shadow JAR + integration verify

### Task 12: Bundle scheduling.yml

**Files:**
- Modify: `ARC/build.gradle.kts` (if scheduling.yml not already pulled from arc-core jar)

- [ ] **Step 1:** Ensure `modules/scheduling.yml` lands in ARC plugin data folder on first run (via arc-core resource or ConfigManager bootstrap — mirror redis.yml shadowJar pattern if used)

- [ ] **Step 2: Commit** if build.gradle changed

### Task 13: Full verification

- [ ] **Step 1:** `cd arc-core && ./gradlew test publishToMavenLocal`

- [ ] **Step 2:** `cd ARC && ./gradlew test`

- [ ] **Step 3:** `cd ProxyARC && ./gradlew test`

- [ ] **Step 4:** Manual smoke: ProxyARC `delayed(20)` in JoinListener still works; ARC farm/cooldown tasks start on enable

---

## Chunk 8: Documentation commit (arc-core)

- [ ] **Step 1:** Ensure spec + plan committed in `arc-core` repo:

```bash
cd ~/IdeaProjects/arc-core
git add docs/superpowers/specs/2026-06-24-arc-core-scheduling-design.md \
        docs/superpowers/plans/2026-06-24-arc-core-scheduling.md
git commit -m "docs: arc-core-scheduling design and implementation plan"
```

---

## Risk notes

| Risk | Mitigation |
|------|------------|
| Duplicate `Tasks` object at runtime | Delete ARC TaskDsl `object Tasks` before merge; single definition in arc-core |
| `Tasks.scheduler` used before install | `installScheduling` first line in `onEnable`; `IllegalStateException` if mis-ordered |
| Subtick on Paper runs off main thread | Always hop via `runSync` for sync subtick tasks |
| ARC tests import old TaskDsl paths | Same package `ru.arc.core.*` — imports unchanged after delete |

## Execution order

1. Chunk 1–2 (config + Tasks.install) — arc-core only  
2. Chunk 3 (TaskDsl port) — arc-core, publish local  
3. Chunk 4 (platform schedulers) — arc-core-paper/velocity  
4. Chunk 5–6 (plugin wiring + ARC cleanup)  
5. Chunk 7–8 (verify + docs)
