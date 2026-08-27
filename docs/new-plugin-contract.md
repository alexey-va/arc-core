# New plugin arc-core contract

Every new RusCrafting Paper or Velocity plugin carries an
`arc-core-consumer.toml` file at its repository root and runs the central
consumer verifier in CI. This turns the shared-primitives guide into an
executable architecture boundary: adding an infrastructure capability requires
the matching arc-core module and canonical API evidence, while high-signal
local reimplementations fail the build.

"Use all core modules" means **all applicable modules selected by capability**.
It does not mean putting Redis, SQL, Velocity, or AI on a plugin that has no
such boundary. Unused infrastructure increases startup, dependency, and
security surface without removing duplication.

## Start a repository

1. Copy the platform manifest from
   `templates/consumer-contract/<paper|velocity>/arc-core-consumer.toml`.
2. Pin one immutable public `core_version` across every arc-core artifact.
3. Add only the capabilities the plugin needs, before writing their adapters.
4. Run the verifier locally:

   ```bash
   python3 ../arc-core/scripts/verify_consumer_architecture.py .
   ```

5. Pin the central action to the exact arc-core release tag or commit in the
   consumer workflow; never use a floating branch:

   ```yaml
   - uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
   - uses: alexey-va/arc-core/.github/actions/verify-consumer@<immutable-ref>
   ```

The verifier emits stable `CONSUMER_CONTRACT_ERROR rule=...` lines with a file,
line, and remediation. An agent must fix the owning boundary; deleting a
capability or hiding source from the scan is not an acceptable way to make the
gate pass.

## Baseline by platform

| Platform | Required runtime modules | Required test modules | Required composition |
|---|---|---|---|
| Paper | `arc-core`, `arc-core-logging`, `arc-core-metrics`, `arc-core-paper` | `arc-core-paper-testing` | `PaperArcRuntime`, `PaperPluginRuntime`, `ArcLogging`, `ArcMetricsRuntime`, health probes, `LocalizedMiniMessage`, `MockBukkitTestRuntime` |
| Velocity | `arc-core`, `arc-core-logging`, `arc-core-metrics`, `arc-core-velocity` | none until a test capability is selected | `VelocityArcRuntime`, `PluginRuntime`, `ArcLogging`, `ArcMetricsRuntime`, health probes |

The baseline makes lifecycle ownership, scheduling installation, bounded health,
logging/metrics versions, localization, and deterministic platform testing
visible from the first commit. A plugin may remain small; it must not replace
the baseline with a local superclass, scheduler bag, health DTO, locale engine,
or MockBukkit singleton.

## Capability routing

| Capability | Required module/API evidence |
|---|---|
| `scheduling` | `Tasks`, `LifecycleTaskScope`, or the runtime task scope |
| `deterministic-testing` | `arc-core-testing` plus `DeterministicClock`, `ControlledExecutor`, or `FailureInjector` |
| `atomic-files` | `AtomicFileStore` |
| `coalesced-writes` | `CoalescingAsyncWriter` |
| `durable-recovery` | `DurableRecoveryWorkflow` / `DurableRecordJournal` |
| `one-time-use` | `ru.arc.onetime`, `arc-core-sql`, `MySqlOneTimeUseLedger`, and `MySqlTestService`; one shared `arc_one_time_uses` table with a feature purpose |
| `structured-debug` | `StructuredDebugLine` |
| `redis-networking` | `arc-core-redis`, `ValidatedRedisTopic` / `RedisRequestReplyChannel` / `RedisPresenceDirectory`, plus `RedisTestService` |
| `sql` | `arc-core-sql` plus `MySqlTestService` |
| `integration-testing` | `arc-core-integration-testing` and one shared test service |
| `backend-transfer` | `BackendTransfer` / `BungeeBackendTransfer` |
| `scoped-teleport` | `ScopedTeleportAuthorizer` |
| `player-state` | `PaperPlayerStateService` |
| `ai` | `arc-core-ai` |

See [`shared-primitives.md`](shared-primitives.md) for the contracts callers
still own. The manifest is not a substitute for domain tests: it proves routing
and dependency consistency, not gameplay behavior.

## Rejected local mechanisms

The initial gate rejects direct platform scheduling, direct MockBukkit or
Testcontainers ownership, local Hikari pools, raw `ATOMIC_MOVE`, local
MiniMessage engines, raw Jedis pub/sub, hand-written Bungee `Connect` payloads,
and feature-local voucher/coupon/ticket/book ledgers. Add new high-confidence
rules when a reusable semantic duplicate is found in review; keep rules narrow
enough that the error identifies one canonical replacement.

If a new requirement does not fit an existing primitive, extend arc-core with
a typed policy or narrow adapter seam and tests, update
[`shared-primitives.md`](shared-primitives.md), then add the consumer
capability. Do not add an exception list that silently legalizes a second
implementation.
