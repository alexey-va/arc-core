# arc-core-ops Design

**Date:** 2026-06-26  
**Status:** Approved  
**Approach:** B (core-ops + reload registry + hybrid MCP connectivity)  
**Consumers:** ARC (Paper), ProxyARC (Velocity), mcserver MCP (Python)

## Goal

Extract ops HTTP infrastructure into **arc-core** so every game server (spawn, survival, velocity) runs its own local ops webserver. MCP (Python) talks HTTP + token to each server. Platform plugins (ARC, ProxyARC) register only platform-specific handlers. A standard capability contract returns **501** when an operation is not supported on that platform.

## Non-Goals

- Replacing `mc_push` / `mc_deploy` SSH/git workflow
- Generic `POST /ops/reload {target}` — each plugin has its own console command
- Public exposure of ops ports (bind stays `127.0.0.1`, nginx/stream for monitoring host only)

## Architecture

```
MCP (Python, mcserver/scripts/mcp-server/)
    → OpsClient per server (HTTP + Bearer token)
        ├─ spawn     127.0.0.1:25823  ARC + arc-core-ops-paper
        ├─ survival  127.0.0.1:25824  ARC + arc-core-ops-paper
        └─ velocity  127.0.0.1:25825  ProxyARC + arc-core-ops-velocity

arc-core-ops           HttpServer, auth, router, capabilities, log buffer
arc-core-ops-paper     console adapter, reload registry, item handlers
arc-core-ops-velocity  console adapter, reload registry, proxy online
```

**Connectivity (hybrid):**

- `arc_ops_*` / `mc_console` / `mc_reload` → HTTP via SSH curl (phase 1) or direct WireGuard (phase 2)
- `mc_push` / `mc_deploy` → unchanged (SSH/scp/git)

## Module Layout

```
arc-core/
├── arc-core-ops/
│   ru.arc.ops.core.*
│     OpsHttpServer          JDK HttpServer, bounded thread pool
│     OpsAuth                Bearer token from ops-http.yml
│     OpsRouter              method + path → handler; 404/501/401
│     OpsCapabilityRegistry  registered capability ids
│     OpsHttpConfig          bind, port, token, feature flags
│     OpsLogBuffer           ring buffer for GET /ops/errors
│     OpsConsolePort         interface: execute(command): OpsResult
│     ReloadPathResolver     path patterns → console commands
│     OpsJson                shared JSON helpers
│
├── arc-core-ops-paper/
│   PaperOpsModule           PluginModule wiring
│   PaperConsoleAdapter      sync to server thread, timeout
│   PaperReloadRegistry      port of reload-configs.sh rules
│   PaperItemHandlers        give, inventory, cmi-blob (from ru.arc.ops)
│   PaperOnlineHandler       backend player list
│
└── arc-core-ops-velocity/
    VelocityOpsModule
    VelocityConsoleAdapter
    VelocityReloadRegistry   proxyarc reload, …
    VelocityOnlineHandler    proxy player list
```

**Plugin wiring:**

- ARC: `OpsHttpModule` delegates to `PaperOpsModule.register(router)`
- ProxyARC: new `OpsHttpModule` → `VelocityOpsModule.register(router)`
- Legacy `ru.arc.ops.*` in ARC → thin deprecated layer, then removed in phase 4

## Standard HTTP Contract (v1)

All servers bind `127.0.0.1:<port>`. Auth: `Authorization: Bearer <token>`.

| Method | Path | Provided by | Notes |
|--------|------|-------------|-------|
| GET | `/ops/health` | core | 200 OK |
| GET | `/ops/info` | core | platform, version, serverName |
| GET | `/ops/capabilities` | core | list of registered capability ids |
| POST | `/ops/console` | platform | `{"command":"..."}` |
| POST | `/ops/reload-paths` | platform | `{"paths":["plugins/CMI/..."]}` → console commands |
| GET | `/ops/errors` | core + platform | recent WARN/ERROR from ops buffer |
| GET | `/ops/online` | platform | player names |
| POST | `/ops/broadcast` | platform (optional) | xserver message |
| POST | `/ops/player/{name}/give` | paper only | 501 on velocity |
| GET | `/ops/player/{name}/inventory` | paper only | 501 on velocity |
| POST | `/ops/item/*` | paper only | cmi-blob, preview, presets |

### Error Responses

**401 Unauthorized**

```json
{"error":"unauthorized"}
```

**501 Not Supported**

```json
{
  "error": "not_supported",
  "capability": "item.give",
  "platform": "velocity"
}
```

MCP tools must surface `capability` and `platform` in user-facing errors.

### Reload Strategy

No `POST /ops/reload {target: "arc"}`.

1. **Primary primitive:** `POST /ops/console`
2. **Convenience:** `POST /ops/reload-paths` — server resolves paths using platform `ReloadRegistry` (same rules as `mcserver/scripts/ops/reload-configs.sh`), dedupes commands, executes via console adapter.

Example Paper rules:

| Path pattern | Console command |
|--------------|-----------------|
| `plugins/ARC/*` | `arc reload` |
| `plugins/CMI/*` | `cmi reload` |
| `PlaceholderAPI/*` | `papi reload` |
| `plugins/Jobs/*` | `jobs reload` |
| `plugins/HuskHomes/*` | `huskhomes reload` |
| `plugins/AdvancedEnchantments/*` | `ae reload` |
| `DeluxeMenus/*` | `dm reload` |

Example Velocity rules:

| Path pattern | Console command |
|--------------|-----------------|
| `plugins/proxyarc/*` | `proxyarc reload` |

JAR updates / full restarts remain `mc arc`, `mc proxyarc`, `mc restart` — not ops HTTP.

## Configuration

**Per-server `modules/ops-http.yml`** (same schema on Paper and Velocity):

```yaml
enabled: true
bind: 127.0.0.1
port: 25823          # spawn 25823, survival 25824, velocity 25825
token: "<secret>"
console-enabled: true
reload-paths-enabled: true
items-read-enabled: true
items-give-enabled: false   # prod default
```

**MCP registry:** `mcserver/config/ops-servers.yml`

```yaml
servers:
  spawn:
    ssh_host: Gercena
    ops_url: http://127.0.0.1:25823
    token_env: ARC_OPS_TOKEN_SPAWN
    transport: ssh_curl
  survival:
    ssh_host: Gercena
    ops_url: http://127.0.0.1:25824
    token_env: ARC_OPS_TOKEN_SURVIVAL
  velocity:
    ssh_host: velocity
    ops_url: http://127.0.0.1:25825
    token_env: ARC_OPS_TOKEN_VELOCITY
```

Phase 2: optional `transport: direct` over WireGuard/nginx.

## MCP Python Changes

**New:** `mcserver/scripts/mcp-server/ops_client.py`

- `OpsClient.for_server("spawn")` — health, capabilities, console, reload-paths
- Pre-flight: check capability before tool call
- Timeouts: connect 5s, read 30s; one retry on timeout/502
- SSH ControlMaster reuse (existing in `common.sh` pattern)

**Refactor `server.py`:**

- `arc_ops_*` → `OpsClient` (no tmux for console)
- `mc_console` → `POST /ops/console` for spawn, survival, **velocity**
- `mc_reload` → `POST /ops/reload-paths`
- Keep `mc_push`, `mc_deploy` on SSH/git

**Stability fixes (retain + extend):**

- Tool thread pool ≥ 8 workers
- Per-tool hard timeouts
- No single-thread executor blocking all MCP calls
- Ops HTTP handlers must not block platform main thread > configured timeout (504 to MCP)

## Threading & Timeouts

| Layer | Policy |
|-------|--------|
| OpsHttpServer | Fixed thread pool (e.g. 8), separate from platform main thread |
| Paper console | `OpsConsolePort` schedules on main thread, future with 5–10s timeout |
| Velocity console | Velocity scheduler sync, same timeout |
| MCP curl | `MC_MCP_CURL_TIMEOUT` default 12s; console tools 30s |

## Testing

| Area | Approach |
|------|----------|
| ReloadPathResolver | Kotest unit, table-driven path → commands |
| OpsRouter / 501 | Unit without platform |
| PaperConsoleAdapter | MockBukkit |
| VelocityConsoleAdapter | Mock Velocity APIs or integration test |
| Ops HTTP integration | Existing `OpsHttpTest` pattern, port both modules |
| MCP ops_client | pytest with mocked HTTP responses |

## Migration Phases

| Phase | Deliverable |
|-------|-------------|
| **1** | `arc-core-ops` skeleton; migrate Paper handlers; MCP OpsClient for spawn/survival |
| **2** | `ReloadPathResolver` + HTTP `reload-paths`; `mc_reload`/`mc_console` via HTTP |
| **3** | `arc-core-ops-velocity`; ProxyARC ops on :25825; MCP velocity tools |
| **4** | Remove `ru.arc.ops` duplicate; bash reload → HTTP wrapper only |

## References

- Current Paper ops: `ARC/src/main/kotlin/ru/arc/ops/`
- MCP server: `mcserver/scripts/mcp-server/server.py`
- Reload bash mapping: `mcserver/scripts/ops/reload-configs.sh`
- arc-core framework: `docs/superpowers/specs/2026-06-24-arc-core-framework-design.md`
