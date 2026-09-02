# Configurable Paper menus

`arc-core-menu` and `arc-core-paper-menu` separate screen composition from
gameplay. Operators can move buttons, reshape content regions, change the
background, and select safe item presentation without recompiling a plugin.
The plugin still owns authorization, state transitions, prices, persistence,
messages, and every click action.

## Ownership

| Concern | Owner |
|---------|-------|
| Rows, slots, row/column positions, patterns, regions, pagination controls, background template | YAML layout |
| Material/external item ID, amount, model data, glint, tooltip and item flags | YAML item template |
| Required semantic IDs and which IDs may be omitted | Plugin `MenuContract` |
| Title, final localized name/lore, enabled state and accepted clicks | Plugin `PaperMenuContent` |
| Permission checks and domain action | Plugin `PaperMenuClickHandler` |
| Cancellation, one-dispatch guarantee, viewer ownership, render lifecycle | `PaperMenuService` |

There is deliberately no `commands:` field. A typo or config edit must never
turn an inventory file into an arbitrary command executor.

## Layout schema

Every screen has `schema-version: 1` and one to six rows. Slot indexes are
zero-based. Position rows and columns are zero-based. Slot expressions preserve
their declared order and support scalars, inclusive ranges, and comma unions.

```yaml
gui:
  layouts:
    market:
      schema-version: 1
      rows: 6
      background: { template: background }
      pattern:
        - 'B.......X'
        - '.........'
        - '.........'
        - '.........'
        - '.........'
        - 'P...I...N'
      legend:
        B: { element: back, template: back }
        X: { element: close, template: close }
        P: { element: previous, template: previous }
        I: { element: page, template: page }
        N: { element: next, template: next }
      regions:
        offers: { slots: ['10-16', '19-25', '28-34', '37-43'] }
        navigation: { elements: [previous, page, next] }
      pagination:
        region: offers
        previous: previous
        next: next
        indicator: page

  templates:
    background:
      material: black_stained_glass_pane
      hide-tooltip: true
    close:
      material: barrier
      glint: false
    premium:
      external-item: itemsadder:rank_caesar
      fallback-material: red_stained_glass_pane
      custom-model-data: 0
      item-flags: [hide_attributes, hide_additional_tooltip]
```

`pattern` must contain exactly one nine-character line per row. `.` is empty.
A symbol that occurs once defaults to a button; repeated symbols default to a
decoration. Explicit `elements` may use exactly one of `slot`, `position`, or
`slots`. Content regions cannot collide with fixed elements. Group regions may
reference fixed elements and exist for styling or higher-level inspection, not
for dynamic item population.

## Bootstrap and reload

Declare every semantic element that code uses. Optional controls can disappear
from a layout; unknown IDs are rejected by default.

```kotlin
private val marketId = MenuId.of("market")
private val offersId = MenuRegionId.of("offers")
private val contract = MenuContract(
    requiredElements = setOf("back", "close", "previous", "next")
        .mapTo(linkedSetOf(), MenuElementId::of),
    optionalElements = setOf(MenuElementId.of("page")),
    requiredRegions = setOf(offersId),
)

config.mergeMissingFromBundled("menus.yml")
val initialLayouts = MenuLayoutParser.require(
    config,
    "gui.layouts",
    mapOf(marketId to contract),
)
var itemTemplates = PaperMenuItemTemplateParser.require(config, "gui.templates")
val layouts = MenuCatalogRepository(initialLayouts)

fun reloadMenus(): MenuCatalogReplaceResult {
    val templateCandidate = PaperMenuItemTemplateParser.parse(config, "gui.templates")
    val layoutCandidate = MenuLayoutParser.parse(config, "gui.layouts", mapOf(marketId to contract))
    if (templateCandidate is PaperMenuItemTemplateLoadResult.Rejected) {
        throw PaperMenuItemTemplateException(templateCandidate.issues)
    }
    if (layoutCandidate is MenuCatalogLoadResult.Rejected) {
        return layouts.replace(layoutCandidate) // exact old generation remains active
    }
    itemTemplates = (templateCandidate as PaperMenuItemTemplateLoadResult.Loaded).templates
    return layouts.replace(layoutCandidate)
}
```

Run reload on the Paper primary thread. Because sessions and replacement run on
that thread, validated templates and the new layout generation become visible
without a partial render. A session opened against an older generation keeps
cancelling inventory mutations but stops invoking handlers until the plugin
refreshes or reopens it.

## Rendering and semantic clicks

Inventory Framework classes do not appear in consumer APIs. The content
supplier is called again for refresh and delayed feedback restoration, so it
must return the latest domain state.

```kotlin
val menuService = PaperMenuService(plugin, layouts, BukkitTaskScheduler(plugin))

fun openMarket(player: Player) = menuService.open(player, marketId) {
    val factory = PaperMenuItemFactory(itemsAdderResolver, log::warn)
    PaperMenuContent(
        title = messages.component("market.title", "<gold>Рынок"),
        background = factory.create(itemTemplates.getValue("background"), Component.empty(), emptyList()),
        elements = mapOf(
            MenuElementId.of("back") to PaperMenuEntry(
                item = backItem(player),
                onClick = PaperMenuClickHandler { openParent(it.player) },
            ),
            MenuElementId.of("close") to PaperMenuEntry(
                item = closeItem(player),
                onClick = PaperMenuClickHandler { it.session.close() },
            ),
            MenuElementId.of("previous") to PaperMenuEntry(
                item = previousItem(player),
                onClick = PaperMenuClickHandler { it.session.previousPage() },
            ),
            MenuElementId.of("next") to PaperMenuEntry(
                item = nextItem(player),
                onClick = PaperMenuClickHandler { it.session.nextPage() },
            ),
        ),
        regions = mapOf(
            offersId to currentOffers(player).map { offer ->
                PaperMenuEntry(offerItem(offer)) { context -> buy(context.player, offer.id) }
            },
        ),
    )
}
```

Use named arguments for non-default entry fields. Final item names and every
lore line receive an explicit non-italic root. External providers such as
ItemsAdder stay behind `PaperMenuExternalItemResolver`; resolved stacks are
cloned, and provider failure produces a configured vanilla fallback plus a
bounded diagnostic key.

`showFeedback(element, delayTicks, item)` temporarily replaces a fixed item.
Only the newest token may expire. A full refresh, catalog replacement, or close
invalidates delayed restoration, and restoration asks the content supplier for
the latest normal item rather than retaining a stale `ItemStack`.

Close `PaperMenuService` during plugin shutdown. Opening another menu for the
same player closes the old session exactly once.

## Verification

The platform tests use IF 0.12.0 with the shared MockBukkit runtime and cover
layout failures, range order, collisions, catalog replacement, item metadata,
background priority, click filtering, top/bottom cancellation, drag safety,
viewer ownership, duplicate dispatch, rerendered handlers, pagination,
feedback tokens, stale generations, replacement, and idempotent close.

```bash
./gradlew :arc-core-menu:test :arc-core-paper-menu:test
python3 -m unittest scripts.tests.test_verify_consumer_architecture
./gradlew stageRelease -PreleaseVersion=<version> -PpublicationGroup=ru.ruscrafting.arc
```
