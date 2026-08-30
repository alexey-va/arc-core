# Player nameplates

`PlayerNameplateRegistry` and `PaperPlayerNameplates` provide one reusable,
bounded path for extra live rows above a player. Gameplay plugins own the text
and decide when a value changes; core owns composition and Paper owns the
transient display lifecycle.

## Ownership

- `arc-core` owns stable row keys, deterministic priority, capacity bounds,
  typed rejections, snapshots, and owner cleanup.
- `arc-core-paper` owns one `TextDisplay` per target, passenger attachment,
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

`PaperNameplateOptions.scale` and `verticalOffset` control the complete native
display transformation. Keep their defaults for an additive row at the normal
passenger anchor; set explicit values only after checking the composed plate
against the server-owned vanilla/TAB name tag.

Displays are hidden by default and shown per viewer. They are non-persistent,
removed on close, removed immediately when the target quits, dies, or changes
world, and recreated after the next reconciliation when the target returns or
the passenger relationship is lost. Registry rows remain available while the
target is offline.

`PlayerNameplateRegistry` is thread-safe. `PaperPlayerNameplates.open`,
`refreshNow`, event reconciliation, and `close` require Paper's primary thread.
Normal callers update the registry and let its bounded repeating reconciliation
apply the newest immutable snapshot.

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
