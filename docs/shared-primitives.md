# Shared plugin primitives

This is the routing index for code shared by ARC, ProxyARC, and sibling
plugins. Before implementing infrastructure locally, search this table by the
behavior name. Gameplay rules remain in their plugin; reusable safety and
lifecycle mechanisms belong here.

## Choose the owner

| Need | Module and API | Contract |
|------|----------------|----------|
| Player/backend identifiers at a network boundary | `arc-core`: `NetworkPlayerName`, `BackendServerId` | Validate once, then pass the typed value. Widen the explicit policy only for a verified external namespace. |
| Reload-safe scheduled work | `arc-core`: `LifecycleTaskScope`, `whenCompleteSync` | One scope owns one lifecycle. `restart()` cancels old work and stale epoch tokens cannot schedule or execute work. |
| Bounded crash-safe local state | `arc-core`: `AtomicFileStore` | Resolve below a trusted root, reject traversal/symlinks, validate before and after an atomic replacement, and bound bytes. |
| Durable per-record recovery | `arc-core`: `DurableRecordJournal` | Commit one bounded record per safe identifier, verify the durable readback, list deterministically, and acknowledge idempotently. Keep domain transitions in the consumer. |
| Burst coalescing | `arc-core`: `CoalescingAsyncWriter` | Keep at most one write in flight and the newest pending snapshot. Completion means the submitted snapshot or a newer one was stored. |
| Stable QA/debug readback | `arc-core`: `StructuredDebugLine` | Emit a bounded single line with ordered safe `key=value` fields. Never include secrets or raw network/persistence payloads. |
| Localized MiniMessage | `arc-core`: `LocalizedMiniMessage` | Validate required keys at startup, select locale with a fallback, and insert untrusted values as `Component` placeholders. |
| Strict JSON boundary | `arc-core-redis`: `RedisWireCodec`, `BoundedJsonCodec`, `JsonObjectContract`, `JsonArrayContract` | Use the minimal codec contract only for an explicit domain-to-wire adapter; otherwise prefer the bounded implementation, which rejects malformed/trailing data and resource-limit violations, validates an explicit object or array root, then runs domain validation. |
| Atomic Redis hash transition | `arc-core-redis`: `RedisHashUpdater` | Return typed changed/unchanged/rejected/contended outcomes. Corrupt state fails closed; `consume` deletes only the exact value read. |
| Pub/sub origin and replay safety | `arc-core-redis`: `OriginBoundRedisBus`, `RecentMessageDeduplicator` | Authorize transport origin before parse, match embedded origin when present, bound and deduplicate message ids, and never log raw rejected payloads. |
| Paper backend transfer | `arc-core-paper`: `BackendTransfer`, `BungeeBackendTransfer` | Route only to a typed `BackendServerId`; own channel registration and return a typed delivery outcome. |
| Narrow teleport exception | `arc-core-paper`: `ScopedTeleportAuthorizer` | Authorize one player and one exact world/position/rotation only for the dynamic extent of one action. Nested scopes are rejected and cleanup is unconditional. |
| Complete Paper player escrow | `arc-core-paper`: `PaperPlayerStateService`, `PaperPlayerStateCodec` | Capture/restore on the primary thread, use versioned native item bytes plus SHA-256 and bounds, verify every restored field, then call `saveData`. Explicit partial APIs preserve inventory or location for cross-server recovery without weakening full restore. |
| Paper platform test runtime | `arc-core-paper-testing`: `MockBukkitTestRuntime` | Consume the pinned Paper/MockBukkit pair as a test dependency, own one global runtime per test, drive events and ticks deterministically, and always close it. |

Package names are deliberately searchable and behavior-specific:

```text
ru.arc.network
ru.arc.persistence
ru.arc.observability
ru.arc.text
ru.arc.redis.safety
ru.arc.paper.network
ru.arc.paper.teleport
ru.arc.paper.playerstate
ru.arc.paper.testing
```

## Required integration order

For any feature that can erase or replace valuable player state:

```text
capture on Paper primary thread
    -> encode bounded versioned envelope
    -> durably commit the domain escrow
    -> mutate inventory/location/state
    -> restore and verify every field
    -> persist Paper player data
    -> acknowledge/delete escrow with an exact conditional transition
```

`PaperPlayerStateService.captureEnvelope` only creates the payload. The plugin
owns the durable commit and must prove it succeeded before mutation.
`restoreAndVerify` returning a `PlayerStateRestoreReceipt` is the earliest safe
point at which the domain layer may acknowledge the escrow. An unknown storage
outcome is not permission to mutate or delete recovery state.

## Minimal examples

Lifecycle ownership:

```kotlin
private val tasks = LifecycleTaskScope()

override fun reload() {
    val epoch = tasks.restart()
    tasks.runTimer(epoch, delayTicks = 20, periodTicks = 20) { reconcile() }
}

override fun shutdown() = tasks.close()
```

Strict Redis message boundary:

```kotlin
val codec = BoundedJsonCodec(
    gson = gson,
    type = MatchMessage::class.java,
    rootContract = JsonObjectContract(
        allowedFields = setOf("id", "origin", "player", "arena"),
        requiredFields = setOf("id", "origin", "player"),
    ),
    bounds = JsonResourceBounds(maxCharacters = 8_192),
    validate = { message -> message.validated() },
)

val bus = OriginBoundRedisBus(
    redis = redis,
    channel = "arc:match:v1",
    codec = codec,
    originAllowed = allowedBackends::contains,
    embeddedOrigin = MatchMessage::origin,
    messageId = MatchMessage::id,
    deduplicator = RecentMessageDeduplicator(ttlMillis = 60_000, maxEntries = 4_096),
    onMessage = { message, origin -> matchService.accept(message, origin) },
)
```

Paper recovery boundary:

```kotlin
val envelope = playerState.captureEnvelope(player, clock.millis())
escrowRepository.commit(player.uniqueId, envelope) // must be durable
applyTemporaryLoadout(player)

val receipt = playerState.restoreAndVerify(player, envelope)
escrowRepository.acknowledgeExactly(receipt.playerId, receipt.envelopeSha256)
```

If a destination node intentionally lacks the origin world, pass an explicit
`fallbackWorld` to the partial restore API. The service resolves and verifies
the fallback destination; it never guesses one. Use
`restoreWithoutInventoryAndVerify` only when the owning escrow says the live
inventory must be preserved.

## Do not duplicate

- Do not build Bungee `Connect` bytes or command strings in a feature.
- Do not add a feature-local username/server-id regex.
- Do not create another reload epoch, task bag, atomic JSON file writer,
  MiniMessage fallback engine, Redis CAS loop, replay map, or player snapshot
  format.
- Do not weaken a shared primitive to fit one caller. Add a typed policy or a
  narrow injected seam and cover the new contract in `arc-core` tests.
- Do not move gameplay state machines, GUI composition, or feature-specific
  repository schemas into core merely because two classes look similar.
- Do not declare MockBukkit directly in a plugin or manage its global singleton
  ad hoc. Use `arc-core-paper-testing` and follow
  [`paper-testing.md`](paper-testing.md).

## Verification

Run the focused module while iterating and the complete gate before publishing:

```bash
./gradlew :arc-core:test
./gradlew :arc-core-redis:test
./gradlew :arc-core-paper:test
./gradlew :arc-core-paper-testing:test
./gradlew testAll publishToMavenLocal
```

Tests for a new shared primitive must cover its typed success outcomes, every
rejection branch, bounds, lifecycle cleanup, races where applicable, and an
integration test at the platform or storage seam. A test-only seam must keep
the production default bound to the native exact-version API.
