# Packet player NPCs

`ru.arc.paper.npc.PaperPacketNpcs` renders ephemeral player actors without Bukkit
entities, native AI, tracking, collision, chunk tickets or persistence. It targets
Minecraft/Paper 1.21.11 with the installed PacketEvents 2.12.1 plugin. Declare the
`paper-packet-npcs` consumer capability, depend on ArcCore and PacketEvents, and keep
PacketEvents and `arc-core-paper-api` out of the consumer's shaded artifact.

Keep profile resolution, the bounded actor count, navigation, terrain checks,
activity, combat and domain authorization in the consumer. A packet actor is not a
`Player` or `LivingEntity`; services that require native targets need an explicit
domain adaptation. Core performs no skin HTTP request, world mutation or pathfinding.

## Create and own actors

Create the owner, mutate handles and close it on Paper's main thread. Reuse the
consumer runtime's `BudgetedTaskQueue` where it already has one:

```kotlin
val npcs = PaperPacketNpcs(plugin, "residents", nativeWork)
val actor = npcs.spawnPlayer(
    home,
    PacketNpcProfile(residentUuid, "rc_a12b34", textureValue, textureSignature),
)
actor.setEquipment(EquipmentSlot.HAND, tool)
actor.teleport(nextPosition)
actor.headYaw = lookingYaw
actor.pitch = lookingPitch
actor.scale = 0.5
actor.swingMainHand()
```

The two-argument owner constructor supplies an owned native queue. The profile's
UUID and ASCII name must be unique within that owner; use internal names rather
than real online player identities. Skin properties are immutable, already
resolved values. The client receives `ADD_PLAYER` with `UPDATE_LISTED=false`
before the `PLAYER` spawn, so skin loading has its profile without a tab-list
entry. A private packet team suppresses the native nameplate and collision.
The profile stays available until retirement, which removes both entity and
profile and deletes its team.

`teleport(Location)` is the authoritative position setter. Small same-world
changes use relative move/look packets, preserving the vanilla remote-player
walking interpolation. Coordinates are quantized to 1/4096 block before computing
deltas, so rounding does not accumulate. A displacement of eight blocks or more
uses an absolute teleport; a world change retires the previous generation.
`bodyYaw`, `headYaw` and `pitch` are independent presentation controls.
`scale` defaults to 1.0 and accepts finite values from 0.25 through 2.0. It sends
the native `minecraft:scale` attribute on spawn and only on later scale changes,
without replacing the actor or its attached displays. Standing/crouching
`height`, `eyeHeight` and interaction width follow that scale. As in native
1.21.11, sleeping dimensions and eye height remain fixed at 0.2 blocks.
`PacketNpcPose.SLEEPING` plus `sleepingBed` supplies the pose and bed position;
the consumer owns a real, correctly oriented bed and the feet anchor. Waking
clears the bed metadata. The six player equipment slots, main-hand swing and hurt
animation are supported. An equipment item is copied on Paper before encoding.

Range defaults to 48 blocks. `isVisibleByDefault`, `showTo` and `hideFrom` express
actor privacy. Each viewer also needs the current world and received chunk.
World/respawn, chunk load/unload, reconnect and retired actors invalidate the
corresponding generation, including a pending spawn that has not yet been sent.

## Attach existing displays

```kotlin
val caption = displays.spawnText(home, Component.text("Житель"))
caption.attachTo(actor)
```

The existing display transformation remains passenger-local. Spawn uses the
actor's pose/scale-aware passenger origin. Use one display owner per carrier, as for a
native player attachment. No second display renderer or periodic world-teleport
fallback is created.

A per-viewer lease becomes available only after the NPC spawn transaction has
been handed to that connection. Display capture and Netty both check that exact
lease, so a delayed display cannot spawn or mount after its carrier retires.
The lease registers child display IDs; NPC cleanup unmounts and destroys those
children before destroying the actor. Chunk/world replay creates a fresh lease,
causing the retained display handles to replay under the new generation.
This is ordered server transport evidence, not a client acknowledgement.

## Input and work bounds

`onInteract` receives `(Player, PacketPlayerNpc, PacketNpcInteraction)` on Paper.
Packet intake accepts only an owned entity ID that was actually shown on the
same connection, main-hand `INTERACT` or `ATTACK`, one pending callback per viewer,
and at most one accepted callback per 100 ms. `INTERACT_AT` is ignored because the
vanilla follow-up `INTERACT` handles that gesture once.

Before dispatch, Core rechecks actor and connection generation, current privacy,
player life/online/world state, received chunk, eye ray versus the pose hitbox,
current interaction reach (bounded at six blocks plus 0.1 tolerance) and block
occlusion. The callback must still validate its own permission, economy, activity
and mutation conditions. Packet IDs or click intent alone never authorize work.

One shared native queue slices viewer capture and actor visibility; every sixteen
memory-only visibility checks yields. Packet preparation, metadata/equipment
deltas and encoding run on the captured connection's Netty loop through
`PaperVisualPackets`. Each connection retains one newest pending frame and one
retry. Unchanged actors emit nothing. Coalesced swing/hurt counters yield at most
one recent gesture, without replaying an accumulated animation history.

Call and observe `closeAsync()` in the consumer generation's drain. It seals the
owner and cancels its tasks/listeners, while host-owned cleanup continues retiring
actors and connections. The completion means cleanup packets reached write/flush
invocation; a lost channel or terminal failure is explicit. Partial writes retain
entity/profile cleanup identities before a same-channel replacement is allowed.
`close()` only dispatches this operation.

## Version and evidence boundary

The 1.21.11 server's mapped `Entity`, `LivingEntity` and `Avatar` static
`SynchedEntityData.defineId` calls establish the used metadata: pose index 6,
optional sleeping block index 14, skin-layer byte index 16. Since `Player` now
extends `Avatar`, the historical player skin index 17 is incorrect. The checked
PacketEvents 2.12.1 wrappers provide the matching metadata types and constructors.
Do not broaden this version contract by reusing remembered indices.

`PacketNpcTransportTest` covers 48 actors, idle deltas, relative movement, latest
frame admission, sleep/equipment/gestures, lifecycle replay, input admission,
partial-write retirement and display/carrier ordering.
`PaperPacketNpcsTest` covers viewer world/range/chunk/session changes, main-thread
input revalidation and host cleanup. The existing display tests protect the
native attachment route.

These are source/recording-transport checks. Skin appearance, walking/looking
smoothness, bed orientation, mounted cart/caption motion, empty-hand clicks and
two-viewer entry/departure still require Minecraft-client observation. A successful
build or packet trace does not establish that visual acceptance.
