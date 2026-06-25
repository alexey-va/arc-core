# Architecture Documentation & Agent Skills Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create canonical agent-oriented architecture docs across arc-core/ARC/ProxyARC/mcserver and add McFine-specific Cursor skills without duplicating content.

**Architecture:** Single canon in `arc-core/AGENTS.md`; thin deltas in plugin repos; ops hub unchanged except one link; CLAUDE.md stubbed; custom skills link to AGENTS.

**Tech Stack:** Markdown, Cursor skills (SKILL.md + YAML frontmatter), `.mdc` rules

**Spec:** `docs/superpowers/specs/2026-06-24-architecture-docs-design.md`

---

## Chunk 1: arc-core canon

### Task 1: Create `arc-core/AGENTS.md`

**Files:**
- Create: `arc-core/AGENTS.md`

- [ ] **Step 1: Write AGENTS.md** following spec outline (§ arc-core/AGENTS.md Outline):
  - Repository map table
  - Layer diagram (ASCII)
  - Boundary rules (5 bullets)
  - Decision tree (bullet list)
  - Module pattern (short code example)
  - Migration status table
  - Skills index (placeholder — filled in Chunk 3)
  - Links to `docs/INDEX.md`, mcserver paths

- [ ] **Step 2: Verify** all module names match `settings.gradle.kts`: arc-core, arc-core-logging, arc-core-redis, arc-core-paper, arc-core-velocity

- [ ] **Step 3: Commit** (arc-core repo)

```bash
cd ~/IdeaProjects/arc-core
git add AGENTS.md
git commit -m "docs: add architecture canon AGENTS.md for AI agents"
```

---

### Task 2: Create `arc-core/docs/INDEX.md`

**Files:**
- Create: `arc-core/docs/INDEX.md`

- [ ] **Step 1: List all specs/plans** with one-line description:

| File | Description |
|------|-------------|
| `superpowers/specs/2026-06-24-arc-core-framework-design.md` | Phase A/B/C roadmap |
| `superpowers/specs/2026-06-24-arc-core-redis-design.md` | Redis module extraction |
| `superpowers/specs/2026-06-24-arc-core-scheduling-design.md` | TaskScheduler unification |
| `superpowers/specs/2026-06-24-proxyarc-modules-design.md` | ProxyARC module layout |
| `superpowers/specs/2026-06-24-architecture-docs-design.md` | This documentation design |
| `superpowers/plans/2026-06-24-*.md` | Implementation plans (link each) |

- [ ] **Step 2: Add link** from `AGENTS.md` §Read first → `docs/INDEX.md`

- [ ] **Step 3: Commit**

```bash
git add docs/INDEX.md AGENTS.md
git commit -m "docs: add superpowers INDEX and link from AGENTS"
```

---

### Task 3: Update `arc-core/README.md`

**Files:**
- Modify: `arc-core/README.md`

- [ ] **Step 1: Add modules** arc-core-logging, arc-core-redis to table

- [ ] **Step 2: Replace** old `Tasks.scheduler = VelocityTaskScheduler` example with:

```kotlin
PaperArcRuntime.installScheduling(plugin)   // Paper
VelocityArcRuntime.installScheduling(server, plugin)  // Velocity
```

- [ ] **Step 3: Add** link: «Architecture for agents → [AGENTS.md](AGENTS.md)»

- [ ] **Step 4: Update** Phase A status checklist (config, modules, scheduling, redis, logging)

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: sync README with current modules and ArcRuntime API"
```

---

## Chunk 2: Plugin repo deltas

### Task 4: Create `ARC/AGENTS.md`

**Files:**
- Create: `ARC/AGENTS.md`

- [ ] **Step 1: Write ~25 lines:**
  - «Read [arc-core/AGENTS.md](../arc-core/AGENTS.md) first» (adjust path for composite/submodule)
  - Paper-only: `PaperArcRuntime.installScheduling(this)` in `ARC.kt` before modules
  - Stays in ARC: Event DSL, GuiDsl, Bukkit listeners, gameplay modules
  - Links: `GUI.md`, `COMMANDS.md`, `ops/AGENTS.md`, `mcserver/classic/plugins/ARC/AGENTS.md`
  - Build: `JAVA_HOME=.../temurin-25.jdk/Contents/Home ./gradlew test`

- [ ] **Step 2: Commit** (ARC repo)

```bash
cd ~/IdeaProjects/ARC
git add AGENTS.md
git commit -m "docs: add Paper plugin AGENTS.md delta"
```

---

### Task 5: Slim `ARC/CLAUDE.md`

**Files:**
- Modify: `ARC/CLAUDE.md`

- [ ] **Step 1: Replace body** with stub (~30 lines):
  - One paragraph project overview
  - «Full architecture → [AGENTS.md](AGENTS.md) and arc-core/AGENTS.md»
  - JAVA_HOME + `./gradlew test` one-liner
  - mcserver link table (3 rows: TASKS, deploy, runtime AGENTS)
  - «Patterns (Config get(), Kotest, DSLs) → arc-core/AGENTS.md»

- [ ] **Step 2: Verify** no duplicated sections remain (Architecture Overview, Testing Philosophy, etc. removed)

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: slim CLAUDE.md to stub pointing at AGENTS"
```

---

### Task 6: Update `ARC/README.md`

**Files:**
- Modify: `ARC/README.md`

- [ ] **Step 1: Fix** build instructions: Gradle not Maven, Java 25, shadowJar output path

- [ ] **Step 2: Add** arc-core composite build note + link AGENTS.md

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: update README for Gradle 25 and arc-core"
```

---

### Task 7: Create `ProxyARC/AGENTS.md`

**Files:**
- Create: `ProxyARC/AGENTS.md` (path: `~/mcserver/ProxyARC/` or `~/IdeaProjects/` if separate clone)

- [ ] **Step 1: Write ~20 lines:**
  - Link arc-core/AGENTS.md
  - `VelocityArcRuntime.installScheduling(server, plugin)`
  - Proxy modules list (from proxyarc-modules-design)
  - Build: `./gradlew build` → `ztarget/ProxyARC.jar`
  - Deploy: `./scripts/mc proxyarc` (mcserver)
  - Runtime configs: `mcserver/velocity/plugins/ProxyARC/`

- [ ] **Step 2: Commit** (ProxyARC repo)

---

### Task 8: Update `mcserver/AGENTS.md`

**Files:**
- Modify: `mcserver/AGENTS.md`

- [ ] **Step 1: Add row** to «Документация для агентов» table:

```markdown
| [`../IdeaProjects/arc-core/AGENTS.md`](../IdeaProjects/arc-core/AGENTS.md) или submodule | **Архитектура кода** — канон слоёв, границ, миграции |
```

(Use relative path that works from mcserver clone — if arc-core is not submodule, use GitHub link)

- [ ] **Step 2: Commit** (mcserver repo)

---

## Chunk 3: Skills & routing

### Task 9: Create custom skills

**Files:**
- Create: `mcserver/.cursor/skills/arc-deploy/SKILL.md`
- Create: `mcserver/.cursor/skills/arc-new-module/SKILL.md`
- Create: `mcserver/.cursor/skills/arc-migrate-to-core/SKILL.md`
- Create: `arc-core/.cursor/skills/arc-kotest-mockk/SKILL.md`

- [ ] **Step 1: arc-deploy** — golden rules:
  - MCP `user-mcserver` first (arc_ops_*, mc_deploy)
  - `./scripts/mc arc` for JAR, `./scripts/mc deploy` for YAML
  - Logs via Grafana Loki MCP, not SSH grep
  - Link: mcserver/AGENTS.md, mcserver/scripts/mcp-server/README.md

- [ ] **Step 2: arc-new-module** — checklist:
  - Package under `ru/arc/{feature}/`
  - `*ModuleConfig` + `modules/{feature}.yml`
  - `PluginModule` + register in ModuleRegistry
  - Kotest test with `Test*Config`
  - Link: arc-core/AGENTS.md §Module pattern

- [ ] **Step 3: arc-migrate-to-core** — decision tree from AGENTS (copy as checklist, not prose)

- [ ] **Step 4: arc-kotest-mockk** — FreeSpec, MockK, TestTaskScheduler; forbid JUnit `@Test`, Mockito

- [ ] **Step 5: Update** arc-core/AGENTS.md Skills index with all four + mcfine-cmi-kits

- [ ] **Step 6: Commit** (mcserver + arc-core)

---

### Task 10: Vendored skill (optional)

**Files:**
- Create: `arc-core/.cursor/skills/skill-creator/SKILL.md` (copy from anthropics/skills)

- [ ] **Step 1: Fetch** from `https://raw.githubusercontent.com/anthropics/skills/main/skills/skill-creator/SKILL.md`

- [ ] **Step 2: Add** header comment: «Upstream: anthropics/skills — do not edit; update by re-fetch»

- [ ] **Step 3: Create wrapper** `arc-core/.cursor/skills/kotlin-coroutines-arc/SKILL.md`:
  - `description`: Kotlin coroutines for ARC; use with arc-core scheduling rules
  - Body: link kotlin-coroutines-expert concepts + **hard rule**: never call Bukkit API from Dispatchers.IO/Default; use Tasks.scheduler for main thread

- [ ] **Step 4: Commit** (optional chunk — skip if user prefers Remote Rule in Cursor UI instead)

---

### Task 11: Cursor routing rule

**Files:**
- Create: `ARC/.cursor/rules/architecture-pointer.mdc`

- [ ] **Step 1: Write** per spec §Cursor Workspace Routing (`alwaysApply: true`)

- [ ] **Step 2: Commit** (ARC repo)

---

## Chunk 4: Verification

### Task 12: Consistency check

- [ ] **Step 1: Grep** for duplicated boundary phrases across CLAUDE.md, AGENTS.md, rule1.mdc — ensure single canon

```bash
rg -l "BukkitTaskScheduler" ~/IdeaProjects/ARC/CLAUDE.md  # should be empty or link-only
rg "PaperArcRuntime" ~/IdeaProjects/arc-core/AGENTS.md ~/IdeaProjects/ARC/AGENTS.md
```

- [ ] **Step 2: Validate** each SKILL.md has `name` matching folder and non-empty `description`

- [ ] **Step 3: Open** arc-core/AGENTS.md in agent context — confirm readable in <2 min

- [ ] **Step 4: Final commits** per repo; push when user requests

---

## Execution Order

1. Chunk 1 (arc-core) — unblocks everything
2. Chunk 2 (ARC, ProxyARC, mcserver) — parallelizable after Chunk 1
3. Chunk 3 (skills) — after AGENTS.md exists
4. Chunk 4 (verify)

## Estimated effort

~2–3 hours total; no code changes, no tests required (docs-only).
