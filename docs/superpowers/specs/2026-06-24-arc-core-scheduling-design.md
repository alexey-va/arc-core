# arc-core-scheduling Design

**Date:** 2026-06-24  
**Status:** Approved  
**Consumers:** ARC (Paper), ProxyARC (Velocity)  
**Depends on:** `arc-core`, `arc-core-paper`, `arc-core-velocity`

## Goal

Unify task scheduling behind a platform-agnostic `TaskScheduler` interface with universal Minecraft ticks (50 ms). Feature code in ARC and shared modules must never reference `BukkitTaskScheduler` or `VelocityTaskScheduler` — only `ru.arc.core.*` so services can be copied between Paper and Velocity plugins.

**Non-goals:** Event DSL in arc-core (stays in ARC plugin), Folia entity/region schedulers, repository/xserver extraction.

## Decision Summary

| Choice | Decision |
|--------|----------|
| Module layout | Extend `arc-core` (no new Gradle module); platform impls in `arc-core-paper` / `arc-core-velocity` |
| Wiring | `Tasks.install(scheduler)` once at plugin bootstrap via `*ArcRuntime.installScheduling()` |
| Feature code | `TaskScheduler` interface + global DSL (`delayed`, `repeating`, …); zero platform scheduler imports |
| Ticks | `TickConstants.TICK_MS = 50`; all tick delays are `ticks × 50 ms` |
| Paper | Native Bukkit scheduler for whole-tick sync/async; subtick via executor + main-thread hop |
| Velocity | No game tick — emulate ticks as milliseconds on Velocity scheduler |
| Subtick | `SubtickScheduler` extends tick API with `Duration`-based methods |
| Config | `modules/scheduling.yml` + `SchedulingModuleConfig` (bundled in `arc-core` JAR) |
| Event DSL | Remains in ARC (`EventDsl.kt`), not extracted |

## Repository Layout

```
arc-core/
├── src/main/kotlin/ru/arc/core/
│   TaskScheduler.kt           # existing interface (unchanged contract)
│   SubtickScheduler.kt        # Duration-based scheduling
│   TickConstants.kt           # TICK_MS = 50
│   Tasks.kt                   # install(), scheduler, withScheduler() — merge with TaskDsl
│   TaskDsl.kt                 # port from ARC (Duration, TaskContext, chain, debounce…)
│   TestTaskScheduler.kt       # + advanceMs()
│   scheduling/
│       SchedulingModuleConfig.kt
│       SchedulingConfigBootstrap.kt  # optional legacy migration
├── src/main/resources/modules/
│   scheduling.yml

arc-core-paper/
├── BukkitTaskScheduler.kt     # existing — whole ticks, Bukkit native
├── PaperSubtickScheduler.kt   # implements SubtickScheduler
├── PaperArcRuntime.kt         # + installScheduling(plugin)

arc-core-velocity/
├── VelocityTaskScheduler.kt   # existing — tick emulation via ms
├── VelocitySubtickScheduler.kt # Duration-native (alias/wrapper if needed)
├── VelocityArcRuntime.kt      # + installScheduling(server, plugin)
```

## Public API

### TaskScheduler (unchanged)

```kotlin
interface TaskScheduler {
    fun runAsync(task: Runnable): ScheduledTask
    fun runSync(task: Runnable): ScheduledTask
    fun runLater(delayTicks: Long, task: Runnable): ScheduledTask
    fun runLaterAsync(delayTicks: Long, task: Runnable): ScheduledTask
    fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask
    fun runTimerAsync(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask
    fun cancelAll()
}
```

### SubtickScheduler

```kotlin
interface SubtickScheduler : TaskScheduler {
    fun runLater(duration: Duration, task: Runnable): ScheduledTask
    fun runLaterAsync(duration: Duration, task: Runnable): ScheduledTask
    fun runTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask
    fun runTimerAsync(delay: Duration, period: Duration, task: Runnable): ScheduledTask
}
```

Default tick methods delegate to `duration.inWholeTicks` (50 ms per tick). Sub-ms delays use platform subtick path when `SchedulingModuleConfig.subtickEnabled`.

### Tasks (global accessor)

```kotlin
object Tasks {
    fun install(scheduler: TaskScheduler)
    val scheduler: TaskScheduler  // throws if not installed
    fun reset()                   // tests only
    fun <T> withScheduler(test: TaskScheduler, block: () -> T): T
}
```

**Rule:** `Tasks.install()` must run before `ModuleRegistry.initAll()` in both plugins.

### Duration DSL (from ARC TaskDsl)

- `Int.ticks` / `Long.ticks` → `Duration` (×50 ms)
- `Duration.inWholeTicks` → Long
- Extension methods on `TaskScheduler`: `sync`, `async`, `delayed`, `repeating`, `countdown`, `chain`, `debounce`, …
- Top-level: `delayed(20.ticks) { }`, `repeating(period) { }`, etc.

No `LazyBukkitScheduler` or `ARC.instance` references in arc-core.

## Platform Implementations

### Paper — BukkitTaskScheduler

- `runSync` / `runLater` / `runTimer` → Bukkit main thread, tick-aligned
- `runAsync` / `runLaterAsync` / `runTimerAsync` → Bukkit async pool

### Paper — PaperSubtickScheduler

Wraps `BukkitTaskScheduler`:

- Whole ticks (delay ≥ 50 ms and divisible): Bukkit path
- Sub-tick / arbitrary ms: `ScheduledExecutorService` + `runSync` hop to main thread for sync tasks
- Respects `scheduling.yml` `subtick.min-delay-ms`

### Velocity — VelocityTaskScheduler

- All operations via Velocity `ProxyServer.scheduler`
- `delayTicks * TICK_MS` milliseconds
- `runSync` == proxy main thread (Velocity has no separate async game thread)

### Velocity — VelocitySubtickScheduler

Duration methods map directly to Velocity ms API (subtick is native on proxy).

## Bootstrap Wiring

**ARC.kt (Paper):**

```kotlin
override fun onEnable() {
    PaperArcRuntime.installScheduling(this)  // Tasks.install(PaperSubtickScheduler(...))
    registerModules()
    ModuleRegistry.initAll()
    ...
}
```

**Velocity.kt:**

```kotlin
VelocityArcRuntime.installScheduling(proxyServer, this)  // replaces manual Tasks.scheduler =
registerModules()
ModuleRegistry.initAll()
```

Only these two call sites may import platform scheduler classes.

## Portability Rule

Code is **shared-safe** when imports are limited to:

- `ru.arc.core.TaskScheduler`, `SubtickScheduler`, `ScheduledTask`, `Tasks`
- `ru.arc.core.delayed`, `repeating`, `sync`, `async`, …
- `kotlin.time.Duration`, tick extensions

**Forbidden in shared/feature code:**

- `ru.arc.core.BukkitTaskScheduler` (paper module)
- `ru.arc.velocity.core.VelocityTaskScheduler` (velocity module)
- Instantiating schedulers with `ARC.instance` / plugin references

## ARC Refactoring (9 call sites)

Replace `BukkitTaskScheduler(ARC.instance)` with `Tasks.scheduler`:

| File | Usage |
|------|-------|
| `CooldownManager.kt` | init scheduler |
| `RestartModule.kt` | RestartService |
| `ScheduledCommandsModule.kt` | ScheduledCommandsService |
| `TreasureHuntRegistry.kt` | HuntFurnitureJanitor |
| `CoreModules.kt` | HuntFurnitureJanitor |
| `LocationPoolEditor.kt` | editor tasks |
| `AuditManager.kt` | balance history |
| `FarmManager.kt` | farm tick |
| `MobSpawnManager.kt` | spawn task |

Delete ARC `TaskDsl.kt` after port to arc-core. Keep ARC `EventDsl.kt` (Bukkit-specific).

## Configuration

**Resource:** `modules/scheduling.yml` (bundled in `arc-core` JAR)

```yaml
# Universal Minecraft tick length (ms). Do not change unless server TPS model changes.
tick-ms: 50

subtick:
  enabled: true
  min-delay-ms: 1
```

**SchedulingModuleConfig** — get() accessors pattern (like `RedisModuleConfig`):

- `tickMs: Int` (default 50)
- `subtickEnabled: Boolean`
- `subtickMinDelayMs: Int`

Plugins load via `SchedulingModuleConfig.load(dataPath)` when needed; `TickConstants` reads config version for hot-reload.

## Testing

- `TestTaskScheduler`: existing tick simulation + `advanceMs(ms: Long)`
- Kotest tests for `TaskDsl` extensions using `TestTaskScheduler`
- `SchedulingModuleConfigTest` with `TestSchedulingModuleConfig`
- ARC tests: replace direct `BukkitTaskScheduler` with `TestTaskScheduler` / `Tasks.withScheduler`
- No MockBukkit required for scheduler unit tests

## Success Criteria

1. `arc-core` contains full TaskDsl; ARC deletes duplicate `TaskDsl.kt`
2. Zero `BukkitTaskScheduler` imports in ARC feature code (grep clean)
3. ProxyARC uses `VelocityArcRuntime.installScheduling()` only
4. `Tasks.install()` called before module init on both platforms
5. `./gradlew test` passes in arc-core, ARC, ProxyARC
6. `modules/scheduling.yml` bundled and overridable on disk

## Future (out of scope)

- Folia `GlobalRegionScheduler` / entity schedulers
- Shared Gradle module for cross-plugin services (restart, scheduled commands)
- Event DSL extraction
