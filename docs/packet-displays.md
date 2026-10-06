# Client-only Display scenes

`ru.arc.paper.display.PaperPacketDisplays` owns ephemeral BlockDisplay,
ItemDisplay and TextDisplay visuals for one plugin/module lifecycle. Use it for
bulk previews, idle item animations, roulettes and anchor markers. Keep reward
selection, access, targeting, durable anchors and gameplay state in the plugin.

The host supplies PacketEvents **2.12.1** and Paper **1.21.11**. Declare
`packetevents` as a plugin dependency and compile it only; do not shade it.
Other consumers of `arc-core-paper` need no PacketEvents dependency unless they
construct this service. Install core scheduling before construction.
The ARC host must also install [the shared visual packet provider](visual-packets.md)
before constructing a visual owner. All plugin copies resolve the same
`ArcVisualPacketBudget` API through Bukkit services; keep that API compile-only
in consumers and supplied by ARC.

```kotlin
val displays = PaperPacketDisplays(plugin, "workshop-tables")
val marker = displays.spawnBlock(location, blockData).apply {
    isVisibleByDefault = false
    showTo(player)
    brightness = Display.Brightness(15, 15)
    isGlowing = true
    interpolationDuration = 1
    teleportDuration = 1
}
// Later, on the server thread: stable ID, position/metadata delta only.
marker.teleport(nextLocation)
marker.transformation = nextTransformation
marker.remove()
// At owner shutdown, after animations stop:
displays.close()
```

Factories and all handle access belong on the server thread. Inputs and mutable
outputs (locations, item stacks, block data and transformations) are copied.
The service captures one frame per tick. It reads players, world IDs and received
chunks on that thread, then submits only protocol values to each connection's
Netty event loop. Encoding, delta calculation and packet writes happen there.
The queue coalesces pending animation frames while retaining replay requests and
any requested zero-duration visual-state edge for the same entity ID, UUID and
display kind. The edge applies to the latest frame for that entity incarnation;
the next normal-duration frame restores ordinary interpolation. Unchanged frames
still send nothing. Transform changes restart interpolation, position changes
teleport the existing ID, and item changes retain the ID. Reusing an entity ID
with a different UUID or display kind destroys the old client entity before
spawning the replacement.

Rate or channel pressure retains one latest desired scene, with one delayed
event-loop retry per attachment. Entity spawn and initial metadata are admitted
together. Large scenes progress in individual entity transactions; animation
updates rotate their starting entity to avoid starving later entities in one
scene. Removal precedes replacement and bypasses rate quotas, but waits for a
writable channel. Closing an owner replaces any deferred scene with empty state
and keeps bounded cleanup retries alive until sent or the connection closes.

By default, every online player in the same world and within `viewRange * 64`
blocks can see the visual, provided the client has received its chunk. Set
`isVisibleByDefault = false` and use `showTo` for an explicit audience, or
`hideFrom` to exclude one player from a public animation. Chunk unload/reload,
respawn, world changes and new/failed connections replay the current scene.
Cleanup is ordered before replacement spawns and remains eligible after a failed
batch. Entity IDs use Paper's `UnsafeValues.nextEntityId()` global allocator directly,
without entity construction or per-ID reflection.

No visual is written to the world, so new scenes cannot leave native orphan
entities. A migrating plugin may remove old native entities only through its
existing exact owner tags, including in chunks loaded after startup. Core does
not scan or delete world entities, and cannot identify an old untagged item as
belonging to a particular feature.

Focused checks:

```sh
./gradlew :arc-core-paper:test --tests 'ru.arc.paper.display.*'
```

The tests cover immutable capture, audience/chunk/connection lifecycle, delta
and coalescing behavior, ordered cleanup and write failure recovery. They do not
prove client appearance or wire compatibility on a running Minecraft client;
those remain separate deployment checks.
