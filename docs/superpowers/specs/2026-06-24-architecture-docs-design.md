# Architecture Documentation & Agent Skills Design

**Date:** 2026-06-24  
**Status:** Approved  
**Audience:** AI agents (Cursor, Claude Code) — primary; human developer — secondary  
**Repos:** arc-core, ARC, ProxyARC, mcserver

## Goal

Establish a **single canonical architecture vision** for the McFine plugin stack, expressed as short agent-oriented MD files. Minimize duplication across CLAUDE.md, cursor rules, and superpowers specs. Add curated **skills** for multi-step workflows; domain skills written locally, generic skills vendored from high-quality upstream sources.

**Non-goals:** Replacing feature-specific docs (`GUI.md`, `COMMANDS.md`, runtime plugin AGENTS); migrating code; publishing docs to a website.

## Decision Summary

| Choice | Decision |
|--------|----------|
| Doc strategy | **A + C hybrid:** one canon in `arc-core/AGENTS.md`; ops hub in `mcserver/AGENTS.md`; thin deltas in ARC/ProxyARC |
| Audience | AI agents first — short files, tables, links, decision trees |
| DRY rule | Principles once in canon; local files = delta + links only |
| CLAUDE.md | Stub (~30 lines) pointing to AGENTS.md |
| superpowers specs | Historical migration record; indexed via `docs/INDEX.md`, not duplicated |
| Skills | Cherry-pick from upstream; write McFine-specific skills locally; skills link to AGENTS, never duplicate canon |

## Repository Map

```
┌─────────────────────────────────────────────────────────────────┐
│  mcserver          Ops, deploy, runtime YAML, MCP recipes       │
│  (AGENTS.md)       Entry for prod/config work                   │
└────────────────────────────┬────────────────────────────────────┘
                             │ deploy / configs
┌────────────────────────────▼────────────────────────────────────┐
│  arc-core          Platform-agnostic framework (CANON)          │
│  (AGENTS.md)       Config, modules, tasks, redis, logging       │
└──────────────┬─────────────────────────────┬────────────────────┘
               │                             │
    ┌──────────▼──────────┐       ┌──────────▼──────────┐
    │  ARC (Paper)        │       │  ProxyARC (Velocity) │
    │  AGENTS.md = delta  │       │  AGENTS.md = delta   │
    └─────────────────────┘       └─────────────────────┘
```

| Repo | Role | Agent entry |
|------|------|-------------|
| `arc-core` | Shared Kotlin framework | **`AGENTS.md`** — architecture canon |
| `ARC` | Paper gameplay plugin | `AGENTS.md` — Paper bootstrap, what stays in plugin |
| `ProxyARC` | Velocity proxy plugin | `AGENTS.md` — Velocity bootstrap, proxy modules |
| `mcserver` | Runtime config mirror | `AGENTS.md` + `TASKS.md` — deploy, server roles |

## File Hierarchy

### Created / rewritten

| File | ~Lines | Purpose |
|------|--------|---------|
| `arc-core/AGENTS.md` | 100 | **Canon:** layers, boundaries, decision tree, migration status, skills index |
| `arc-core/docs/INDEX.md` | 40 | Links to all superpowers specs/plans |
| `ARC/AGENTS.md` | 25 | Paper delta: `PaperArcRuntime`, Event DSL, GUI, links |
| `ProxyARC/AGENTS.md` | 20 | Velocity delta: `VelocityArcRuntime`, proxy modules |
| `mcserver/AGENTS.md` | +3 | Row in doc table → `arc-core/AGENTS.md` |

### Slimmed

| File | Action |
|------|--------|
| `ARC/CLAUDE.md` | Reduce to stub: link AGENTS + JAVA_HOME + mcserver pointer |
| `arc-core/README.md` | Sync modules (redis, logging, scheduling), `*ArcRuntime` wiring |
| `ARC/README.md` | Gradle/Java 25, composite build, link AGENTS |

### Unchanged (linked from canon)

- `ARC/src/main/kotlin/ru/arc/gui/GUI.md`
- `ARC/src/main/kotlin/ru/arc/commands/arc/COMMANDS.md`
- `ARC/src/main/kotlin/ru/arc/ops/AGENTS.md`
- `mcserver/classic/plugins/ARC/AGENTS.md` (runtime)
- `mcserver/.cursor/skills/mcfine-cmi-kits/SKILL.md`
- `arc-core/docs/superpowers/specs/*.md`

## `arc-core/AGENTS.md` Outline

1. **Read first** — one-line purpose, link to `docs/INDEX.md`
2. **Repository map** — table above
3. **Layer diagram** — `arc-core` → `arc-core-{paper,velocity,logging,redis}` → plugins
4. **Boundary rules** (non-negotiable):
   - No Bukkit/Velocity imports in `arc-core` feature logic
   - Feature code uses `Tasks.*` / `TaskScheduler` — never `BukkitTaskScheduler` / `VelocityTaskScheduler`
   - Event DSL stays in ARC plugin only
   - Config: `get()` accessor pattern + `Test*Config(EmptyConfig)`
   - Tests: Kotest + MockK; no `@Ignore`
5. **Decision tree** — «куда класть новый код?»
   - Shared + platform-agnostic → `arc-core`
   - Paper-only API → `arc-core-paper` or ARC with paper dep
   - Gameplay feature → `ARC/src/.../ru/arc/{feature}/`
   - Proxy feature → `ProxyARC/...`
   - Runtime YAML → `mcserver/*/plugins/ARC/modules/`
6. **Module pattern** — `PluginModule`, `*ModuleConfig`, bundled `modules/*.yml`, bootstrap
7. **Migration status** — Phase A/B/C checklist table (see below)
8. **Skills index** — where skills live, when to invoke
9. **Related docs** — mcserver TASKS, runtime AGENTS, GUI.md, ops AGENTS

## Migration Status Table (in AGENTS.md)

| Component | Status | Spec |
|-----------|--------|------|
| Config (SnakeYAML Engine) | ✅ arc-core | framework-design |
| PluginModule + ModuleRegistry | ✅ arc-core | framework-design |
| TaskScheduler + TaskDsl | ✅ arc-core | scheduling-design |
| Logging (Loki, LogContext) | ✅ arc-core-logging | — |
| Redis | ✅ arc-core-redis | redis-design |
| Paper runtime wiring | ✅ arc-core-paper | scheduling-design |
| Velocity runtime wiring | ✅ arc-core-velocity | proxyarc-modules-design |
| Event DSL | ❌ stays in ARC | framework-design (non-goal) |
| CachedRepository / xserver | ⏳ Phase B | framework-design |
| PlayerProvider / domain events | ⏳ Phase C | framework-design |

## Rules vs Skills vs AGENTS

| Layer | Loaded | Content | Example |
|-------|--------|---------|---------|
| `.cursor/rules/*.mdc` | Always | 1–2 screens, non-negotiable | Kotlin-first, MCP-first |
| `AGENTS.md` | Repo context | Architecture + repo delta | arc-core canon |
| `.cursor/skills/*/SKILL.md` | On trigger | Multi-step workflow + link to AGENTS | arc-deploy |
| `docs/superpowers/specs/` | On demand | Migration design history | redis-design |

**Anti-pattern:** Copying boundary rules into skills or CLAUDE.md — always link to `arc-core/AGENTS.md §Boundary rules`.

## Skills Strategy

### Already installed (do not duplicate)

| Source | Skills | Location |
|--------|--------|----------|
| obra/superpowers | brainstorming, TDD, verification-before-completion, writing-plans | Claude plugin |
| mcserver | mcfine-cmi-kits | `.cursor/skills/mcfine-cmi-kits/` |
| ~/.claude/skills | kotlin-dev, kotlin-dev-rules | Global |

### Vendored from upstream (thin install)

| Source | Skill | Why | Install |
|--------|-------|-----|---------|
| anthropics/skills ⭐155k | `skill-creator` | Official skill authoring | Remote rule or copy to `arc-core/.cursor/skills/` |
| sickn33/antigravity ⭐40k | `kotlin-coroutines-expert` | Coroutines patterns | Wrapper skill + «never Bukkit from async» |
| anthropics/skills | `mcp-builder` | Extend mcserver MCP | Optional, on demand |

### Rejected as-is

| Skill | Reason |
|-------|--------|
| `minecraft-bukkit-pro` (antigravity) | Java/Google Style, MockBukkit — conflicts with Kotlin/Kotest/arc-core boundaries |
| antigravity full bundle (~1600) | Context noise, poor routing |
| cursor-handbook (208 components) | Overkill; borrow setup-agent idea only |

### Custom skills to write (McFine domain)

Pattern: follow `mcfine-cmi-kits` — golden rules, links to AGENTS/TASKS, no prose duplication.

```
mcserver/.cursor/skills/
├── mcfine-cmi-kits/       ← exists
├── arc-deploy/            MCP mc_deploy, arc_ops_*, Loki; never SSH for ops
├── arc-new-module/        Scaffold PluginModule + *ModuleConfig + Kotest test
└── arc-migrate-to-core/   Decision tree from arc-core/AGENTS.md

arc-core/.cursor/skills/
└── arc-kotest-mockk/      Kotest + MockK + TestTaskScheduler; no JUnit/Mockito
```

Each custom skill frontmatter:

```yaml
---
name: arc-deploy
description: Deploy ARC/ProxyARC or configs to McFine via MCP and ./scripts/mc.
  Use when user asks deploy, push to prod, mc arc, mc deploy, or verify on server.
---
```

Body: ≤60 lines, links to `mcserver/AGENTS.md` and `arc-core/AGENTS.md`.

## Cursor Workspace Routing

Add to `ARC/.cursor/rules/` (new file `architecture-pointer.mdc`):

```markdown
---
description: Route agents to architecture canon before structural changes
alwaysApply: true
---

Before moving code between repos or adding framework APIs, read:
- Architecture canon: arc-core/AGENTS.md (composite build path or ~/IdeaProjects/arc-core)
- Ops/deploy: mcserver/AGENTS.md + TASKS.md
```

## Success Criteria

1. Agent opening any repo finds architecture within one hop (AGENTS.md → arc-core canon)
2. `ARC/CLAUDE.md` ≤ 40 lines; no duplicated architecture/testing sections
3. `arc-core/README.md` matches actual Gradle modules and bootstrap API
4. Four custom McFine skills exist with valid frontmatter and AGENTS links
5. `docs/INDEX.md` lists all superpowers specs with one-line descriptions
6. No contradiction between AGENTS canon and `.cursor/rules/rule1.mdc` (rules stay always-on; AGENTS adds repo-specific detail)

## Non-Goals

- Auto-generating AGENTS.md per Kotlin package (YAGNI until a module exceeds ~500 lines)
- Replacing obra/superpowers workflow skills
- Public documentation site
- Committing vendored antigravity skills wholesale
