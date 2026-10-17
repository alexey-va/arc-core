# Shared spatial scenes

`ru.arc.paper.scene` in `arc-core-paper` composes mixed packet visuals, optional
native controls and non-loading world clearance. It adds no provider service,
packet transport or scheduler. Direct consumers declare `paper-spatial-scenes`.

## Visual ownership

`PacketDisplayScene<K>(displays)` borrows an existing `PaperPacketDisplays`.
Apply `SceneFrame` with keyed `ScenePart.Text`, `.Item` and `.Block` parts.
`ScenePose` copies Location and Transformation; item/block constructors copy
payloads. Styles preserve native rendering fields explicitly. Culling dimensions
are never treated as mesh size, input size or collision bounds.

Keys and part types determine handle identity. Content, item, style, position
and transform changes update that handle; key/type replacement removes only the
changed part. Unchanged items are compared against the stored immutable snapshot,
without cloning payloads or reconverting them into packet snapshots. A
frame's `teleportTicks` applies to every moving part. Callers decide whether the
whole motion is safe: blocked sweeps snap. Transform interpolation is explicit.

`hide()` is idempotent and retains keys. Authored invisible parts also retain
handles; an empty parts list removes them. `reset()` removes current handles but
keeps the scene reusable for a new source. `close()` also unregisters refresh.
Neither closes the borrowed packet owner. Failure cleanup attempts every handle.
Frames are bounded to 256 parts. Large schematic previews retain their existing
bounded asynchronous window selection and spawn-budget policy.

## Audience and input

`SceneAudience.Private(UUID, group?)` and `.Public(group?)` gate both visuals and
native controls. Owner-bound groups restrict but cannot override private audience.
UUID visibility works without storing Player references. World/range/sent chunks,
connection replay, backpressure and ordered cleanup remain with the packet owner.
All Bukkit/world reads and handle changes stay on the primary thread.

`PaperSpatialControls<C,A>(plugin, displays, controller)` also borrows the owner.
Its capture participant is registered on first scene application and removed on
last clear, so constructing idle controls does not trigger player audience reads.
Independent empty-scene placement refresh subscriptions remain active.
`apply(key, frame)` reconciles native targets; `target` resolves a fresh action;
`intercept` dispatches an aimed gesture. `SpatialControlController.current`
returns fresh state; `activate` rechecks feature authority. A native ID is only a
routing hint. Semantic context replacement rotates native IDs while visual keys
may remain stable. The 250 ms gate is per player/scene and survives replacement.
Exceptions from fresh state, ray filtering, input observation or activation abort
dispatch and revoke the scene's native input before calling the typed
`inputFailed(player, scene, context, failure)` cleanup hook. Feature adapters clear
their corresponding visuals/state there; the shared owner reports once, including
cleanup failures, and never retries an action. Action panels retain their existing
five-second renderer retry delay.

Each control declares actual rendered `SpatialHitPlane` separately from its
optional bottom-origin `SpatialNativeTarget`. Native dimensions remain unscaled
when text uses scale 2. Input checks current context, world, audience, actual eye,
plane, reach and first blocking ray. Optional copied `clearanceFootprints` (maximum
32) also recheck the full visual volume at input time, rejecting a new edge
obstacle between placement refreshes. The nearest disabled visible control blocks
fall-through. `SpatialReach.Fixed` serves personal panels; `.NativeInteraction`
uses the current player interaction-range attribute for public/book gateways.

Profiles preserve existing input: `ENTITY_BOTH` supports left/right and owns
cancelled right events; `ENTITY_RIGHT` honors cancellation; `EXTERNAL_RAY` creates
no native entities; `ENTITY_RIGHT_EARLY` preserves LOWEST, right-only gateway
behavior and leaves offhand events untouched. Frames permit 64 controls and each
control at most eight planes.

`SpatialSceneScope.Viewer(UUID)` clears on that owner's quit/death/teleport/world
change/respawn. `World` survives individual viewer events and closes through the
feature's domain teardown. Book previews use World scope plus existing owner
teardown because ordinary teleport does not discard a preview. Admin departure
never closes another owner's preview or a public project.

Native targets spawn only in already-loaded bounds; admission additionally needs
sent chunks. Invalid targets recover without recreating valid visuals. Lifecycle
changes hide prior native admission before forgetting it, so permission revocation
cannot retain an old explicit show override. No chunk load or ticket is issued.

## Placement

`PaperSpatialSpace` owns loaded/sent guards, exact collision-shape OBB tests,
known-ray paths and sample-point LOS. Collision boxes are translated once from
block-local coordinates, preserving empty stair/pane/fence space. Public placement
uses loaded world state; personal placement additionally requires sent chunks.

`AnchoredSurface` takes authored pose and explicit visual OBBs, independent of
input/culling geometry. Its outward/up/side escape is bounded and deterministic,
retains a safe previous offset, and requires extra clearance to return. Maximums
are 32 footprints and 512 candidates. No fit means hide plaque and controls;
never place inside a block or shrink text implicitly.

Construction sites use separate scenes for fixed outline and movable plaque.
Only visual/input plaque poses move, never persisted site anchor, face or blocks.
`PaperActionPanels` keeps following/layout/hover/hysteresis/guide policy and
uses these owners for visuals, input and exact queries. Chest retains its lid/
camera candidate policy and uses the common renderer and exact world boundary.
Camera-mounted spell HUDs are not converted into selection-following menus.

Event-driven plaques opt into `scene.refreshWith(SceneRefreshRegion(authoredAnchor,
audience, rangeBlocks), SceneRefreshController)`. The copied region is independent
of rendered handles, so initially empty and hidden scenes can recover.
Every five existing display-capture ticks it recomputes placement from cached
content and applies visuals/controls in one main-thread call. It does not rescan
project steps or create a timer. Scenes without an eligible nearby audience skip
refresh. Manual chest scenes do not subscribe. Close unregisters; hide permits
obstacle-removal recovery. Input validates fresh rays between refreshes.

## Oracles

`SpatialScenesTest` covers mixed stable keys, copied poses, item conversion
suppression, all-part motion, privacy transitions, borrowed ownership, refresh
cleanup, early native input, stale IDs/context, deduplication, public lifecycle
and permission revocation, target recovery, exact collision negative controls,
deterministic escape, contained native callback failures and no chunk loads.

Existing `PaperActionPanelsTest`, `ActionPanelGeometryTest` and
`ActionPanelWorldSpaceTest` retain stationarity, head/crouch independence, body
motion, input profiles, failure/no-fit retries, disabled-nearest handling, full
footprint reach/LOS, paging, thin swept barriers/sagitta, placement hysteresis and
guide restoration. Consumer tests retain chest lid/item-model behavior, book
menus, project authority and zero step rereads during progress. Source/test
verification remains separate from ordinary-client visual acceptance.
