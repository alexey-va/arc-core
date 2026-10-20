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
| Reload-safe scheduled work | `arc-core`: `LifecycleTaskScope`, `whenCompleteSync` | One scope owns one lifecycle. `restart()` cancels old work and stale epoch tokens cannot schedule or execute work. `callSync` completes queued native promises exceptionally on cancellation; a fire-and-forget callback may intentionally be dropped. |
| Bounded I/O and pure computation | `arc-core`: `BoundedAsyncExecutor`, `JvmShutdownDrain` | Explicit owned workers, bounded admission and rejection without inline fallback. Nonwaiting close drains accepted work; `closeAsync(finalizer)` reserves the first close's terminal flush on a worker after all accepted work. A cancelled observer does not undo an accepted write. The owner must close on disable; the JVM hook waits up to 120 seconds without sealing a consumer's still-needed terminal lane. Forced termination still requires durable recovery. Shared cleanup lanes disable their individual hook and are protected by the provider's full drain instead. |
| Aggregate native tick work | `arc-core`: `BudgetedTaskQueue` | One round-robin count/time budget per runtime across requests (default 512 steps / 4 ms per tick). Pending tasks are retained without capacity rejection; producers chain/batch futures to control backlog. The legacy `maximumPending` parameter is only an allocation hint. `submit` is owning-thread only and never inline; `call` marshals a bounded stage from a worker. An explicit `drainAvailable(LongSupplier)` pump binds the owning platform's tick clock and spends only that tick's remaining aggregate allowance, shared with scheduled continuations. Fence resumption by operation/lifecycle; close the queue before its scope. Shutdown/stale-context cancellation and real failures remain explicit; an in-memory queue is not durable recovery. One native call cannot be preempted. |
| Native cleanup after consumer disable | `arc-core-paper-api`: `ArcNativeCleanupService`; host: `PaperNativeCleanupServiceProvider` | Submit a detached cursor on Paper; each step releases one concrete entity/lease without accessing closed consumer state. The host owns one shared tick budget and the actual completion stage. Include it in the generation predecessor barrier. Observer cancellation does not cancel accepted work. Full host stop fails unfinished work; ephemeral entities and startup recovery remain the consumer's responsibility. This is not a durable storage acknowledgement. |
| Authoritative offline UUID inventory and persisted names | `arc-core-paper`: `PaperOfflinePlayerData` | Capture the primary-world playerdata path on Paper with `capture(server)`; list UUID `.dat` filenames and read requested `bukkit.lastKnownName` values on an existing worker. Merge native online UUID/name snapshots in the caller. Missing or malformed name data never removes UUIDs; per-ID diagnostics contain no NBT payload. Compressed/decompressed byte bounds and Adventure's depth limit apply. Own no additional executor or cache; never call cold `OfflinePlayer.name` or `getOfflinePlayers` on Paper. |
| Bounded crash-safe local state | `arc-core`: `AtomicFileStore` | Resolve below a trusted root, reject traversal/symlinks, validate before and after an atomic replacement, and bound bytes. |
| Durable per-record recovery | `arc-core`: `DurableRecordJournal` | Commit one bounded record per safe identifier, verify the durable readback, list deterministically, and acknowledge idempotently. Keep domain transitions in the consumer. |
| Durable recovery call order | `arc-core`: `DurableRecoveryWorkflow` | Commit and compare the durable readback before mutation; restore and verify before exact acknowledgement. Storage, executors, and domain transitions remain injected. |
| Globally one-time bearer capability | `arc-core`: `OneTimeUseLedger`, `OneTimeUseFingerprint`; `arc-core-sql`: `MySqlOneTimeUseLedger` | Claim the exact identity before value mutation, commit only after proven success, release only before mutation, and abandon an unknown result for exact recovery. Use one shared table and a stable per-feature `purpose`. |
| Burst coalescing | `arc-core`: `CoalescingAsyncWriter` | Keep at most one write in flight and the newest pending snapshot. Completion means the submitted snapshot or a newer one was stored. |
| Stable QA/debug readback | `arc-core`: `StructuredDebugLine` | Emit a bounded single line with ordered safe `key=value` fields. Never include secrets or raw network/persistence payloads. |
| Canonical runtime events | `arc-core`: `RuntimeEvent`, `StructuredRuntimeEventLine` | Use stable event/outcome names with bounded fields for operator and agent readback. Never attach raw payloads or secrets. |
| Durable player/product activity telemetry | `arc-core-telemetry`: `PlayerTelemetryEvent` and the shared SQL-backed capture/query owner; `arc-core-paper-api`: `ArcTelemetryProvider.recordActivity` | Paper consumers submit stable domain identifiers and named scalar attributes through the registered provider; ProxyARC feeds the same shared store. A `true` result means the bounded asynchronous capture queue accepted the event, not that SQL is durable. Keep one shared outbox/store, use only bounded semantic values, and never include chat or raw command arguments. UI observations stay on `observeUi`; do not route them through this activity event contract. |
| Agent-readable runtime health | `arc-core`: `RuntimeHealthRegistry`, `RuntimeHealthContribution`, `moduleRuntimeHealth` | Register only non-blocking in-memory probes; expose the bounded snapshot through authenticated ops and emit `ARC_HEALTH` periodically. Report readiness, recovery backlog, active leases, schema versions, and dependency state. |
| Local expiring network view | `arc-core`: `LeasedNetworkDirectory` | Keep a bounded lease map with deterministic expiry, optional monotonic sequences, capacity rejection, and fail-closed clock rollback. Authenticate transport before observation. |
| Localized MiniMessage | `arc-core`: `LocalizedMiniMessage` | Validate required keys at startup, select locale with a fallback, and insert untrusted values as `Component` placeholders. Opt into `renderLazy`, `renderOptionalLazy`, or `renderLinesLazy` for non-blocking suppliers evaluated only when used, once per successful render (shared across lines), on the caller's thread. |
| Optional ItemsAdder menu items | `arc-core-paper-menu`: `PaperMenuItemsAdderResolver` | Adapt `itemsadder:pack/item` to `pack:item` through a consumer-supplied enabled check and native lookup. Preserve bare legacy keys. No optional API access when unavailable; `PaperMenuItemFactory` owns cloning, fallback, and diagnostics. See [examples](lazy-text-and-external-items.md). |
| Composed player nameplate rows | `arc-core`: `PlayerNameplateRegistry`, `NameplateLayer`; `arc-core-paper`: `PaperPlayerNameplates`, `ViewAlignedPaperNameplateVisibilityPolicy`; `arc-core-paper-testing`: `RecordingPaperNameplateDisplayFactory` | Gameplay owns one styled `Component` per stable row. Core bounds and composes rows; Paper owns one transient TextDisplay per target, per-viewer distance/visibility/line-of-sight and optional camera-cone checks, passenger recovery, and terminal cleanup. |
| Client-only Block/Item/TextDisplay scenes | `arc-core-paper`: `PaperPacketDisplays`, `PacketDisplay`, `PacketDisplayAudienceGroup` | Plugin owns geometry and audience policy. Core spends the runtime BudgetedTaskQueue's current-tick allowance for short captures, batches up to 16 memory-only frame/visibility operations per step while keeping native calls individually sliced, and prepares/sends detached frames on Netty with one pending admission sweep. A conservative size-aware rear cone suspends already-spawned display updates per viewer without ID churn; return snaps to the latest state, and privacy/cleanup remain unconditional. Observe closeAsync in the generation drain; host-owned cleanup survives consumer disable, while host stop or lost channels fail explicitly. Owner-bound audience groups gate whole scenes once per eligible viewer per refresh without overriding explicit privacy. `PacketDisplay.attachTo(player)` reuses the packet entity ID as a native passenger, suppresses mounted world teleports, merges only admitted fake IDs into native passenger replacements, and restores links through the same per-connection queue. One display owner per vehicle; no native visual entity, chunk ticket, or world persistence. See [packet displays](packet-displays.md). |
| Client-only player NPCs | `arc-core-paper`: `PaperPacketNpcs`, `PacketPlayerNpc` | Consumer owns a bounded actor population, resolved skin profiles, movement and gameplay. Core captures world/range/received-chunk audiences in the shared native budget and coalesces player pose, equipment and gestures through `PaperVisualPackets` on Netty. Hidden profile precedes PLAYER spawn; retirement removes both. `PacketDisplay.attachTo(npc)` waits for that viewer's admitted spawn generation and retires children before their carrier. Input is bounded and revalidated on Paper for shown ID/session, audience, reach, aim and walls; domain authorization remains local. Declare `paper-packet-npcs` and observe `closeAsync`. See [packet NPCs](packet-npcs.md). |
| Keyed mixed spatial interfaces and native controls | `arc-core-paper`: `PacketDisplayScene`, `ScenePart`, `PaperSpatialControls`, `PaperSpatialSpace`, `AnchoredSurface` | Stable keyed Text/Item/Block reconciliation over the existing packet owner; shared audience, generation-fenced actual-plane input, native recovery, exact collision shapes and deterministic anchored escape. Explicit Viewer/World lifetime; optional cached plaque refresh joins the existing capture every five ticks. Keep content, source selection, project anchors, chest lid/camera candidates and authority local. Declare `paper-spatial-scenes` for direct composition. See [contract and preservation oracles](paper-spatial-scenes.md). |
| Personal/contextual floating action controls | `arc-core-paper`: `PaperActionPanels`, `ActionPanelController`, `ActionPanelFrame`, `ActionPanelLayouts` | Delegates physical scene/input ownership to the shared spatial owners. Typed context/action snapshots preserve explicit button geometry, fixed/target/view placement, native left/right or externally routed ray gestures, fresh authority handoff, actual-eye hover/clicks, full-footprint reach/occlusion, obstacle hysteresis and scoped guide suppression. Stable keys retain packet text; a changed context rotates native input IDs. Borrow or own the existing packet display runtime; close once at the feature lifecycle boundary. Declare `paper-action-panels`. See [profiles, lifecycle and preservation checks](paper-action-panels.md). |
| Rendered display footprint, collision and proxy scaling | `arc-core-paper`: `DisplayOrientedVolume`, `DisplayGeometry`; `ActionPanelGeometry` for yaw-oriented controls | Copied arbitrary orthonormal OBB bases, exact collision-box overlap, translation sweep, whole-card center/corner samples, clearance pullback and proportional proxy scale are reusable by mixed item/text scenes and camera cards. Panel helpers add finite ray-plane hits, disabled-nearest blocking, whole-rectangle reach and rotation-aware swept envelopes. Keep authored scene motion, attachment, content, and eligibility in the owning feature; do not replace a camera-following HUD with selection-stable placement. |
| Shared visual packet admission and traffic accounting | `arc-core-paper-api`: `ArcVisualPacketBudget`; `arc-core-paper`: `PaperVisualPacketRuntime`, `PaperVisualPackets` | ArcCore registers one shared provider before visual owners, independently of ARC. Per-server/per-connection payload budgets and Netty writability defer replaceable visuals; owners coalesce the latest state, preserve ordered cleanup and retry asynchronously. Counters, bounded source metrics and verbose Mbit/s logs measure encoded payload before compression, not wire bandwidth. Native gameplay packets stay outside this opt-in path. See [visual packet budgets](visual-packets.md). |
| Canonical AFK pause for cosmetic displays | `arc-core-paper-api`: `ArcAfkProvider`; `arc-core-paper`: `PaperPacketDisplays` | The plugin owning AFK policy registers one typed, bounded in-memory UUID lookup through Bukkit services. Core snapshots it once per viewer on the main thread, pauses ordinary display updates and new spawns, and resumes current state without replaying history. Missing provider means no pause; privacy removals, resets, cleanup and attachment relation changes remain active. Core does not invent an idle threshold or query placeholders. |
| One-shot viewer dust effects | `arc-core-paper`: `PaperViewerParticles` | Capture dust values on the Paper thread and send one viewer-only packet through `PaperVisualPackets`; pressure, closed channels, owner close, and invalidated session/target effects are dropped without retry or history. Caller owns audience and cadence. See [visual packet budgets](visual-packets.md). |
| Viewer-only entity glow | `arc-core-paper`: `PaperViewerEntityGlow` | Create one lifecycle owner after PacketEvents and the host native cleanup service initialize. Set or clear a viewer/entity pair on Paper; Core overrides only that viewer's metadata glow bit. `cleanupDrained()` observes accepted restorations without closing the owner. `closeAsync()` detaches selections in O(1), restores one pair per shared host step, then waits for packet write/flush invocation or superseding native state/disconnect; it does not acknowledge socket/client delivery. Consumer retirement must await it; `close()` only dispatches. Host stop fails unfinished cleanup. |
| Shared per-viewer inspection card | `arc-core-paper-api`: `ArcInspectionService`, `ArcInspectionProvider`, `ArcInspectionFrame`; `arc-core-paper`: `PaperArcInspectionService` | One Paper owner arbitrates sources by priority and renders one client-only hologram or bossbar per viewer. `null` falls through; an empty frame occupies the target and suppresses lower sources. The host supplies polling/preferences; providers supply hit resolution, content and an optional immutable `InspectionHologramAnchor` at the target top. Anchored cards stay upright in world space and ignore viewer-relative offsets. |
| Shared Paper SQL pools | `arc-core-paper-api`: `ArcSqlService`; `arc-core-paper`: `PaperSqlServices` | Install the plugin-local factory on Paper before creating any `SqlRuntime` on a worker; the host owns matching pools and executors. `installConsumer(plugin, terminalDrain)` retains existing leases through the consumer's actual terminal flush even if the host stops first; close seals new acquisitions immediately. Complete the terminal drain on failure too. Exact settings, including credentials and limits, define compatibility. Accepted tasks, reserved durable completions, and held connections drain before physical close. See [shared Paper services](shared-paper-services.md). |
| Shared Paper Redis transport | `arc-core-paper-api`: `ArcRedisService`; `arc-core-paper`: `PaperRedisClient` | Capture the typed service/owner on Paper and use the service overload to acquire on a worker. Borrow an explicit connection and origin or the host primary profile. Before host shutdown, use `retainDuringShutdown`/`closeAfter(actualTerminalDrain)` for a consumer that still needs terminal writes; a detached observer or fire-and-forget save is not a durable acknowledgement. Each lease owns its listeners; close and unique-channel replacement never remove another plugin's subscription. Domain codecs and replay policy remain in the consumer. |
| Shared Paper technical metrics | `arc-core-paper-api`: `ArcMetricsService`; `arc-core-paper`: `PaperArcMetrics` | Open one stable owner scope, declare bounded counters/gauges/timers, and close with the consumer. ARC owns the registry/exporter. Use stable labels, never player/request identifiers or free text. Handles survive registry reload; absent telemetry must not block gameplay. |
| Cross-plugin protected blocks | `arc-core-paper-api`: `ArcBlockProtectionService`; `arc-core-paper`: `PaperBlockProtection` | Feature owners register bounded, memory-only spatial checks with Bukkit ServicesManager. Automated editors consult all registered providers before every world mutation; no provider is bypassed by service priority. Off-thread calls and provider failures deny edits. ArcCore hosts the shared contract class. |
| Async schematic libraries, native block transforms and bounded paste | `arc-core-paper-api`: `ArcSchematicService`, `ArcSchematicLibrary`; optional `arc-core-paper-schematics`: `PaperSchematicServiceProvider` | ArcCore owns one bounded worker/cache budget across consumer roots. Acquire and verify files asynchronously; retain compact immutable snapshots only for active work. Palette adaptation and native writes use aggregate primary-thread tick budgets. Preserve block-entity NBT; explicitly choose how to handle source entities/biomes. Native paste requires loaded chunks; its opt-in `Consumer<ArcSchematicBlock>` observes accepted writes at world coordinates on Paper after each native batch, excludes skips/repair-only writes, and logs/disables a failed observer without retrying mutations. The explicit paced overload adds a primary-thread `BooleanSupplier` gate and tick interval for one accepted block/pair per interval; waiting retains the operation without spinning or blocking ordinary mutations. Fence/wall/pane repair follows the consumer's durable commit. See [schematics](paper-schematics.md). |
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
| Shared native Paper sidebar | `arc-core-paper-api`: `ArcSidebarService`, `ArcSidebarFrame`, `ArcSidebarPriorities`; `arc-core-paper`: `PaperArcSidebarService` | Register one lifecycle-owned source, publish the exact current 0–15 rows per player, and hide it when inactive. ARC owns the physical scoreboard; the highest active priority wins and the next source is restored immediately. Event frames may carry hidden-name entries so name-tag state stays on the same board even with no visible rows. |
| Configurable inventory layout | `arc-core-menu`: `MenuLayoutParser`, `MenuContract`, `MenuCatalogRepository` | Declare consumer capability `menu`. YAML owns placement and background; code declares required semantic IDs. Validate the complete candidate and swap one generation only on success. |
| Paper inventory sessions and bounded observations | `arc-core-paper-menu`: `PaperMenuService`, `PaperMenuContent`, `PaperMenuFrame`, `PaperMenuItemFactory`, `PaperMenuTextContract`; `arc-core-paper-api`: `ArcTelemetryProvider` | Inventory Framework is internal. YAML owns safe item presentation including MiniMessage name/lore composition, conditions and repeated rows. Code supplies typed component tags and actions. `PaperMenuFrame` is the bounded bridge for slot-oriented renderers: it accepts only configured physical slots or one configured logical region. Cancel inventory mutation, except an explicit domain-reserved `PaperMenuTransferHandler` top-slot pickup; dispatch typed semantic targets once, reject invalid text/region overflow before mutation, invalidate stale generations and delayed feedback, and close the service at shutdown. ARC registers the optional telemetry provider at runtime. Consumers resolve it through Paper's service registry and skip observations when it is absent. UI observations contain stable semantic IDs, slots and row/page indexes, click/action kinds, material keys, amounts, lifecycle phase, and bounded JDK values; they never carry item stacks, lore/NBT, titles, input text, or domain payloads. |
| Cloud chest storage | `arc-core-paper-menu`: `PaperMenuRuntime.openStorage`, `PaperCloudStorage`, `PaperCloudStorageContent`, `PaperCloudStorageSession` | A native chest with cursor pickup, partial quick moves, optional deposits, padding decorations, and configured navigation. The consumer supplies detached null-preserving snapshots and an atomic conditional replacement, owning permissions and persistence. Commit the backing edit before player slots/cursor; stale or rejected edits transfer nothing. Native bottom clicks/drags remain usable; unsafe top mutations are cancelled. Runtime replacement, quit and shutdown close the session. |
| Opt-in resource-pack dialog decoration | `arc-core-paper-menu`: `DialogDecoration` | Caller supplies the font, scalar glyph IDs, measured advances and signed spacing ranges. Composes repeatable bitmap button frames with independently centered native labels and 16px icon insets, open text corners and thin dividers. Unsupported labels retain the original text; no pack, global style or runtime is installed. Verify the composed result at the intended GUI scale. |
| Native dialog text frames and tables | `arc-core-paper-menu`: `DialogTables.framedBody`, `DialogTables.body` | Use `framedBody(content, frame, width)` for one uninterrupted text block, including lore previews; use `body` only for actual paired columns. Both use the pack metrics, preserve styled text and wrapping, and align their outer edges at the same width. Unsupported metrics retain readable unframed content. Pass all arguments explicitly across plugin classloaders. |
| Native Paper dialog sessions | `arc-core-paper-menu`: `PaperDialogRuntime`, `PaperDialogScreen`, `PaperDialogActionId`, `PaperDialogInputId`, `PaperDialogTextInput`, `PaperDialogNumberRangeInput` | Consumer configuration owns visible presentation and code owns typed domain actions. Text and numeric slider responses remain typed; validate every response again against current domain state. Keep one bounded session per runtime and shared actual-visit history per player across plugin classloaders; use stable screen IDs; reopening an existing ancestor automatically replaces it and discards intermediate visits (same-owner refresh lifecycle, foreign-owner dismissal); use beginFlow for programmatic root entries, reopen/onDismiss for fresh data and async invalidation, back(player) for completed child actions returning to their existing parent, close(player) for explicit flow exit. Empty grids use a native notice with the history footer. Use one event listener, bind action keys to player plus fresh nonce, consume before dispatch, re-authorize current domain state in the handler, and close the runtime at shutdown. |
| Large native dialog preparation | `PaperDialogRuntime.openAsync(player, screen, sharedNativeWork, isCurrent, reopen, onDismiss, closeOnEscape)` | Prepare each body/input/button through the runtime's existing `BudgetedTaskQueue`; nonce/handlers stay detached until the complete screen is ready. New requests, Back, flow reset, quit, runtime close and domain-generation loss cancel preparation. Show a small pending child during the original action, then asynchronously refresh it. Construct a large immutable screen model on the consumer worker. Paper's final `Dialog.create` codec conversion and `showDialog` are indivisible native calls; this API does not preempt those calls or cap the native packet. |
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
ru.arc.paper.schematics
ru.arc.paper.actionpanel
ru.arc.paper.display
ru.arc.telemetry
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
- Do not preload schematic folders, expose cached mutable WorldEdit clipboards,
  or add a consumer-local schematic cache, disk hash, transform or native
  connection-repair loop. Use `ArcSchematicLibrary`; keep book ownership,
  payments, permissions and durable world-change journals in the consumer.
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

## Execution boundaries during cold preparation

`Config` construction, merge, reload and save are blocking worker operations.
Prepare all cold `ConfigManager.of/ofModule` lookups before publishing a native
runtime. Ready module resolution is cached; `reloadAll` refreshes known legacy/
modular paths on its worker. YAML reads and emission do not own the live getter
write lock; a reload candidate racing with a setter/save is rejected by mutation
revision instead of erasing the acknowledged edit. Config snapshots contain raw data; binding Bukkit material, item,
registry, world or menu objects still belongs to the owning thread.

Install `PaperSqlServices.installConsumer` on Paper once so its factory captures
service identity there. `SqlRuntime.create` and `ArcSqlService.acquire` are
blocking worker-only cold operations. SQL/Redis groups prepare outside the
native diagnostics monitor and reject late acquisitions after close. Physical
transport/pool close runs on owned drain workers; provider `closeAsync` observes
it without a main-thread wait. At most 128 physical connection groups (including
preparing/retiring groups) bound each provider's resources and cleanup admission.

ArcCore may publish the visual/schematic providers while preparing its host
configuration. Visual admission returns `STARTING` until the validated policy
is attached; deferred schematic libraries wait for configured limits within
bounded admission. No default policy grants a temporary startup bypass.

`PaperPlayerStateCodec.encodePayload` captures native item bytes on Paper, one
bounded player per native slice. `wrapPayload` performs detached Base64/checksum
work on a worker. For restore, `prepareDecode(envelope)` authenticates and parses
the outer envelope on a worker, retaining opaque, privately owned item blobs;
`decode(preparedPayload)` binds native items and validates actual slot counts on
Paper without repeating Base64, SHA or outer parsing. The prepared value belongs
to that codec instance. `validateEnvelope` remains a pure metadata validation
when a prepared native restore is not needed. Item contents remain a native check
before any restore/mutation. Native
`Player.saveData` remains an indivisible owning-thread durability barrier.

`MySqlOneTimeUseLedger.closeAsync()` seals new claims and observes accepted
operations, retained advisory-lock release and completion-resource cleanup on its
owned worker. An attached ledger never closes the borrowed `SqlRuntime`; close
that facade only after the ledger future completes, and complete the consumer's
SQL terminal drain last. Do not replace actual completion with a consumer timeout
that releases leases while durable cleanup is still running. Legacy standalone
`close()` retains its bounded waiting contract and belongs on a worker.
