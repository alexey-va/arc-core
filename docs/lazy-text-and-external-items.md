# Lazy text and optional external menu items

These APIs extend existing owners without adding a parser, global registry,
cache, or ItemsAdder dependency. They are independently implemented in Kotlin;
no NightCore source is copied. They are available in this source checkout;
published consumers must upgrade to a release containing them before adoption.

## Lazy localized text

```kotlin
val lines = messages.renderLinesLazy(
    "profile.lore",
    localeTag,
    mapOf(
        "player" to { Component.text(playerName) },
        "summary" to { summaryComponentFromSnapshot(snapshot) },
    ),
)
```

`renderLazy`, `renderOptionalLazy`, and `renderLinesLazy` preserve the existing
locale selection and fallback behavior. Values are components, so their text
is never reparsed as MiniMessage. Placeholder names are validated even when
unused, and `prefix` remains reserved.

Only referenced suppliers execute. Repeated references share one computed
value within a successful invocation, including across a list of lines. A new
invocation creates a fresh snapshot; it cannot reuse another player's value.
An explicitly blank optional message evaluates no suppliers. Suppliers run
synchronously on the rendering thread and must use already available state,
not blocking storage or network requests. Supplier exceptions propagate;
the renderer does not conceal a failed lookup with an empty component.

The existing eager methods remain unchanged for cheap, already computed values.
Tests: `LocalizedMiniMessageTest` and `LocalizedMiniMessageLazyTest`.

## ItemsAdder menu adapter

The plugin that already depends optionally on ItemsAdder supplies the native
lookup. The adapter belongs to a menu item factory, not a global service:

```kotlin
val itemFactory = PaperMenuItemFactory(
    externalItems = PaperMenuItemsAdderResolver(
        isAvailable = { Bukkit.getPluginManager().isPluginEnabled("ItemsAdder") },
        lookup = { id -> CustomStack.getInstance(id)?.itemStack },
    ),
    diagnostics = ::recordMenuDiagnostic,
)
```

Use this on the Paper primary thread. `itemsadder:pack/item` is translated to
`pack:item`; only the first slash is replaced. Bare keys retain the legacy
pass-through behavior. Foreign namespaces and unavailable providers return
`Missing` without calling the lookup. Availability is checked every time, so
the adapter does not retain a stale enabled state or cached item.

The existing factory clones resolved items before applying presentation. It
also handles provider exceptions with `external-resolver-failed` and the
configured fallback material. Consumers keep their existing optional plugin
dependency declarations and diagnostics policy.

After a core release includes this class, replace repeated resolver lambdas in
ArcBuilder's `BuildBookEditorGui`, `BuilderConstructionProjectsMenuManager`,
`BuilderBookPreviewPresentation`, and `BuilderConstructionMenuManager` with
this adapter. Do not change their published dependency to an unavailable release.

Tests: `PaperMenuItemsAdderResolverTest` and `PaperMenuItemFactoryTest` cover
lookup routing, provider absence/failure, live availability, and item ownership.
