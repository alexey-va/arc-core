# arc-core-ai Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** Shared OpenRouter LLM client + tool RPC in `arc-core-ai`; Paper keeps moderation; Velocity runs chat agent with remote tools.

**Architecture:** New `arc-core-ai` module; mandatory optional HTTP proxy (`enabled` flag); dual LLM hosts; Redis tool v2.

**Spec:** `docs/superpowers/specs/2026-06-24-arc-core-ai-design.md`

---

## Chunk 1: arc-core-ai skeleton

### Task 1: Gradle module

**Files:**
- Modify: `arc-core/settings.gradle.kts`
- Create: `arc-core-ai/build.gradle.kts`
- Create: `arc-core-ai/src/main/resources/modules/llm.yml`

- [ ] **Step 1:** Add `include("arc-core-ai")` to settings
- [ ] **Step 2:** `build.gradle.kts` — deps: `arc-core`, `arc-core-redis`, `openai-java`, Gson; `assertKotlinOnly`
- [ ] **Step 3:** Create `llm.yml` with placeholder `http-proxy.host: "10.255.0.1"`, `enabled: true`
- [ ] **Step 4:** `./gradlew :arc-core-ai:compileKotlin`
- [ ] **Step 5:** Commit `feat(ai): add arc-core-ai Gradle module`

### Task 2: LlmModuleConfig

**Files:**
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/config/LlmModuleConfig.kt`
- Create: `arc-core-ai/src/test/kotlin/ru/arc/ai/config/LlmModuleConfigTest.kt`

- [ ] **Step 1:** Implement get() accessors: `apiBaseUrl`, `apiKey`, `proxyEnabled`, `proxyHost`, `proxyPort`, moderation markers
- [ ] **Step 2:** Validation: if proxy enabled → host non-blank; api-key `"none"` → `enabled = false` flag on config
- [ ] **Step 3:** Tests: proxy on/off, missing host throws
- [ ] **Step 4:** `./gradlew :arc-core-ai:test`
- [ ] **Step 5:** Commit

### Task 3: OpenRouterLlmClient

**Files:**
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/llm/OpenRouterLlmClient.kt`
- Create: `arc-core-ai/src/test/kotlin/ru/arc/ai/llm/OpenRouterLlmClientTest.kt`

- [ ] **Step 1:** Factory builds OpenAI SDK client; applies HTTP proxy only when `proxyEnabled`
- [ ] **Step 2:** Returns null/disabled wrapper when api-key is `"none"`
- [ ] **Step 3:** Test with mock or spy that proxy builder called when enabled
- [ ] **Step 4:** Commit

---

## Chunk 2: Moderation on Paper

### Task 4: ModerationService

**Files:**
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/llm/ModerationService.kt`
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/llm/ModerResult.kt`
- Create: `arc-core-ai/src/test/kotlin/ru/arc/ai/llm/ModerationServiceTest.kt`

- [ ] **Step 1:** Port marker parsing from `GPTEntity.getModerResponse`
- [ ] **Step 2:** `moderate(text): CompletableFuture<Optional<ModerResult>>`
- [ ] **Step 3:** Unit test marker parsing without network
- [ ] **Step 4:** Commit

### Task 5: Wire ARC Paper

**Files:**
- Modify: `ARC/build.gradle.kts` — add `arc-core-ai`
- Modify: `ARC/src/main/kotlin/ru/arc/ai/GPTManager.kt`
- Modify: `ARC/src/main/kotlin/ru/arc/core/modules/` — register AI module or init in existing

- [ ] **Step 1:** Load `LlmModuleConfig` from `modules/llm.yml`
- [ ] **Step 2:** `GPTManager.moderationResponse` → `ModerationService`
- [ ] **Step 3:** Mark `GPTEntity.getModerResponse` `@Deprecated`
- [ ] **Step 4:** `./gradlew test --tests "*Moderation*"` / board tests if any
- [ ] **Step 5:** Commit ARC

### Task 6: Runtime llm.yml bootstrap

**Files:**
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/config/LlmConfigBootstrap.kt`
- Modify: mcserver runtime (at deploy): `classic/plugins/ARC/modules/llm.yml`

- [ ] **Step 1:** Bootstrap copies api-key from legacy `gpt.yml` if present
- [ ] **Step 2:** Document in spec: replace `10.255.0.1` at deploy
- [ ] **Step 3:** Commit

---

## Chunk 3: Tool RPC v2

### Task 7: Tool types + client

**Files:**
- Create: `arc-core-ai/src/main/kotlin/ru/arc/ai/tools/*.kt`

- [ ] **Step 1:** `ToolRouting`, `ToolInvokeRequest`, `ToolInvokeResponse`, `ToolExecutor` SPI
- [ ] **Step 2:** `ToolRpcClient` — refactor from `ToolsMessager` (CompletableFuture, timeout)
- [ ] **Step 3:** Kotest tests with mock Redis publisher
- [ ] **Step 4:** Commit

### Task 8: Paper ToolRpcServer

**Files:**
- Create: `ARC/src/main/kotlin/ru/arc/ai/tools/PaperToolExecutors.kt`
- Modify: `ARC/src/main/kotlin/ru/arc/ai/assistant/ToolMessanger.kt` → delegate to server
- Modify: `ARC/src/main/kotlin/ru/arc/network/NetworkRegistry.kt`

- [ ] **Step 1:** Port `GetBalTop`, `GetPlayerInfo` to `ToolExecutor` registrations
- [ ] **Step 2:** Server filter by `redis.server-name` + routing
- [ ] **Step 3:** Remove duplicate tool classes when stable
- [ ] **Step 4:** Commit

### Task 9: GetInventory tool

**Files:**
- Create: `ARC/src/main/kotlin/ru/arc/ai/tools/GetInventoryExecutor.kt`

- [ ] **Step 1:** Tool DTO with `playerName`; main-thread inventory read
- [ ] **Step 2:** Register on Paper; expose schema to Velocity `ToolRegistry`
- [ ] **Step 3:** Test with MockBukkit
- [ ] **Step 4:** Commit

---

## Chunk 4: Velocity ChatAgent

### Task 10: Migrate ProxyARC Assistant

**Files:**
- Modify: `ProxyARC/build.gradle.kts`
- Modify: `ProxyARC/src/main/kotlin/ru/arc/ai/Assistant.kt`
- Modify: `ProxyARC/src/main/kotlin/ru/arc/ai/tools/ToolsMessager.kt` → use `ToolRpcClient`

- [ ] **Step 1:** Add arc-core-ai dependency
- [ ] **Step 2:** Replace `createClient` with `OpenRouterLlmClient`
- [ ] **Step 3:** Wire `ToolRpcClient` for remote tools; `BY_PLAYER` via `PlayerListAnnouncer`
- [ ] **Step 4:** `./gradlew test` ProxyARC + arc-core
- [ ] **Step 5:** Commit

### Task 11: Config merge assistant.yml → llm.yml

**Files:**
- Modify: `ProxyARC/src/main/resources/modules/assistant.yml`
- Modify: velocity runtime configs

- [ ] **Step 1:** Remove duplicate api-key/proxy from assistant.yml (prompts only)
- [ ] **Step 2:** Add `velocity/plugins/ProxyARC/modules/llm.yml` at deploy with real proxy IP
- [ ] **Step 3:** Commit

---

## Chunk 5: Docs & cleanup

### Task 12: AGENTS.md + INDEX

**Files:**
- Modify: `arc-core/AGENTS.md` — migration row for arc-core-ai
- Modify: `arc-core/docs/INDEX.md`

- [ ] **Step 1:** Add arc-core-ai to module table and migration status
- [ ] **Step 2:** Commit

### Task 13: Deprecation cleanup (after prod verify)

- [ ] Remove `GPTEntity` HTTP path when moderation stable
- [ ] Remove old channel names if aliased
- [ ] Commit

---

## Deploy checklist (user provides IP later)

```yaml
# classic/plugins/ARC/modules/llm.yml + velocity + survival
http-proxy:
  enabled: true
  host: "<REAL_TINYPROXY_IP>"
  port: 8888
openrouter:
  api-key: "<REAL_KEY>"
```

Local dev:

```yaml
http-proxy:
  enabled: false
```

---

## Execution order

1. Chunk 1 (module + client)
2. Chunk 2 (moderation — highest user value)
3. Chunk 3 (tools)
4. Chunk 4 (Velocity agent)
5. Chunk 5 (docs)
