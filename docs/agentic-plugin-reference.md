# Agentic-first plugin composition

This reference is the shortest supported path for a new Paper sibling plugin.
Each mechanism has one searchable owner, explicit outcomes, and deterministic
tests; the plugin keeps only its gameplay rules and storage adapters.

## Runtime composition

Create one `PaperPluginRuntime` in `onEnable`, register resources in creation
order, and close only the runtime in `onDisable`. Reverse shutdown then remains
visible and testable without a framework superclass.

```kotlin
private var runtime: PaperPluginRuntime? = null

override fun onEnable() {
    PaperArcRuntime.installScheduling(this)
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

Authenticate and decode with `OriginBoundRedisBus`, then pass only accepted
messages to `LeasedNetworkDirectory`. The directory owns bounded replacement,
expiry, rollback, and optional sequence ordering; product TTL stays configured
by the plugin.

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
