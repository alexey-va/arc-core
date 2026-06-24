# ProxyARC Module System Design

**Date:** 2026-06-24  
**Status:** Approved  
**Parent:** [arc-core framework design](./2026-06-24-arc-core-framework-design.md)  
**Track:** ProxyARC-first (before Phase B `arc-core-redis`)

## Goal

Replace `CommonCore` god-object with the same **PluginModule + ModuleRegistry** pattern used on ARC Paper. Shared runtime state lives on **`Velocity` companion** (mirrors `ARC.redisManager`, `ARC.plugin`). Pretty init via **`VelocityArcRuntime`** in `arc-core-velocity`.

## Non-Goals

- Extracting Redis/xserver into `arc-core-redis` (Phase B)
- Changing ARC Paper modules
- EventDsl / full domain events (Phase C)

## Architecture

```
Velocity.onProxyInit
  ├── Tasks.scheduler = VelocityTaskScheduler
  ├── VelocityArcRuntime.installModuleLifecycleReporting(...)
  ├── registerModules()          // grouped list, like ARC.registerModules()
  ├── ModuleRegistry.initAll()
  └── registerCommands() / Tools (stay on plugin class)

ModuleRegistry.shutdownAll() on proxy stop
ModuleRegistry.reloadAll() on /proxyarc reload (+ ConfigManager.reloadAll())
```

## Package Layout (ARC-style)

Mirror ARC: **one module object per file** for non-trivial features; **grouped infrastructure** in `core/modules/`.

```
ProxyARC/src/main/kotlin/
├── ru/arc/velocity/Velocity.kt          ← companion holds all shared refs
├── ru/arc/core/modules/
│   ├── InfrastructureModules.kt         ← Logging, Config, Redis, Network
│   ├── PersistenceModules.kt            ← FirstJoin, Save
│   ├── CrossServerModules.kt            ← PlayerListAnnouncer, JoinMessages repo
│   └── RuntimeModules.kt                ← Listeners, ProxyTasks
├── ru/arc/hooks/HooksModule.kt
├── ru/arc/discord/DiscordModule.kt
├── ru/arc/telegram/TelegramModule.kt
├── ru/arc/ai/AssistantModule.kt
└── ru/arc/AntibotModule.kt
```

**Delete:** `CommonCore.kt`

## Velocity Companion (shared state)

Same fields as today’s `CommonCore`, on `Velocity` companion:

| Field | Set by module |
|-------|----------------|
| `config`, `serverName` | `ConfigModule` |
| `redisManager` | `RedisModule` |
| `networkRegistry` | `NetworkModule` |
| `discordBot` | `DiscordModule` |
| `telegramBot` | `TelegramModule` |
| `firstJoinData` | `FirstJoinModule` |
| `playerListAnnouncer` | `PlayerListModule` |
| `joinMessagesRedisRepo` | `JoinMessagesModule` |
| `antibot` | `AntibotModule` |
| `chatAssistant` | `AssistantModule` |
| `luckpermsHook`, `liteBansHook` | `HooksModule` |

**Migration map:** `CommonCore.inst` → `Velocity.plugin`, `CommonCore.folder` → `Velocity.dataFolder`, `CommonCore.config` → `Velocity.config`, `CommonCore.serverName` → `Velocity.serverName`.

## Modules & Priorities

Section headers match ARC `CoreModules.kt` style.

### Priority 10–29: Core Infrastructure

| Module | File | Priority | init | shutdown |
|--------|------|----------|------|----------|
| `LoggingModule` | `InfrastructureModules.kt` | 10 | Loki appender | — |
| `RedisModule` | `InfrastructureModules.kt` | 15 | `RedisManager` connect | close jedis |
| `NetworkModule` | `InfrastructureModules.kt` | 20 | `NetworkRegistry.init()` | — |
| `ConfigModule` | `InfrastructureModules.kt` | 25 | load `config.yml`, set `serverName` | — |

### Priority 30–49: Configuration & Hooks

| Module | File | Priority | init |
|--------|------|----------|------|
| `HooksModule` | `HooksModule.kt` | 30 | LuckPerms + LiteBans |

### Priority 50–69: Persistence & Cross-Server

| Module | File | Priority | init | shutdown |
|--------|------|----------|------|----------|
| `FirstJoinModule` | `PersistenceModules.kt` | 50 | load JSON | save |
| `SaveModule` | `PersistenceModules.kt` | 55 | 60s scheduled save | cancel task |
| `PlayerListModule` | `CrossServerModules.kt` | 60 | `PlayerListAnnouncer` | — |
| `JoinMessagesModule` | `CrossServerModules.kt` | 65 | `RedisRepo<JoinMessages>` | repo shutdown |

### Priority 70–89: Integrations

| Module | File | Priority | init | shutdown |
|--------|------|----------|------|----------|
| `DiscordModule` | `DiscordModule.kt` | 70 | JDA bot start | JDA shutdown |
| `TelegramModule` | `TelegramModule.kt` | 75 | optional bot | stop bot |
| `AntibotModule` | `AntibotModule.kt` | 80 | `Antibot` | — |
| `AssistantModule` | `AssistantModule.kt` | 85 | chat `Assistant` | — |

### Priority 90–99: Runtime

| Module | File | Priority | init | shutdown |
|--------|------|----------|------|----------|
| `ListenersModule` | `RuntimeModules.kt` | 90 | register Join/Chat on Velocity | unregister |
| `ProxyTasksModule` | `RuntimeModules.kt` | 95 | repeating Discord list + Redis announce | cancel tasks |

**Total: 15 modules** (compare ARC ~25) — room to split further in Phase B (e.g. `AuctionModule`).

## registerModules() (Velocity.kt)

Grouped comments like `ARC.registerModules()`:

```kotlin
private fun registerModules() {
    ModuleRegistry.registerAll(
        // Core infrastructure (10-29)
        LoggingModule, RedisModule, NetworkModule, ConfigModule,
        // Hooks (30)
        HooksModule,
        // Persistence & cross-server (50-69)
        FirstJoinModule, SaveModule, PlayerListModule, JoinMessagesModule,
        // Integrations (70-89)
        DiscordModule, TelegramModule, AntibotModule, AssistantModule,
        // Runtime (90-99)
        ListenersModule, ProxyTasksModule,
    )
}
```

## Reload & Shutdown

**Reload** (`/proxyarc reload` + `ProxyReloadEvent`):

1. `ConfigManager.reloadAll()`
2. `ModuleRegistry.reloadAll()` — modules re-read configs; `RedisModule.reload()` reconnects if needed
3. `Assistant.assistants.forEach { reload() }` (unchanged)

**Shutdown** (`ProxyShutdownEvent`):

1. `Velocity.isShuttingDown = true`
2. `ModuleRegistry.shutdownAll()` (reverse priority)
3. `Tasks.scheduler.cancelAll()`
4. Final `FirstJoinModule` save if not already done in shutdown

## arc-core-velocity

Add `VelocityArcRuntime.installModuleLifecycleReporting(consoleLog, logError)` — delegates to `PrettyModuleLifecycleReporter` (same as Paper).

Proxy console: slf4j → plain, or Adventure if Velocity console API used later.

## Consumer Refactors

| File | Change |
|------|--------|
| `JoinListener`, `ChatListener` | Remove `CommonCore` ctor; use `Velocity.*` |
| `ProxyARCCommand` | `Velocity.discordBot` |
| `RedisManager`, `RedisRepoMessager` | `Velocity.serverName`, `Velocity.config` |
| `DiscordBot`, `DiscordListener`, `TelegramBot` | `Velocity.dataFolder`, `Velocity.plugin` |
| `FirstJoinData` | `Velocity.dataFolder!!.resolve(...)` |
| `Utils.formatTime` | `Velocity.dataFolder` |

## Testing

| Test | Scope |
|------|-------|
| `ProxyModuleOrderTest` | Kotest: fake modules, assert init/shutdown order |
| `InfrastructureModulesTest` | ConfigModule sets serverName (mock path) |
| Existing `DefaultsTest`, `ToolsRegistryTest` | Must stay green |
| Manual | Deploy proxy, pretty module list, join/chat/discord smoke |

## Success Criteria

- [ ] `CommonCore.kt` deleted; zero `CommonCore` references
- [ ] Proxy startup shows pretty module init (15 lines)
- [ ] `/proxyarc reload` reloads modules + configs
- [ ] Graceful shutdown closes Redis/Discord/Telegram
- [ ] `./gradlew test` green on ProxyARC

## Phase B Follow-Up

When `arc-core-redis` lands: `RedisModule` / `NetworkModule` / `JoinMessagesModule` swap imports from local `xserver` to shared module — **no change to Velocity.registerModules() list**.
