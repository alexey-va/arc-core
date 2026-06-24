# ProxyARC Module Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete `CommonCore`; run ProxyARC through `ModuleRegistry` with 15 ARC-style modules and `Velocity` companion state.

**Architecture:** One `PluginModule` per concern, files grouped under `ru/arc/core/modules/` + feature packages. Pretty lifecycle via `VelocityArcRuntime` in `arc-core-velocity`.

**Tech Stack:** Kotlin 2.3, Java 25, Velocity 3.3, arc-core + arc-core-velocity, Kotest + MockK.

**Spec:** [2026-06-24-proxyarc-modules-design.md](../specs/2026-06-24-proxyarc-modules-design.md)

**Repo paths:** `~/mcserver/ProxyARC` (canonical), composite `~/IdeaProjects/arc-core`

---

## File Map

| Action | Path |
|--------|------|
| Create | `arc-core-velocity/.../VelocityArcRuntime.kt` |
| Create | `ProxyARC/.../core/modules/InfrastructureModules.kt` |
| Create | `ProxyARC/.../core/modules/PersistenceModules.kt` |
| Create | `ProxyARC/.../core/modules/CrossServerModules.kt` |
| Create | `ProxyARC/.../core/modules/RuntimeModules.kt` |
| Create | `ProxyARC/.../hooks/HooksModule.kt` |
| Create | `ProxyARC/.../discord/DiscordModule.kt` |
| Create | `ProxyARC/.../telegram/TelegramModule.kt` |
| Create | `ProxyARC/.../ai/AssistantModule.kt` |
| Create | `ProxyARC/.../AntibotModule.kt` |
| Create | `ProxyARC/src/test/.../ProxyModuleOrderTest.kt` |
| Modify | `ProxyARC/.../velocity/Velocity.kt` |
| Modify | `JoinListener.kt`, `ChatListener.kt`, `ProxyARCCommand.kt` |
| Modify | `RedisManager.kt`, `RedisRepoMessager.kt`, `DiscordBot.kt`, `DiscordListener.kt`, `TelegramBot.kt`, `FirstJoinData.kt`, `Utils.kt` |
| Delete | `ProxyARC/.../CommonCore.kt` |

---

### Task 1: VelocityArcRuntime (arc-core)

**Files:**
- Create: `arc-core-velocity/src/main/kotlin/ru/arc/core/VelocityArcRuntime.kt`

- [ ] **Step 1:** Copy pattern from `PaperArcRuntime.kt` — `installModuleLifecycleReporting(consoleLog, logError)` → `PrettyModuleLifecycleReporter`
- [ ] **Step 2:** Run `./gradlew :arc-core-velocity:compileKotlin` in arc-core
- [ ] **Step 3:** Commit arc-core

---

### Task 2: Velocity companion fields

**Files:**
- Modify: `ProxyARC/src/main/kotlin/ru/arc/velocity/Velocity.kt`

- [ ] **Step 1:** Add all `@JvmField` refs from spec (`config`, `serverName`, `redisManager`, …)
- [ ] **Step 2:** Remove `commonCore: CommonCore` field
- [ ] **Step 3:** Compile (expect errors until modules wired)

---

### Task 3: InfrastructureModules

**Files:**
- Create: `ProxyARC/src/main/kotlin/ru/arc/core/modules/InfrastructureModules.kt`

- [ ] **Step 1:** Port logic from `CommonCore.init()` into `LoggingModule`, `RedisModule`, `NetworkModule`, `ConfigModule` with priorities 10/15/20/25
- [ ] **Step 2:** Each module sets `Velocity.*` fields; `RedisModule.shutdown()` closes connections
- [ ] **Step 3:** KDoc + section header `// Priority 10-29: Core Infrastructure`

---

### Task 4: HooksModule

**Files:**
- Create: `ProxyARC/src/main/kotlin/ru/arc/hooks/HooksModule.kt`

- [ ] **Step 1:** Move LuckPerms + LiteBans init from CommonCore (try/catch per hook)
- [ ] **Step 2:** Set `Velocity.luckpermsHook`, `Velocity.liteBansHook`

---

### Task 5: PersistenceModules

**Files:**
- Create: `ProxyARC/src/main/kotlin/ru/arc/core/modules/PersistenceModules.kt`

- [ ] **Step 1:** `FirstJoinModule` — load/save `FirstJoinData` on `Velocity.firstJoinData`
- [ ] **Step 2:** `SaveModule` — `ScheduledExecutorService` 60s tick calling save; cancel on shutdown
- [ ] **Step 3:** Move save executor off deleted CommonCore

---

### Task 6: CrossServerModules

**Files:**
- Create: `ProxyARC/src/main/kotlin/ru/arc/core/modules/CrossServerModules.kt`

- [ ] **Step 1:** `PlayerListModule` — create `PlayerListAnnouncer`
- [ ] **Step 2:** `JoinMessagesModule` — build `RedisRepo<JoinMessages>` (requires redis from step 3)

---

### Task 7: Feature modules (separate files)

**Files:**
- Create: `discord/DiscordModule.kt`, `telegram/TelegramModule.kt`, `AntibotModule.kt`, `ai/AssistantModule.kt`

- [ ] **Step 1:** Port Discord/Telegram/Antibot/Assistant init from CommonCore
- [ ] **Step 2:** `TelegramModule.enabled` gate from `telegram.yml`
- [ ] **Step 3:** `DiscordModule.shutdown()` — JDA shutdown if applicable

---

### Task 8: RuntimeModules

**Files:**
- Create: `ProxyARC/src/main/kotlin/ru/arc/core/modules/RuntimeModules.kt`

- [ ] **Step 1:** `ListenersModule` — register `JoinListener`, `ChatListener` (no CommonCore in ctor)
- [ ] **Step 2:** `ProxyTasksModule` — move `repeating` tasks from `Velocity.onProxyInit` (discord player list + redis announce)
- [ ] **Step 3:** Store `ScheduledTask` refs; cancel in `shutdown()`

---

### Task 9: Wire Velocity lifecycle

**Files:**
- Modify: `Velocity.kt`

- [ ] **Step 1:** Add `registerModules()` with grouped `ModuleRegistry.registerAll(...)` (15 modules)
- [ ] **Step 2:** `onProxyInit`: scheduler → `VelocityArcRuntime.install...` → `registerModules()` → `initAll()` → commands/tools
- [ ] **Step 3:** `onProxyReload`: save + `ConfigManager.reloadAll()` + `ModuleRegistry.reloadAll()`
- [ ] **Step 4:** `onProxyStop`: `shutdownAll()` + cancel orphan tasks
- [ ] **Step 5:** Remove inline init body (println only in banner optional)

---

### Task 10: Migrate CommonCore call sites

**Files:** (see spec table)

- [ ] **Step 1:** Replace all `CommonCore.*` with `Velocity.*`
- [ ] **Step 2:** Update listener constructors — drop `CommonCore` param
- [ ] **Step 3:** `ProxyARCCommand` uses `Velocity.discordBot`
- [ ] **Step 4:** Delete `CommonCore.kt`
- [ ] **Step 5:** `rg CommonCore` → zero matches

---

### Task 11: Tests

**Files:**
- Create: `ProxyARC/src/test/kotlin/ru/arc/core/ProxyModuleOrderTest.kt`

- [ ] **Step 1:** Kotest FreeSpec — register 3 fake modules with order tracking, `ModuleRegistry.initAll` / `shutdownAll`
- [ ] **Step 2:** Run `./gradlew test` on ProxyARC
- [ ] **Step 3:** Run arc-core `testAll`

---

### Task 12: Deploy & verify

- [ ] **Step 1:** `cd mcserver && ./scripts/mc proxyarc --fast`
- [ ] **Step 2:** Confirm pretty module init in proxy console (15 modules)
- [ ] **Step 3:** Smoke: player join, `!` chat to discord path, `/proxyarc reload`

---

## Dependency Order

```
Task 1 (arc-core-velocity)
  → Task 2-8 (modules, parallelizable 4-8 after 3)
  → Task 9 (wire)
  → Task 10 (call sites + delete)
  → Task 11-12 (verify)
```

## Notes

- Keep module `name` strings stable for ops/logs (match class name without "Module" where obvious: `"Redis"`, `"Discord"`, …).
- `JoinMessagesModule` must run after `RedisModule` (priority 65 > 15).
- `ListenersModule` last before tasks so all `Velocity.*` refs are non-null.
