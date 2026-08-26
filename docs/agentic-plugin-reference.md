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
    active.ready("server" to serverId)
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
