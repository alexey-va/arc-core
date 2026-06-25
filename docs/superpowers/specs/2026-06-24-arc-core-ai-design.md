# arc-core-ai Design

**Date:** 2026-06-24  
**Status:** Approved  
**Consumers:** ARC (Paper), ProxyARC (Velocity)  
**Depends on:** `arc-core`, `arc-core-redis`, OpenAI Java SDK (OpenRouter-compatible)

## Goal

Extract a shared **AI framework** into `arc-core-ai`: OpenRouter LLM client, moderation helpers, chat sessions with optional tool-calling, and Redis-based **tool RPC** so Velocity agents can invoke Bukkit-only tools on Paper backends.

**Constraints (user):**

1. **Both** Velocity and Paper call OpenRouter — moderation **stays on Paper** (board GUI, etc.).
2. HTTP to OpenRouter goes through **HTTP proxy** (tinyproxy, no auth) in production; config must allow **disabling proxy** for local dev.
3. Placeholder proxy host in bundled YAML; real IP set at deploy time.

**Non-goals (v1):** Tool side-effects (give/kick); streaming; multi-provider beyond OpenRouter-compatible API; moving moderation to Velocity.

## Decision Summary

| Choice | Decision |
|--------|----------|
| Module | New Gradle module `arc-core-ai` |
| LLM client | OpenAI Java SDK, `baseUrl` = OpenRouter, shared factory in core |
| Proxy | `http-proxy.enabled` + `host` + `port`; if enabled, client **must** use `Proxy.Type.HTTP` |
| Moderation | `ModerationService` on **Paper** only |
| Chat + tools | `ChatAgent` on **Velocity**; tools via Redis |
| NPC chat | **Paper** keeps UI (Citizens); LLM via shared client (replaces `GPTEntity` HttpClient) |
| Tool transport | Evolve existing `arc.ai_tools_req/res` → typed `ToolRpc` v2 |
| Legacy | Deprecate `GPTEntity` raw HttpClient; migrate `Assistant.createClient` to shared factory |

## Architecture

```
arc-core-ai
├── config/          LlmModuleConfig, HttpProxyConfig
├── llm/             OpenRouterLlmClient, LlmChatSession, ModerationService
├── tools/           ToolDefinition, ToolRouting, ToolRpcClient, ToolExecutor (SPI)
└── resources/modules/llm.yml

Paper ARC                          Velocity ProxyARC
├── ModerationService (local LLM)    ├── ChatAgent (LLM + tool loop)
├── NpcChatService (local LLM)     ├── ToolRpcClient → Redis
├── ToolRpcServer (executors)        └── PlayerList routing
└── GetPlayerInfo, GetInventory…
```

### Who calls what

| Scenario | LLM host | Tools |
|----------|----------|-------|
| Board moderation (`AddBoardGui`) | Paper | — |
| NPC conversation (`GPTManager`) | Paper | — (v1) |
| Proxy chat assistant | Velocity | Redis → Paper |
| `GetInventory`, `GetPlayerInfo` | — | Paper executor |

## Repository Layout

```
arc-core/
├── settings.gradle.kts          + include("arc-core-ai")
│
arc-core-ai/
├── build.gradle.kts             api: arc-core, arc-core-redis; implementation: openai-java
├── src/main/kotlin/ru/arc/ai/
│   config/
│       LlmModuleConfig.kt
│       LlmConfigBootstrap.kt      # migrate gpt.yml / assistant api-key sections
│   llm/
│       OpenRouterLlmClient.kt     # factory: SDK + optional HTTP proxy
│       LlmChatSession.kt          # history trim, completion, tool loop orchestration
│       ModerationService.kt       # single-shot OK/BAD + comment parsing
│   tools/
│       ToolDefinition.kt
│       ToolRouting.kt             # BROADCAST | BY_PLAYER | TARGET_SERVER
│       ToolInvokeRequest.kt
│       ToolInvokeResult.kt
│       ToolRpcClient.kt           # publish invoke, await result(s)
│       ToolExecutor.kt            # SPI: execute(name, json) → json
│       ToolRegistry.kt            # name → definition (schema for LLM)
│   modules/
│       AiModuleConfig.kt          # enabled, timeouts
├── src/main/resources/modules/
│       llm.yml
└── src/test/kotlin/...            # Kotest: config validation, routing, mock RPC

arc-core-paper/   (optional thin wiring, or stay in ARC plugin)
└── PaperToolRpcServer.kt          # registers Redis listener, dispatches ToolExecutor

ProxyARC/         migrate ru.arc.ai.* to use arc-core-ai
ARC/              migrate GPTManager, ToolMessanger → arc-core-ai
```

## Configuration

Bundled `modules/llm.yml`:

```yaml
# OpenRouter (OpenAI-compatible). Runtime: set api-key + proxy host on deploy.

openrouter:
  api-base-url: https://openrouter.ai/api/v1
  api-key: "none"
  timeout-seconds: 30

http-proxy:
  enabled: true
  host: "10.255.0.1"    # PLACEHOLDER — replace at deploy (tinyproxy, no auth)
  port: 8888

moderation:
  model: openai/gpt-4o-mini
  max-tokens: 250
  temperature: 0.2
  ok-marker: "OK"
  bad-marker: "BAD"
  comment-marker: "COMMENT:"

tools:
  invoke-channel: arc.ai.tools.invoke
  result-channel: arc.ai.tools.result
  default-timeout-ms: 30000
```

**Validation (`LlmModuleConfig`):**

- `api-key == "none"` → LLM disabled (log warn; moderation returns empty like today)
- If `http-proxy.enabled` → require non-blank `host`, `port > 0`
- If `http-proxy.enabled: false` → direct connection (local dev only)

ProxyARC `assistant.yml` keeps **prompts**, **chat** toggles; **api-key/proxy** move to shared `llm.yml` (bootstrap copies legacy keys once).

## OpenRouterLlmClient

```kotlin
class OpenRouterLlmClient(private val config: LlmModuleConfig) {
    val client: OpenAIClient  // null if disabled

    companion object {
        fun create(config: LlmModuleConfig): OpenRouterLlmClient
    }
}
```

Build rules:

```kotlin
val builder = OpenAIOkHttpClient.builder()
    .baseUrl(config.apiBaseUrl)
    .apiKey(config.apiKey)
    .timeout(Duration.ofSeconds(config.timeoutSeconds))

if (config.proxyEnabled) {
    builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(config.proxyHost, config.proxyPort)))
}
```

Same factory used from Paper and Velocity — **no duplicated `createClient`**.

## ModerationService (Paper)

Replaces `GPTEntity.getModerResponse` logic:

```kotlin
class ModerationService(
    private val llm: OpenRouterLlmClient,
    private val config: LlmModuleConfig,
) {
    fun moderate(text: String): CompletableFuture<Optional<ModerResult>>
}
```

- Runs on calling thread → use `CompletableFuture` + OkHttp async (already SDK)
- `GPTManager.moderationResponse` delegates here; **no Redis**
- Prompts: migrate from `gpt.yml` `ai.moderator.*` → `llm.yml` or `prompts/moderator.txt`

## ChatAgent (Velocity)

Replaces `Assistant` internals:

- Uses `LlmChatSession` + `ToolRegistry` (Jackson `@JsonClassDescription` on tool DTOs)
- Local tools (e.g. `LeaveForTime`) execute on Velocity
- Remote tools → `ToolRpcClient.send(request)`
- History / `leaveForTime` behavior preserved

## Tool RPC v2

**Request:**

```kotlin
data class ToolInvokeRequest(
    val id: UUID,
    val tool: String,
    val payload: JsonElement,
    val routing: ToolRouting,
    val timeoutMs: Long = 30_000,
)

sealed class ToolRouting {
    data object Broadcast : ToolRouting()
    data class ByPlayer(val playerName: String) : ToolRouting()
    data class TargetServer(val serverName: String) : ToolRouting()
}
```

**Response** (per server, same as today):

```kotlin
data class ToolInvokeResponse(
    val id: UUID,
    val serverName: String,
    val result: JsonElement?,
    val error: String? = null,
)
```

**Routing `BY_PLAYER`:** Velocity resolves server via `PlayerListAnnouncer` map; if offline → single error result to LLM.

**Paper `ToolRpcServer`:**

- Subscribe `arc.ai.tools.invoke`
- If routing is `TargetServer` / `ByPlayer` → ignore unless this server's `redis.server-name` matches
- Execute registered `ToolExecutor`, publish to `arc.ai.tools.result`

**v1 tools:**

| Tool | Server | Notes |
|------|--------|-------|
| `GetPlayerInfo` | Paper | port from existing |
| `GetBalTop` | Paper | port from existing |
| `GetInventory` | Paper | **new** — player online on this backend |
| `LeaveForTime` | Velocity | local only |

## Migration Plan

| Phase | Work |
|-------|------|
| **1** | `arc-core-ai` skeleton, `llm.yml`, `OpenRouterLlmClient`, config tests |
| **2** | `ModerationService`; wire `GPTManager` → deprecate `GPTEntity` HTTP |
| **3** | `ToolRpc` v2; migrate `ToolsMessager` / `ToolMessanger` |
| **4** | Velocity `ChatAgent`; deprecate duplicate `Assistant.createClient` |
| **5** | `GetInventory` tool; docs in AGENTS.md |
| **6** | Remove dead code; runtime `gpt.yml` bootstrap → `modules/llm.yml` |

## Testing

- `LlmModuleConfigTest` — proxy enabled/disabled validation
- `OpenRouterLlmClientTest` — mock transport / verify proxy flag passed (no real API calls in CI)
- `ToolRoutingTest` — BY_PLAYER resolution with fake player list
- `ModerationServiceTest` — parse OK/BAD markers
- `ToolRpcClientTest` — with TestRedis or mock pub/sub
- Paper integration: MockBukkit executor smoke test

No `@Ignore`. Kotest + MockK.

## Success Criteria

1. Board moderation works on Paper via `ModerationService` + OpenRouter through proxy when enabled
2. Proxy chat assistant works on Velocity with remote tools
3. Single `OpenRouterLlmClient` factory — zero duplicate HTTP client setup in plugins
4. `http-proxy.enabled: false` allows local dev without proxy
5. Placeholder IP in bundled yml; deploy docs note to replace `10.255.0.1`

## Related

- Existing code: `ProxyARC/ru/arc/ai/*`, `ARC/ru/arc/ai/*`
- [`AGENTS.md`](../../AGENTS.md) — update migration table after Phase 1
- [`docs/INDEX.md`](../INDEX.md) — link this spec
