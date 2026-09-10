# Shared plugin primitives

This is the routing index for code shared by ARC, ProxyARC, and sibling
plugins. Before implementing infrastructure locally, search this table by the
behavior name. Gameplay rules remain in their plugin; reusable safety and
lifecycle mechanisms belong here.

For a complete composition example, see
[`agentic-plugin-reference.md`](agentic-plugin-reference.md).
New plugin repositories must make this routing executable with
[`new-plugin-contract.md`](new-plugin-contract.md); its capability manifest and
CI verifier reject missing modules and high-signal local duplicates.

## Choose the owner

| Need | Module and API | Contract |
|------|----------------|----------|
| Pixel padding and measured component alignment | `arc-core`: `PixelSpacing`, `ComponentTextLayout`, `GlyphWidths`, `TextAlignment` | [Contract and examples](component-text-layout.md). Standalone padding selects existing pack glyphs. Alignment adds verified font advances and bounded wrapping. Handle typed unsupported results; subtract widget padding and verify the loaded pack and client pixels. |
| Additive bundled config upgrade | `arc-core`: `Config.mergeMissingFromBundled` | Copy only missing mapping entries recursively, preserve operator and unknown values, optionally exclude environment-owned root sections, persist atomically, and leave explicit type conflicts for feature validation. |
| Player/backend identifiers at a network boundary | `arc-core`: `NetworkPlayerName`, `BackendServerId` | Validate once, then pass the typed value. Widen the explicit policy only for a verified external namespace. |
| Reload-safe scheduled work | `arc-core`: `LifecycleTaskScope`, `whenCompleteSync` | One scope owns one lifecycle. `restart()` cancels old work and stale epoch tokens cannot schedule or execute work. |
| Bounded crash-safe local state | `arc-core`: `AtomicFileStore` | Resolve below a trusted root, reject traversal/symlinks, validate before and after an atomic replacement, and bound bytes. |
| Durable per-record recovery | `arc-core`: `DurableRecordJournal` | Commit one bounded record per safe identifier, verify the durable readback, list deterministically, and acknowledge idempotently. Keep domain transitions in the consumer. |
| Durable recovery call order | `arc-core`: `DurableRecoveryWorkflow` | Commit and compare the durable readback before mutation; restore and verify before exact acknowledgement. Storage, executors, and domain transitions remain injected. |
| Globally one-time bearer capability | `arc-core`: `OneTimeUseLedger`, `OneTimeUseFingerprint`; `arc-core-sql`: `MySqlOneTimeUseLedger` | Claim the exact identity before value mutation, commit only after proven success, release only before mutation, and abandon an unknown result for exact recovery. Use one shared table and a stable per-feature `purpose`. |
| Burst coalescing | `arc-core`: `CoalescingAsyncWriter` | Keep at most one write in flight and the newest pending snapshot. Completion means the submitted snapshot or a newer one was stored. |
| Stable QA/debug readback | `arc-core`: `StructuredDebugLine` | Emit a bounded single line with ordered safe `key=value` fields. Never include secrets or raw network/persistence payloads. |
| Canonical runtime events | `arc-core`: `RuntimeEvent`, `StructuredRuntimeEventLine` | Use stable event/outcome names with bounded fields for operator and agent readback. Never attach raw payloads or secrets. |
| Agent-readable runtime health | `arc-core`: `RuntimeHealthRegistry`, `RuntimeHealthContribution`, `moduleRuntimeHealth` | Register only non-blocking in-memory probes; expose the bounded snapshot through authenticated ops and emit `ARC_HEALTH` periodically. Report readiness, recovery backlog, active leases, schema versions, and dependency state. |
| Local expiring network view | `arc-core`: `LeasedNetworkDirectory` | Keep a bounded lease map with deterministic expiry, optional monotonic sequences, capacity rejection, and fail-closed clock rollback. Authenticate transport before observation. |
| Localized MiniMessage | `arc-core`: `LocalizedMiniMessage` | Validate required keys at startup, select locale with a fallback, and insert untrusted values as `Component` placeholders. Opt into `renderLazy`, `renderOptionalLazy`, or `renderLinesLazy` for non-blocking suppliers evaluated only when used, once per successful render (shared across lines), on the caller's thread. |
| Optional ItemsAdder menu items | `arc-core-paper-menu`: `PaperMenuItemsAdderResolver` | Adapt `itemsadder:pack/item` to `pack:item` through a consumer-supplied enabled check and native lookup. Preserve bare legacy keys. No optional API access when unavailable; `PaperMenuItemFactory` owns cloning, fallback, and diagnostics. See [examples](lazy-text-and-external-items.md). |
| Composed player nameplate rows | `arc-core`: `PlayerNameplateRegistry`, `NameplateLayer`; `arc-core-paper`: `PaperPlayerNameplates`, `ViewAlignedPaperNameplateVisibilityPolicy`; `arc-core-paper-testing`: `RecordingPaperNameplateDisplayFactory` | Gameplay owns one styled `Component` per stable row. Core bounds and composes rows; Paper owns one transient TextDisplay per target, per-viewer distance/visibility/line-of-sight and optional camera-cone checks, passenger recovery, and terminal cleanup. |
| Strict JSON boundary | `arc-core-redis`: `RedisWireCodec`, `BoundedJsonCodec`, `JsonObjectContract`, `JsonArrayContract` | Use the minimal codec contract only for an explicit domain-to-wire adapter; otherwise prefer the bounded implementation, which rejects malformed/trailing data and resource-limit violations, validates an explicit object or array root, then runs domain validation. |
| Atomic Redis hash transition | `arc-core-redis`: `RedisHashUpdater` | Return typed changed/unchanged/rejected/contended outcomes. Corrupt state fails closed; `consume` deletes only the exact value read. |
| Pub/sub origin and replay safety | `arc-core-redis`: `OriginBoundRedisBus`, `RecentMessageDeduplicator` | Authorize transport origin before parse, match embedded origin when present, bound and deduplicate message ids, and never log raw rejected payloads. |
| Lifecycle-owned Redis events | `arc-core-redis`: `ValidatedRedisTopic`, `RedisReplayPolicy` | Register exactly once on open, apply the strict codec/origin/replay boundary, publish typed messages, and unregister idempotently on close. Publishing after close fails. |
| Bounded Redis request/reply | `arc-core-redis`: `RedisRequestReplyChannel`, `RedisRequestResult` | Correlate only an exact bounded id, cap pending requests, validate replies against the original request and transport origin, own timeout cancellation, and complete every pending future on close. |
| Redis-backed network presence | `arc-core-redis`: `RedisPresenceDirectory`, `RedisPresenceRefresh` | Require hash field = decoded entry id, allowlist origin and domain policy before caching, convert observations into bounded expiring leases, and return typed rejection counts without raw payloads. |
| Paper backend transfer | `arc-core-paper`: `BackendTransfer`, `BungeeBackendTransfer` | Route only to a typed `BackendServerId`; own channel registration and return a typed delivery outcome. |
| Cross-server dungeon party gather | `arc-core`: `DungeonPartyGatherRequest`, `DungeonPartyGatherArrival` | The Paper source supplies an authorized team candidate roster, ProxyARC validates the carrier, selects at most five online players, routes them to the dungeon backend, and returns one leader-first roster to the target Paper node. |
| Transient Paper player UI | `arc-core-paper`: `PaperAudienceEffects`, `NativePaperAudienceEffects`; `arc-core-paper-testing`: `RecordingPaperAudienceEffects` | Gameplay owns content and timing. Production delegates to the exact Adventure API; tests record every effect and still delegate supported calls to MockBukkit. |
| Local asynchronous Paper teleport | `arc-core-paper`: `PaperTeleportExecutor`, `NativePaperTeleportExecutor`; `arc-core-paper-testing`: `RecordingPaperTeleportExecutor` | Gameplay owns authorization, destination choice, generation tokens, and completion. The port owns only the exact pinned Paper call and a recordable test boundary. |
| Reference-counted Paper chunk tickets | `arc-core-paper`: `PaperChunkTicketRegistry`, `PaperChunkTicketLease` | Create one registry per plugin lifecycle. Share one native plugin ticket across idempotent leases, never remove a borrowed ticket, reject new leases after close, and retain uncertain cleanup for retry. |
| Narrow teleport exception | `arc-core-paper`: `ScopedTeleportAuthorizer` | Authorize one player and one exact world/position/rotation only for the dynamic extent of one action. Nested scopes are rejected and cleanup is unconditional. |
| Complete Paper player escrow | `arc-core-paper`: `PaperPlayerStateService`, `PaperPlayerStateCodec`, `PaperPlayerDataPersistence`; `arc-core-paper-testing`: `RecordingPaperPlayerDataPersistence` | Capture/restore on the primary thread, use versioned native item bytes plus SHA-256 and bounds, verify every restored field, then persist through the exact Paper boundary. Explicit partial APIs preserve inventory or location for cross-server recovery without weakening full restore. |
| Paper lifecycle composition | `arc-core-paper`: `PaperPluginRuntime` | Compose rather than inherit: own reload epochs and closeable resources explicitly, close tasks first, and emit canonical bootstrap/ready events. |
| Configurable inventory layout | `arc-core-menu`: `MenuLayoutParser`, `MenuContract`, `MenuCatalogRepository` | Declare consumer capability `menu`. YAML owns placement and background; code declares required semantic IDs. Validate the complete candidate and swap one generation only on success. |
| Paper inventory sessions and bounded observations | `arc-core-paper-menu`: `PaperMenuService`, `PaperMenuContent`, `PaperMenuFrame`, `PaperMenuItemFactory`, `PaperMenuTextContract`; `arc-core-paper-api`: `ArcTelemetryProvider` | Inventory Framework is internal. YAML owns safe item presentation including MiniMessage name/lore composition, conditions and repeated rows. Code supplies typed component tags and actions. `PaperMenuFrame` is the bounded bridge for slot-oriented renderers: it accepts only configured physical slots or one configured logical region. Cancel inventory mutation, except an explicit domain-reserved `PaperMenuTransferHandler` top-slot pickup; dispatch typed semantic targets once, reject invalid text/region overflow before mutation, invalidate stale generations and delayed feedback, and close the service at shutdown. ARC registers the optional telemetry provider at runtime. Consumers resolve it through Paper's service registry and skip observations when it is absent. UI observations contain only stable semantic IDs, slots, lifecycle phase, and bounded JDK values; they never carry titles, items, input text, or domain payloads. |
| Cloud chest storage | `arc-core-paper-menu`: `PaperMenuRuntime.openStorage`, `PaperCloudStorage`, `PaperCloudStorageContent`, `PaperCloudStorageSession` | A native chest with cursor pickup, partial quick moves, optional deposits, padding decorations, and configured navigation. The consumer supplies detached null-preserving snapshots and an atomic conditional replacement, owning permissions and persistence. Commit the backing edit before player slots/cursor; stale or rejected edits transfer nothing. Native bottom clicks/drags remain usable; unsafe top mutations are cancelled. Runtime replacement, quit and shutdown close the session. |
| Native Paper dialog sessions | `arc-core-paper-menu`: `PaperDialogRuntime`, `PaperDialogScreen`, `PaperDialogActionId`, `PaperDialogInputId` | Consumer configuration owns visible presentation and code owns typed domain actions. Keep one bounded session per runtime and shared actual-visit history per player across plugin classloaders; use stable screen IDs; reopening an existing ancestor automatically replaces it and discards intermediate visits (same-owner refresh lifecycle, foreign-owner dismissal); use beginFlow for programmatic root entries, reopen/onDismiss for fresh data and async invalidation, back(player) for completed child actions returning to their existing parent, close(player) for explicit flow exit. Empty grids use a native notice with the history footer. Use one event listener, bind action keys to player plus fresh nonce, consume before dispatch, re-authorize current domain state in the handler, and close the runtime at shutdown. |
| Platform-neutral test fixtures | `arc-core-testing`: `DeterministicClock`, `ControlledExecutor`, `FailureInjector` | Drive time, queued work, and named failure points without sleeps or races. Keep this artifact test-only in consumers. |
| Paper platform test runtime | `arc-core-paper-testing`: `MockBukkitTestRuntime` | Consume the pinned Paper/MockBukkit pair as a test dependency, own one global runtime per test, drive events and ticks deterministically, and always close it. |
| Real Redis/MySQL test services | `arc-core-integration-testing`: `RedisTestService`, `MySqlTestService` | Start one disposable Testcontainer with `use`, consume only the returned endpoint, choose an exact image only when schema/version behavior matters, and never depend on a host daemon port or binary. |

Package names are deliberately searchable and behavior-specific:

```text
ru.arc.network
ru.arc.nameplate
ru.arc.onetime
ru.arc.persistence
ru.arc.observability
ru.arc.runtime
ru.arc.text
ru.arc.redis.safety
ru.arc.redis.network
ru.arc.paper.network
ru.arc.paper.nameplate
ru.arc.paper.audience
ru.arc.paper.chunk
ru.arc.paper.teleport
ru.arc.paper.playerstate
ru.arc.paper.runtime
ru.arc.menu
ru.arc.paper.menu
ru.arc.testing
ru.arc.paper.testing
ru.arc.testing.containers
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

`DurableRecoveryWorkflow` encodes that order without choosing persistence or
threads. A Paper consumer supplies stages that marshal Bukkit mutations and
restores onto the primary thread; a Redis or file repository supplies exact
commit/readback and conditional acknowledgement.

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

Lifecycle-owned Redis event boundary:

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

val topic = ValidatedRedisTopic.open(
    redis = redis,
    channel = "arc:match:v1",
    codec = codec,
    originAllowed = allowedBackends::contains,
    embeddedOrigin = MatchMessage::origin,
    replay = RedisReplayPolicy(MatchMessage::id, ttlMillis = 60_000, maxEntries = 4_096),
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

One-time bearer capability:

```kotlin
val request = OneTimeUseClaimRequest(
    identity = OneTimeUseIdentity(voucherId, OneTimeUseFingerprint.sha256(signedPayload)),
    claimId = voucherId, // stable operation id; never generate a new id on retry
    claimantId = playerId,
)

when (val result = ledger.claim(request).join()) {
    is OneTimeUseClaimResult.Acquired -> {
        val claim = result.claim
        // Apply the external effect exactly once. If its outcome is unknown,
        // abandon(claim); do not release it.
        ledger.commit(claim).join()
    }
    OneTimeUseClaimResult.AlreadyConsumed -> Unit
    else -> error("one-time capability is not claimable: $result")
}
```

If a destination node intentionally lacks the origin world, pass an explicit
`fallbackWorld` to the partial restore API. The service resolves and verifies
the fallback destination; it never guesses one. Use
`restoreWithoutInventoryAndVerify` only when the owning escrow says the live
inventory must be preserved.

## Do not duplicate

- Do not build Bungee `Connect` bytes or command strings in a feature.
- Do not spawn one TextDisplay per plugin row or maintain a feature-local
  per-viewer nameplate loop. Register bounded rows with the shared composer and
  let one `PaperPlayerNameplates` lifecycle own the physical display.
- Do not repeat raw Adventure delivery, local `teleportAsync`, `saveData`, or
  plugin chunk-ticket lifecycle behind feature callbacks. Compose the matching
  `arc-core-paper` port from a feature-named semantic interface.
- Do not add a feature-local username/server-id regex.
- Do not create another reload epoch, task bag, atomic JSON file writer,
  recovery call-order chain, one-time voucher/book table, expiring network map,
  MiniMessage fallback engine, Redis CAS loop, replay map, pending Redis reply
  map, pub/sub registration wrapper, hash-backed node TTL cache, or player
  snapshot format.
- Do not weaken a shared primitive to fit one caller. Add a typed policy or a
  narrow injected seam and cover the new contract in `arc-core` tests.
- Do not move gameplay state machines, GUI composition, or feature-specific
  repository schemas into core merely because two classes look similar.
- Do not hardcode reusable menu slots or raw-slot action routing in a Paper
  plugin. Declare a `MenuContract`, load a configured layout, and bind domain
  handlers through `PaperMenuContent`. Never put arbitrary commands in YAML.
- Do not declare MockBukkit directly in a plugin or manage its global singleton
  ad hoc. Use `arc-core-paper-testing` and follow
  [`paper-testing.md`](paper-testing.md).
- Do not repeat Redis/MySQL Testcontainers setup in a plugin. Use
  `arc-core-integration-testing` and follow
  [`integration-testing.md`](integration-testing.md).
- Do not compose `OriginBoundRedisBus` and `LeasedNetworkDirectory` manually
  for ordinary event, request/reply, or hash-presence flows. Use the application
  layer in [`redis-networking.md`](redis-networking.md); use the lower-level
  primitives only for a wire protocol that cannot satisfy those contracts.
- Do not restore implicit legacy Redis config readers. `RedisConfigBootstrap`
  copies the bundled default or an explicit consumer-provided
  `RedisConnectionSettingsSnapshot`; trusted operator config remains flexible
  in the owning plugin parser.

## Verification

Run the focused module while iterating and the complete gate before publishing:

```bash
./gradlew :arc-core:test
./gradlew :arc-core-redis:test
./gradlew :arc-core-paper:test
./gradlew :arc-core-menu:test
./gradlew :arc-core-paper-menu:test
./gradlew :arc-core-testing:test
./gradlew :arc-core-paper-testing:test
./gradlew :arc-core-sql:compileIntegrationTestKotlin
./gradlew :arc-core-integration-testing:integrationTest
./gradlew testAll publishToMavenLocal
```

Tests for a new shared primitive must cover its typed success outcomes, every
rejection branch, bounds, lifecycle cleanup, races where applicable, and an
integration test at the platform or storage seam. A test-only seam must keep
the production default bound to the native exact-version API.
