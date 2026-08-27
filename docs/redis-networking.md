# Redis networking application layer

Use `ru.arc.redis.network` for ordinary cross-server application flows. It
composes the lower-level strict codec, origin, replay, and lease primitives
without taking ownership of gameplay rules or wire DTOs.

## Choose one owner

| Flow | API | Consumer still owns |
|------|-----|---------------------|
| Fire-and-forget event | `ValidatedRedisTopic<T>` | DTO, codec, allowed origins, handler |
| Command with one response | `RedisRequestReplyChannel<T>` | request id, `replyTo`, reply authorization, result mapping |
| Node/service advertisements in a Redis hash | `RedisPresenceDirectory<T>` | entry id, origin, timestamp/sequence, TTL, domain-entry policy |

Do not put queues, matches, giveaways, farm workdays, player messages, or host
selection into core. The application layer owns only reusable transport
lifecycle and safety.

## Validated topic

`ValidatedRedisTopic.open` registers one listener immediately. It authorizes
the transport origin before parsing, decodes through `RedisWireCodec`, checks
an optional embedded origin, and applies optional bounded replay protection.
`close()` unregisters idempotently; `publish()` after close throws.

```kotlin
val events = ValidatedRedisTopic.open(
    redis = redis,
    channel = "arc:feature:v1:events",
    codec = eventCodec,
    originAllowed = allowedServers::contains,
    embeddedOrigin = FeatureEvent::origin,
    replay = RedisReplayPolicy(FeatureEvent::id, 10.minutes.inWholeMilliseconds, 4_096),
    onMessage = service::accept,
    onRejected = metrics::recordRedisRejection,
)
```

Create the topic during service startup, store the returned owner, and close
that owner during service shutdown. Do not separately call Redis listener
registration methods.

## Request/reply

`RedisRequestReplyChannel` uses the same validated topic and adds a bounded
pending map. A response can complete a future only when its `replyTo` matches
an exact pending request and `replyAllowed(original, response, origin)` returns
true. A rejected or unmatched response never consumes pending state.

```kotlin
val commands = RedisRequestReplyChannel(
    redis = redis,
    channel = "arc:feature:v1:commands",
    codec = commandCodec,
    originAllowed = allowedServers::contains,
    requestId = FeatureCommand::id,
    replyTo = FeatureCommand::replyTo,
    replyAllowed = { request, reply, origin ->
        reply.type == CommandType.RESULT &&
            origin == request.destination &&
            reply.destination == localServer
    },
    timeoutMillis = 12_000,
    maxPending = 32,
    replay = RedisReplayPolicy(FeatureCommand::id, 15.minutes.inWholeMilliseconds, 20_000),
    onMessage = service::acceptCommandOrEvent,
)

when (val result = commands.request(request).join()) {
    is RedisRequestResult.Reply -> service.acceptResult(result.message, result.originServer)
    RedisRequestResult.TimedOut -> service.degradeLocally()
    RedisRequestResult.CapacityExceeded -> service.rejectBusy()
    RedisRequestResult.Closed -> Unit
    else -> service.recordNetworkFailure(result)
}
```

The default timeout scheduler uses `Tasks.scheduler`; install platform
scheduling before constructing requests. Pure tests inject
`RedisRequestTimeoutScheduler`. `close()` cancels every timeout and completes
every pending future with `Closed`.

Redis pub/sub is not durable delivery. A timeout means no authorized reply was
observed; it does not prove that the remote command never ran. Domain commands
that mutate valuable state must therefore carry their own stable idempotency
key and durable transition.

## Presence directory

`RedisPresenceDirectory` publishes one strict value per hash field and refreshes
that hash into a bounded local `LeasedNetworkDirectory`. Before a value enters
the cache it must pass all of these checks:

1. bounded safe hash field;
2. strict codec and domain validation;
3. decoded entry id equals the Redis hash field;
4. decoded origin is safe and allowed;
5. consumer `entryAllowed` policy passes;
6. lease timestamp/sequence/capacity rules pass.

```kotlin
val presence = RedisPresenceDirectory(
    redis = redis,
    hashKey = "arc:feature:v1:nodes",
    codec = nodeCodec,
    entryId = FeatureNode::serverId,
    origin = FeatureNode::serverId,
    observedAtMillis = FeatureNode::heartbeatAtMillis,
    originAllowed = { it == localServer || it in allowedServers },
    entryAllowed = { it.heartbeatAtMillis <= clock() + allowedFutureSkewMillis },
    leaseMillis = configuredStaleMillis,
    maxEntries = 1_024,
    clockMillis = clock,
)

presence.publish(localNode).thenCompose { presence.refresh() }.thenAccept { refresh ->
    router.replaceNodes(refresh.values)
    metrics.recordPresenceRejections(refresh.rejected)
}
```

`updateLeaseMillis` clears cached leases so all entries are revalidated under
the new TTL. `RedisPresenceRefresh.rejected` contains bounded enum counts only;
never log raw Redis values.

## Tests and integration

Unit-test codecs and every policy branch with `InMemoryRedis`. Use a
deterministic injected timeout scheduler; do not sleep. Compile and run a real
storage seam with `arc-core-integration-testing` and `RedisTestService`.

Local development may compile integration sources when Docker is unavailable:

```bash
./gradlew :arc-core-integration-testing:compileIntegrationTestKotlin
```

CI and immutable releases run `./gradlew integrationTestAll`, which includes a
two-manager real Redis request/reply and presence round trip.
