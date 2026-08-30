# Agentic-first plugin composition

This reference is the shortest supported path for a new Paper sibling plugin.
Each mechanism has one searchable owner, explicit outcomes, and deterministic
tests; the plugin keeps only its gameplay rules and storage adapters.

Before writing the bootstrap, copy the Paper `arc-core-consumer.toml` template
and enable the pinned verifier described in
[`new-plugin-contract.md`](new-plugin-contract.md). The reference below is the
composition evidence expected by that gate.

## Runtime composition

Create one `PaperPluginRuntime` in `onEnable`, register resources in creation
order, and close only the runtime in `onDisable`. Reverse shutdown then remains
visible and testable without a framework superclass.

```kotlin
private var runtime: PaperPluginRuntime? = null

override fun onEnable() {
    PaperArcRuntime.installScheduling(this)
    ArcLogging.install(loggingPlatform, loggingConfigSource, lokiInstallSpec)
    val active = PaperPluginRuntime(this, "example").also {
        runtime = it
        it.start("version" to pluginMeta.version)
    }

    val redis = active.own(createRedis())
    val network = active.own(createNetwork(redis))
    val service = active.own(createService(network))
    service.start()
    active.registerHealth("runtime") {
        RuntimeHealthContribution(
            recoveryBacklog = service.recoveryBacklog,
            activeLeases = network.activeLeaseCount,
            schemas = mapOf("journal" to Journal.CURRENT_SCHEMA),
            dependencies = mapOf("redis" to redis.isConnected()),
        )
    }
    active.ready("server" to serverId)
    active.reportHealthEvery(1_200L)
}

override fun onDisable() {
    runtime?.close()
    runtime = null
    Tasks.reset()
}
```

Do not install a Prometheus listener in an ordinary add-on plugin. Central
ARC/ProxyARC runtimes may opt into `arc-core-metrics`; add-ons publish bounded
health through `PaperPluginRuntime` and avoid per-plugin ports and samplers.

## Durable mutation and recovery

Adapt the domain repository once. The workflow does not choose a thread or
storage implementation.

```kotlin
val recovery = DurableRecoveryWorkflow<Record, RestoreReceipt>(
    commit = repository::commitAndReadBack,
    sameContent = Record::sameContent,
    acknowledge = repository::acknowledgeExactly,
)

recovery.commitThenMutate(candidate) { committed ->
    primaryThread { applyGameplayMutation(committed) }
}

recovery.restoreThenAcknowledge(committed) { record ->
    primaryThread { restoreAndVerify(record) }
}
```

Never treat a timeout or unknown commit result as permission to mutate or
delete. Map storage results to `DurableAcknowledgementOutcome`; a content
mismatch remains durable for reconciliation.

## Globally one-time effects

Use `OneTimeUseLedger` for vouchers, redeemable books, tickets, and similar
bearer capabilities. Persist one stable `claimId`; bind every authoritative
payload field into `OneTimeUseFingerprint`; and keep consumer rows in the
single `arc_one_time_uses` table with a unique `MySqlOneTimeUsePartition`
purpose. The only safe lifecycle is claim before mutation, commit after proven
success, release after a proven pre-mutation failure, or abandon after an
unknown outcome. Do not generate a replacement claim id during recovery.

## Network presence

Use `ValidatedRedisTopic` for fire-and-forget events,
`RedisRequestReplyChannel` for correlated commands, and
`RedisPresenceDirectory` for Redis-hash node advertisements. These APIs own
listener lifecycle, replay bounds, pending request timeouts, and local lease
cleanup. The plugin still owns its strict wire DTO, origin allowlist, reply
authorization, domain-entry policy, and configured TTL. See
[`redis-networking.md`](redis-networking.md).

## Tests

Use `arc-core-testing` for pure orchestration and
`arc-core-paper-testing` for Bukkit behavior:

```kotlin
testImplementation("ru.arc:arc-core-testing:1.0-SNAPSHOT")
testImplementation("ru.arc:arc-core-paper-testing:1.0-SNAPSHOT")
```

- `DeterministicClock` replaces sleeps and hand-written clocks.
- `ControlledExecutor` exposes queued completions and ordering.
- `FailureInjector` names exact failure points.
- `MockBukkitTestRuntime` owns one Paper singleton per test and always closes.

## Platform ports without a god context

Keep domain services ignorant of whether Paper, MockBukkit, or a hand-written
fake performs an effect. Give the plugin a narrow semantic interface such as
`FarmBlockPlatform` or `GiveawayAudience`; its production adapter may compose
one or more exact Core mechanisms such as `PaperAudienceEffects`,
`PaperTeleportExecutor`, or `PaperChunkTicketRegistry`.

The split is intentional:

```text
feature service -> plugin semantic port -> native plugin adapter -> Core exact Paper port
       test      -> plugin fake          -> optional Core recorder / MockBukkit runtime
```

Do not collapse unrelated block, entity, chunk, audience, persistence, and
network operations into one `PlatformContext`, and do not expose a constructor
full of function callbacks. A semantic port changes when the feature language
changes; a Core port changes only when the shared pinned-platform contract
changes. Promote the latter only when reuse or lifecycle/safety evidence is
concrete, then add its test-kit counterpart and consumer capability in the same
change.

Operator configuration is trusted input. Keep it expressive; validate only
syntax, resource bounds, and values that cross an untrusted player, network, or
persistence boundary. Do not add command-root allowlists merely to silence a
static scanner.

Health probes run from async log or ops threads. They read cached counters and
flags only: no Bukkit/Velocity calls, Redis, SQL, filesystem access, waits, or
raw exception/payload output. Expose `runtime.snapshot().asMap()` through the
authenticated ops route used by MCP, and let `reportHealthEvery` provide the
same bounded state in Loki.

For real storage seams, add the shared container test artifact:

```kotlin
integrationTestImplementation("ru.arc:arc-core-integration-testing:1.0-SNAPSHOT")
```

Use `RedisTestService.start().use { ... }` or
`MySqlTestService.start(settings).use { ... }`; see
[`integration-testing.md`](integration-testing.md). Do not fall back to a host
Redis binary, fixed port, or locally repeated Testcontainers boilerplate.
