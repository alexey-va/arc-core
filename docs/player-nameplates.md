# Player nameplates

`PlayerNameplateRegistry` and `PaperPlayerNameplates` provide one reusable,
bounded path for extra live rows above a player. Gameplay plugins own the text
and decide when a value changes; core owns composition and Paper owns the
transient display lifecycle.

## Ownership

- `arc-core` owns stable row keys, deterministic priority, capacity bounds,
  typed rejections, snapshots, and owner cleanup.
- `arc-core-paper` owns one `TextDisplay` per target, passenger attachment or head following,
  per-viewer visibility, line of sight, update/recreate, events, tasks, and
  shutdown cleanup.
- The consuming plugin owns wording, colors, data refresh, permissions, and
  mode-specific policy. Core contains no player-facing prose or MiniMessage
  parsing.
- Open one `PaperPlayerNameplates` per plugin lifecycle and register it in
  `PaperPluginRuntime.own`. Plugins must not spawn a second display for every
  row.

One registry composes all rows registered through that runtime. Independent
plugin classloaders do not automatically share a registry; a server that needs
several standalone plugins to contribute to one physical plate must expose one
designated runtime owner instead of starting competing renderers.

## Example

```kotlin
private val nameplateRows = PlayerNameplateRegistry()
private lateinit var nameplates: PaperPlayerNameplates

fun enable(plugin: Plugin, runtime: PaperPluginRuntime) {
    nameplates = runtime.own(
        PaperPlayerNameplates.open(
            plugin = plugin,
            registry = nameplateRows,
        ),
    )
}

fun updateDuelHealth(player: Player, health: Int) {
    nameplateRows.upsert(
        player.uniqueId,
        NameplateLayer(
            key = NameplateLayerKey("arcduels", "health"),
            priority = 100,
            content = Component.text("$health ❤", NamedTextColor.YELLOW),
        ),
    )
}

fun finishDuel(player: Player) {
    nameplateRows.remove(
        player.uniqueId,
        NameplateLayerKey("arcduels", "health"),
    )
}

fun disableDuels() {
    nameplateRows.clearOwner("arcduels")
}
```

Every contribution is exactly one visual row. Explicit plain-text newlines,
empty rows, oversized content, too many targets, and too many rows return typed
rejection results. Higher priority renders above lower priority; equal priority
is sorted by stable key. Composition uses a neutral root component so color and
decorations from one row never leak into the rows below it.

## Visibility and lifecycle

The native Paper policy hides a plate from the target itself and when the
target is offline, dead, vanished, invisible, spectating, in another world,
beyond the configured distance, or behind blocks. The `TextDisplay` also has
see-through rendering disabled. A consumer may inject a stricter
`PaperNameplateVisibilityPolicy` for an arena or duel.

`ViewAlignedPaperNameplateVisibilityPolicy` can wrap the native policy and hide
a plate when its target leaves an approximate camera cone. Its threshold is a
normalized direction dot product: `-1.0` disables the extra check, `0.0` keeps
the forward hemisphere and `0.5` keeps targets within roughly 60 degrees of the
camera center. The server does not know the client's exact FOV or aspect ratio,
so consumers should keep this value configurable and validate it visually for
their surface.

`PaperNameplateOptions.scale` and `verticalOffset` control the local display
transformation. Their defaults describe an additive row at the normal passenger
anchor. The packet factory's `atNativeNameTag` alternative below gives
`verticalOffset` a screen-plane row origin at the native name tag. Check explicit
values against the server-owned vanilla/TAB name tag.

Displays are hidden by default and shown per viewer. They are non-persistent,
removed on close, removed immediately when the target quits, dies, or changes
world, and recreated after the next reconciliation when the target returns or
the passenger relationship is lost. Registry rows remain available while the
target is offline.

`PlayerNameplateRegistry` is thread-safe. `PaperPlayerNameplates.open`,
`refreshNow`, event reconciliation, and `close` require Paper's primary thread.
Normal callers update the registry and let its bounded repeating reconciliation
apply the newest immutable snapshot.

## Optional packet rendering

`PaperPlayerNameplates.openViewerScoped` is the opt-in path for viewer-specific
content such as clan relationships. It preserves the existing `open` signature
and behavior. Its `PaperNameplateViewerContent` callback runs on the primary
thread after visibility checks, receives the composed base rows and returns a
component or null to hide the plate. It must use cached, bounded, read-only
gameplay state. Equal components share one private-audience display; at most
16 variants are rendered per target, with excess variants hidden. A target has
one visible variant per viewer. Audience changes hide old private viewers before
updating and reusing a handle; attachment loss and close retire every variant.

An existing specialized renderer can reserve its targets through the Java-shaped
`ArcNameplateClaim` service in `arc-core-paper-api`. Its `owns(UUID)` includes
temporarily hidden match phases. Ordinary consumers must yield to claims and
fail closed while a known specialized plugin is enabled without its provider.
The shared API stays in ArcCore's runtime JAR and remains compile-only in both
claim provider and consumer. This allows mutually exclusive target ownership
without changing an existing event's private nameplate policy.

The default remains `NativePaperNameplateDisplayFactory`. A consumer that already
owns `PaperPacketDisplays` may explicitly inject
`PacketPaperNameplateDisplayFactory(displays, options)` into
`PaperPlayerNameplates.open`. Both constructor arguments are required, including
when called across plugin classloaders.

The packet factory preserves the native text style, transformation, passenger
anchor and private per-viewer audience. It creates no Bukkit entity and uses the
existing display owner's shared visual budget, latest-frame coalescing and
attachment cleanup. The normal nameplate visibility policy still decides which
viewers may see each row. Invalid players and changed world/entity generations
invalidate the handle so reconciliation can recreate it.

Use `PacketPaperNameplateDisplayFactory.atNativeNameTag(displays, options)` for
rows aligned with the native 1.21.11 name tag. It follows the player's feet plus
their current pose/scale-aware height plus `0.5` blocks on the world's Y axis,
using the same player capture and visibility lifecycle without a passenger link.
The display remains `CENTER` billboarded. `options.verticalOffset` then moves
the row in the local screen plane; the world anchor is not rotated by camera
pitch, including front-facing third-person view. `options.brightness` is opt-in:
`null` preserves sampled world light, while an explicit value overrides it.

The follower retains its entity ID and sends normal position deltas through the
borrowed packet owner's usual tick capture, with one-tick teleport interpolation.
Budgeted capture or transport can defer updates; it does not inherit the client's
passenger movement prediction. The original two-argument constructor retains its
passenger placement. Native-client motion and appearance are separate acceptance
checks; frame and factory tests do not establish them.

The display owner is borrowed. Close `PaperPlayerNameplates` first to remove its
handles; closing a nameplate never closes the shared owner. When the consuming
plugin retires that owner, it must track or await `displays.closeAsync()` through
its asynchronous lifecycle cleanup so client destruction is observed. Do not
block Paper's primary thread waiting for this future.

## Testing

Use `RecordingPaperNameplateDisplayFactory` from `arc-core-paper-testing` for
MockBukkit scenarios. It records content, show/hide calls, attachment loss, and
cleanup without pretending that MockBukkit implements native TextDisplay
passengers or per-viewer entity packets.

```bash
./gradlew :arc-core:test :arc-core-paper:test :arc-core-paper-testing:test
```

The exact native `TextDisplay`, passenger, and per-viewer packet behavior still
requires the ordinary Paper lab smoke before a consuming plugin is promoted to
production.
