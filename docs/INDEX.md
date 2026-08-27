# Superpowers docs index

Current shared API contracts plus historical design specs and implementation
plans. **Do not duplicate** — link from `AGENTS.md`.

## Current contracts

| File | Description |
|------|-------------|
| [shared-primitives.md](shared-primitives.md) | Agent-oriented routing index for reusable plugin lifecycle, one-time-use, persistence, locale, Redis, network, teleport, and player-state mechanisms |
| [paper-testing.md](paper-testing.md) | Canonical MockBukkit test-kit, test-layer selection, lifecycle, examples, and limitations |
| [integration-testing.md](integration-testing.md) | Canonical disposable Redis/MySQL Testcontainers services and consumer pattern |
| [redis-networking.md](redis-networking.md) | Validated Redis topic, bounded request/reply, presence leases, ownership rules, and test contract |
| [agentic-plugin-reference.md](agentic-plugin-reference.md) | Short composition path for lifecycle, health, recovery, MockBukkit, and storage tests |
| [new-plugin-contract.md](new-plugin-contract.md) | Executable capability manifest, required module matrix, and pinned CI verifier for new sibling plugins |

## Specs

| File | Description |
|------|-------------|
| [2026-06-24-arc-core-framework-design.md](superpowers/specs/2026-06-24-arc-core-framework-design.md) | Phase A/B/C roadmap, module layout, config strategy |
| [2026-06-24-arc-core-redis-design.md](superpowers/specs/2026-06-24-arc-core-redis-design.md) | Redis module extraction |
| [arc-core-sql.md](arc-core-sql.md) | Shared optional MySQL/Hikari runtime and migration contract |
| [2026-06-24-arc-core-scheduling-design.md](superpowers/specs/2026-06-24-arc-core-scheduling-design.md) | TaskScheduler, Tasks, subtick, Paper/Velocity wiring |
| [2026-06-24-proxyarc-modules-design.md](superpowers/specs/2026-06-24-proxyarc-modules-design.md) | ProxyARC module layout on arc-core |
| [2026-06-24-architecture-docs-design.md](superpowers/specs/2026-06-24-architecture-docs-design.md) | Agent-oriented documentation and skills |
| [2026-06-24-arc-core-ai-design.md](superpowers/specs/2026-06-24-arc-core-ai-design.md) | OpenRouter LLM, moderation (Paper), tool RPC, proxy config |

## Plans

| File | Description |
|------|-------------|
| [2026-06-24-arc-core-phase-a.md](superpowers/plans/2026-06-24-arc-core-phase-a.md) | Phase A implementation |
| [2026-06-24-arc-core-redis.md](superpowers/plans/2026-06-24-arc-core-redis.md) | Redis module implementation |
| [2026-06-24-arc-core-scheduling.md](superpowers/plans/2026-06-24-arc-core-scheduling.md) | Scheduling implementation |
| [2026-06-24-proxyarc-modules.md](superpowers/plans/2026-06-24-proxyarc-modules.md) | ProxyARC modules implementation |
| [2026-06-24-architecture-docs.md](superpowers/plans/2026-06-24-architecture-docs.md) | Architecture docs and skills |
| [2026-06-24-arc-core-ai.md](superpowers/plans/2026-06-24-arc-core-ai.md) | arc-core-ai implementation |
