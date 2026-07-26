# arc-core-ops Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shared ops HTTP in arc-core with Paper/Velocity handlers, standard capabilities + 501, MCP Python client per server including velocity.

**Architecture:** `arc-core-ops` provides HttpServer/auth/router; `arc-core-ops-paper` and `arc-core-ops-velocity` register platform handlers; MCP uses `OpsClient` over HTTP instead of tmux for console/reload.

**Tech Stack:** Kotlin 25, JDK HttpServer, Kotest, MockBukkit, Python FastMCP, Gradle composite build.

**Spec:** `docs/superpowers/specs/2026-06-26-arc-core-ops-design.md`

---

## Chunk 1: arc-core-ops skeleton

### Task 1: Gradle modules

**Files:**
- Create: `arc-core-ops/build.gradle.kts`
- Create: `arc-core-ops-paper/build.gradle.kts`
- Create: `arc-core-ops-velocity/build.gradle.kts`
- Modify: `arc-core/settings.gradle.kts`
- Modify: `arc-core/build.gradle.kts` (testAll includes new modules)

- [ ] **Step 1:** Add three subprojects to `settings.gradle.kts`
- [ ] **Step 2:** `arc-core-ops` — no platform deps; slf4j, gson or existing JSON util
- [ ] **Step 3:** `arc-core-ops-paper` — compileOnly paper-api, depends on arc-core-ops
- [ ] **Step 4:** `arc-core-ops-velocity` — compileOnly velocity-api, depends on arc-core-ops
- [ ] **Step 5:** Run `./gradlew :arc-core-ops:test` — empty pass

### Task 2: OpsRouter + OpsAuth + capabilities

**Files:**
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsRouter.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsAuth.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsCapability.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsHttpConfig.kt`
- Create: `arc-core-ops/src/test/kotlin/ru/arc/ops/core/OpsRouterTest.kt`

- [ ] **Step 1:** Write failing test — unregistered route → 404; missing auth → 401
- [ ] **Step 2:** Implement OpsAuth bearer check
- [ ] **Step 3:** Implement OpsRouter register/handle; 501 helper `notSupported(capability, platform)`
- [ ] **Step 4:** Register core routes: health, info, capabilities
- [ ] **Step 5:** Run `./gradlew :arc-core-ops:test` — PASS

### Task 3: OpsHttpServer lifecycle

**Files:**
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsHttpServer.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsLogBuffer.kt`
- Create: `arc-core-ops/src/test/kotlin/ru/arc/ops/core/OpsHttpServerTest.kt`

- [ ] **Step 1:** Write test — start server on random port, GET /ops/health
- [ ] **Step 2:** Implement HttpServer bind, thread pool, graceful stop
- [ ] **Step 3:** Wire OpsLogBuffer for /ops/errors
- [ ] **Step 4:** Run tests — PASS

### Task 4: OpsConsolePort interface

**Files:**
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsConsolePort.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/OpsResult.kt`
- Modify: `OpsRouter.kt` — POST /ops/console delegates to injected port

- [ ] **Step 1:** Define `OpsConsolePort.execute(command): OpsResult`
- [ ] **Step 2:** Test with fake port implementation
- [ ] **Step 3:** Register capability `console.execute`

---

## Chunk 2: Reload registry + Paper port

### Task 5: ReloadPathResolver

**Files:**
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/ReloadPathResolver.kt`
- Create: `arc-core-ops/src/main/kotlin/ru/arc/ops/core/ReloadRule.kt`
- Create: `arc-core-ops/src/test/kotlin/ru/arc/ops/core/ReloadPathResolverTest.kt`

- [ ] **Step 1:** Write tests mirroring `reload-configs.sh` rules (arc, cmi, papi, jobs, huskhomes, ae, dm)
- [ ] **Step 2:** Implement pattern matching + command dedupe
- [ ] **Step 3:** POST /ops/reload-paths handler in router (requires OpsConsolePort)
- [ ] **Step 4:** Run tests — PASS

### Task 6: PaperConsoleAdapter

**Files:**
- Create: `arc-core-ops-paper/src/main/kotlin/ru/arc/ops/paper/PaperConsoleAdapter.kt`
- Create: `arc-core-ops-paper/src/main/kotlin/ru/arc/ops/paper/PaperReloadRegistry.kt`
- Create: `arc-core-ops-paper/src/test/kotlin/ru/arc/ops/paper/PaperConsoleAdapterTest.kt`

- [ ] **Step 1:** MockBukkit test — execute `say test` on main thread
- [ ] **Step 2:** Implement adapter with timeout (5–10s)
- [ ] **Step 3:** PaperReloadRegistry registers default rules from spec

### Task 7: Migrate Paper item handlers

**Files:**
- Create: `arc-core-ops-paper/src/main/kotlin/ru/arc/ops/paper/PaperOpsModule.kt`
- Create: `arc-core-ops-paper/src/main/kotlin/ru/arc/ops/paper/PaperItemHandlers.kt` (move from ARC)
- Modify: `ARC/build.gradle.kts` — depend on arc-core-ops-paper
- Modify: `ARC/src/main/kotlin/ru/arc/ops/OpsHttpModule.kt` — delegate to PaperOpsModule

- [ ] **Step 1:** Copy/adapt `OpsItemHandlers`, `OpsCmiKitHandlers`, `ItemPresets` into paper module
- [ ] **Step 2:** Port existing `OpsHttpTest` to run against PaperOpsModule
- [ ] **Step 3:** Switch ARC to the new module and delete the superseded implementation
- [ ] **Step 4:** `./gradlew test` in ARC — PASS

---

## Chunk 3: MCP OpsClient (Paper)

### Task 8: ops-servers.yml + OpsClient

**Files:**
- Create: `mcserver/config/ops-servers.yml`
- Create: `mcserver/scripts/mcp-server/ops_client.py`
- Create: `mcserver/scripts/mcp-server/test_ops_client.py`
- Modify: `mcserver/scripts/mcp-server/server.py`

- [ ] **Step 1:** pytest — mock HTTP, capabilities check, 501 parsing
- [ ] **Step 2:** Implement OpsClient (health, console, reload-paths, capabilities)
- [ ] **Step 3:** Wire spawn/survival tokens from env
- [ ] **Step 4:** Replace `_arc_ops_curl` console path with OpsClient where applicable

### Task 9: mc_console + mc_reload via HTTP

**Files:**
- Modify: `mcserver/scripts/mcp-server/server.py` — `mc_console`, `mc_reload`
- Modify: `mcserver/scripts/mcp-server/README.md`

- [ ] **Step 1:** `mc_console(server)` → POST /ops/console
- [ ] **Step 2:** `mc_reload(server, paths)` → POST /ops/reload-paths
- [ ] **Step 3:** Fallback tmux only if ops health check fails (log warning)
- [ ] **Step 4:** Manual test: `proxyarc reload` equivalent on paper via `arc reload`

---

## Chunk 4: Velocity ops + MCP

### Task 10: VelocityOpsModule

**Files:**
- Create: `arc-core-ops-velocity/src/main/kotlin/ru/arc/ops/velocity/VelocityOpsModule.kt`
- Create: `arc-core-ops-velocity/src/main/kotlin/ru/arc/ops/velocity/VelocityConsoleAdapter.kt`
- Create: `arc-core-ops-velocity/src/main/kotlin/ru/arc/ops/velocity/VelocityReloadRegistry.kt`
- Create: `arc-core-ops-velocity/src/main/kotlin/ru/arc/ops/velocity/VelocityOnlineHandler.kt`
- Modify: `ProxyARC/build.gradle.kts`, `Velocity.kt`, `InfrastructureModules.kt`

- [ ] **Step 1:** Wire OpsHttpModule on init (port 25825 default)
- [ ] **Step 2:** Register console, reload-paths, online; item routes → 501
- [ ] **Step 3:** Add `velocity/plugins/proxyarc/modules/ops-http.yml` to mcserver mirror
- [ ] **Step 4:** Deploy + verify GET /ops/health on `velocity`

### Task 11: MCP velocity tools

**Files:**
- Modify: `mcserver/scripts/mcp-server/server.py` — ServerName enum + velocity
- Modify: `mcserver/config/ops-servers.yml`
- Modify: `mcserver/scripts/mcp-server/README.md`

- [ ] **Step 1:** Add velocity to OpsClient registry
- [ ] **Step 2:** `mc_console(server="velocity", command="proxyarc reload")` works
- [ ] **Step 3:** `arc_ops_*` that need paper return clear 501 message for velocity
- [ ] **Step 4:** Update `.cursor/mcp.json` env if separate velocity token

---

## Chunk 5: Cleanup

### Task 12: Deprecate ru.arc.ops + bash wrapper

**Files:**
- Delete/Migrate: `ARC/src/main/kotlin/ru/arc/ops/*` (after parity)
- Modify: `mcserver/scripts/ops/reload-configs.sh` — call OpsClient HTTP first

- [ ] **Step 1:** Verify all MCP tools use HTTP on Gercena + `velocity`
- [ ] **Step 2:** reload-configs.sh → curl localhost reload-paths
- [ ] **Step 3:** Update AGENTS.md in ARC, ProxyARC, mcserver MCP README
- [ ] **Step 4:** Remove tmux console paths from MCP (keep emergency fallback flag)

---

## Verification Checklist

- [ ] `./gradlew testAll` in arc-core
- [ ] `./gradlew test` in ARC (OpsHttpTest)
- [ ] `./gradlew build` ProxyARC with velocity ops
- [ ] `pytest mcserver/scripts/mcp-server/test_ops_client.py`
- [ ] Manual: MCP `mc_console velocity -- proxyarc reload`
- [ ] Manual: MCP `arc_ops_give_preset` on velocity → readable 501
- [ ] Loki: no ops handler blocking main thread errors

---

## Commit Strategy

One commit per task (or per chunk). Suggested first commit after Task 3: `feat(arc-core-ops): skeleton router auth health`.

**Plan complete.** Ready to execute Phase 1 (Chunk 1–2)?
