# Client-only Display scenes

`ru.arc.paper.display.PaperPacketDisplays` owns ephemeral BlockDisplay,
ItemDisplay and TextDisplay visuals for one plugin/module lifecycle. Use it for
bulk previews, idle item animations, roulettes and anchor markers. Keep reward
selection, access, targeting, durable anchors and gameplay state in the plugin.

The host supplies PacketEvents **2.12.1**, Paper **1.21.11**, and the shared
`ArcNativeCleanupService` (required at construction). Declare
`packetevents` as a plugin dependency and compile it only; do not shade it.
Other consumers of `arc-core-paper` need no PacketEvents dependency unless they
construct this service. Install core scheduling before construction.
The ArcCore host must also install [the shared visual packet provider](visual-packets.md)
before constructing a visual owner. All plugin copies resolve the same
`ArcVisualPacketBudget` API through Bukkit services; keep that API compile-only
in consumers and supplied by ArcCore.

```kotlin
val displays = PaperPacketDisplays(plugin, "workshop-tables", runtime.nativeWork)
val marker = displays.spawnBlock(location, blockData).apply {
    isVisibleByDefault = false
    showTo(player)
    brightness = Display.Brightness(15, 15)
    isGlowing = true
    interpolationDuration = 1
    teleportDuration = 1
}
val carried = displays.spawnItem(player.location, itemStack).apply {
    // Supply a passenger-local transformation; Core does not infer hand/shoulder offsets or yaw.
    transformation = carriedPose
    attachTo(player)
}
// Later, on the server thread: stable ID, position/metadata delta only.
marker.teleport(nextLocation)
marker.transformation = nextTransformation
marker.remove()
// A mounted handle keeps this as its settled world position for detaching.
carried.teleport(settledLocation)
carried.detach()
// At owner shutdown, after animations stop:
val cleanup = displays.closeAsync()
// Include cleanup in the generation drain before constructing its replacement.
// Never join/get this future on the server thread.
```

Factories and all handle access belong on the server thread. Inputs and mutable
outputs (locations, item stacks, block data and transformations) are copied.
The service runs one coalesced sweep at a time through the runtime's shared
`BudgetedTaskQueue`. Audience/tracking entries, carrier visibility, uncached
audience predicates and final connection checks keep individual native steps.
Frame copies and unattached viewer/display checks use bounded chunks of at most
16 memory-only operations per step; a group's already-captured result is also
memory-only. Location world IDs and item/block protocol contents are captured
when handles are created or changed. The queue checks its aggregate time budget
between chunks. Passing the runtime queue shares its count/time allowance;
legacy constructors create one owner queue. Timer pulses do not accumulate sweeps
while native capture or event-loop admission is incomplete. World/respawn/chunk
changes and explicit privacy changes fence a partially captured view. Large
scenes may take multiple ticks to capture; geometry, population and visibility
limits are unchanged. Paper's sent-chunk snapshot getter remains one indivisible
native call.

Short sweeps explicitly spend the queue's remaining allowance in the current
Paper tick, without first waiting for a delayed task. `drainAvailable` binds the
queue to Paper's tick identity: all display owners and scheduled continuations
share the same allowance, including repeated calls within one tick.
Exhausted work resumes on a later tick. Completing capture waits asynchronously
for that sweep's Netty admissions instead of polling their futures with native
steps. Slow Netty still holds the single-capture gate, so immutable snapshots do
not accumulate ahead of the connection queue.

Only detached protocol snapshots cross to Netty. Collection sorting/copies,
frame merging, encoding, delta calculation and packet writes happen there.
Main does not acquire the connection's frame-processing monitor. Each connection
retains the existing latest-frame/replay/snap-edge semantics.
The queue coalesces pending animation frames while retaining replay requests and
any requested zero-duration visual-state edge for the same entity ID, UUID and
display kind. The edge applies to the latest frame for that entity incarnation;
the next normal-duration frame restores ordinary interpolation. Unchanged frames
still send nothing. Transform changes restart interpolation, position changes
teleport the existing ID, and item changes retain the ID. Reusing an entity ID
with a different UUID or display kind destroys the old client entity before
spawning the replacement.

`PacketDisplay.attachTo(player)` mounts the same client-only entity ID as a
passenger of that player's native entity, including a tracked Citizens PLAYER NPC.
NPC carriers are captured separately from online viewers; no connection or sent-chunk
loader is read for them. A weak native reference is released on detach/remove. While mounted, the current main-thread
player position/world/chunk snapshot drives visibility and tracking; the display
is not sent a world-position teleport each tick. Its `transformation` is the
passenger-local pose, and `teleport(location)` records the world position used by
`detach()`. Set the local translation/rotation explicitly: the Core does not
choose a hand/shoulder anchor or promise yaw inheritance. The caller must account
for the native passenger attachment point when converting a feet-relative pose to
this passenger-local transform. Core snapshots the carrier's current native height
and spawns the display at feet plus that height, preserving the local transform.
This matches the 1.21.11 player passenger origin even before a rate-limited
passenger link is admitted; it does not choose or alter the consumer's local pose.
Paper describes display passengers as appearing above the vehicle's head
([Paper display entity docs](https://docs.papermc.io/paper/dev/display-entities/)).

`attachTo(carrier: LivingEntity, passengerOrigin: Vector)` extends the same queue to
tracked native mobs. Supply the current world-axis passenger-origin offset from
carrier feet; the copied value places initial spawn correctly before a budgeted
mount link is admitted. Core captures each distinct carrier once per refresh,
including tracking and native passenger IDs, without treating it as an online
viewer. Visibility also checks `Player.canSee(entity)` and carrier validity.
The caller owns the exact client-version seat/bone geometry, local transform and
orientation; Core does not assume an entity's bounding-box height is its seat.
Update the supplied offset if pose/scale changes. Real rider IDs retain their
order before admitted display IDs. The overload creates no Bukkit passenger or
world entity, and uses the existing detach/reconnect/cleanup protocol.

For each viewer, Core sends spawn+initial metadata before the passenger link, and
removes a link before sending a detached world teleport. Synthetic link changes
use the same coalesced connection queue and visual packet budget; native full-replacement
`SET_PASSENGERS` packets are merged with only fake display IDs whose spawn and
link were admitted. Core keeps the latest native passenger list and appends its
admitted IDs, so a later real passenger update cannot silently unmount them or
replace them with a stale list. A vehicle may have only one active
`PaperPacketDisplays` owner attaching fake passengers; cross-owner merging is not
provided. Carrier visibility is bounded by the existing Paper tracking/visibility
snapshot; viewers that cannot see the carrier do not receive its mounted displays.

Rate or channel pressure retains one latest desired scene, with one delayed
event-loop retry per attachment. Entity spawn and initial metadata are admitted
together. Large scenes progress in individual entity transactions; animation
updates rotate their starting entity to avoid starving later entities in one
scene. Removal precedes replacement and bypasses rate quotas, but waits for a
writable channel. Closing an owner replaces any deferred scene with empty state
and keeps cleanup retries alive until sent or the connection closes.
`close()` is dispatch-only for binary compatibility. `closeAsync()` immediately
seals capture and invalidates handles, then retires one detached native/handle
step at a time through the host cleanup queue, which survives consumer disable.
It closes the transport only after accepted capture admissions and connection
cleanup settle. Success means cleanup packets were admitted and flushed, not
acknowledged by a Minecraft client. Observer cancellation cannot cancel accepted
cleanup. Host shutdown, lost channels and write failures complete exceptionally;
full server stop cannot promise future native ticks. Include the actual future
in a consumer's predecessor drain before re-enabling that generation.

By default, every online player in the same world and within `viewRange * 64`
blocks can see the visual, provided the client has received its chunk. Set
`isVisibleByDefault = false` and use `showTo` for an explicit audience, or
`hideFrom` to exclude one player from a public animation. Chunk unload/reload,
respawn, world changes and new/failed connections replay the current scene. A
queued drain that reaches a closed channel quietly invalidates that attachment;
a replacement channel receives a fresh replay.
Cleanup is ordered before replacement spawns and remains eligible after a failed
batch. Entity IDs use Paper's `UnsafeValues.nextEntityId()` global allocator directly,
without entity construction or per-ID reflection.

For a shared scene gate (for example, a game's cached room line of sight), create
`displays.createAudienceGroup { viewer -> policyAllows(viewer) }` and assign the
same group to every scene handle's `audienceGroup` before the first frame. The
predicate is evaluated on the server thread at most once per eligible viewer in
each owner refresh. Explicit `showTo`/`hideFrom`, world, range, received chunks
and carrier visibility remain independent restrictions: an allowed group never
publishes a private card or control. Core owns per-refresh memoization only;
the feature owns any expensive-query cache and its viewer lifecycle.

A group belongs to exactly one display owner. Close it when the scene ends;
closing releases its predicate and makes any still-attached handles invisible.
The owner also closes all groups during shutdown. Removed handles release their
group reference, while teleports and attach/detach preserve it. Without a group,
the existing visibility policy is unchanged.

Core also suppresses cosmetic updates to already spawned displays that are
definitely behind the viewer's server-reported eye/look direction. This is a
conservative rear cone, not the client's exact frustum: FOV and third-person
camera mode are unavailable. Pure sphere/cone checks run on Netty. Both the last
admitted pose and the newest pose must lie beyond the rear margin before updates
pause, so an object crossing the view cannot leave its old visible pose behind.
The margin uses 125 degrees to pause and 115 degrees to resume, with a 0.75-block
near guard outside the object's radius. Attachments always keep normal updates.

Bounds include transform translation/scale, authored display width/height and
shadow radius. Blocks use their unit geometry; ordinary items without declared
bounds use a padded 1.5-model-unit sphere multiplied by the largest scale.
Oversized resource-pack item meshes must declare suitably large display bounds;
Core cannot discover arbitrary client model geometry. Text without authored
bounds remains uncullable. Rotation and billboard changes do not shrink the
spherical envelope.

Rear suppression retains entity IDs and the actual last admitted frame; it does
not destroy/spawn on camera turns or advance the packet baseline for skipped
updates. On returning, the latest pose is sent with zero teleport/transform
duration once, and the next refresh restores the caller's interpolation. Initial
spawns, privacy loss, chunk/world replay, replacement and terminal cleanup keep
their normal lifecycle, even while the display is behind the viewer. No wall
occlusion or ray tracing is performed.

The optional `ru.arc.paper.api.ArcAfkProvider` supplies the server's canonical
AFK state. The main-thread audience capture resolves it once and reads one
bounded, in-memory Boolean per viewer; no provider means no AFK pause. Core does
not infer AFK from native idle duration or evaluate placeholders. While AFK,
ordinary pose/content updates and new cosmetic spawns pause even in front of the
viewer. Existing IDs retain their admitted state; return snaps to the newest
state and creates any deferred displays. Privacy removals, chunk/reset
invalidation, terminal cleanup and attachment relation changes still reconcile.
An AFK chunk replay removes stale IDs immediately and defers their fresh spawn
until the player returns. Providers register through Bukkit services using the
host's API class and unregister at their own lifecycle boundary.

No visual is written to the world, so new scenes cannot leave native orphan
entities. A migrating plugin may remove old native entities only through its
existing exact owner tags, including in chunks loaded after startup. Core does
not scan or delete world entities, and cannot identify an old untagged item as
belonging to a particular feature.

Focused checks:

```sh
./gradlew :arc-core-paper:test --tests 'ru.arc.paper.display.*'
```

The tests cover immutable capture, audience/chunk/connection lifecycle, rear
bounds and hysteresis, canonical AFK snapshots, pause/resume packet deltas,
coalescing, ordered cleanup and write failure recovery. They do not
prove client appearance or wire compatibility on a running Minecraft client;
those remain separate deployment checks.
