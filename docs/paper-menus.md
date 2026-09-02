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
| Material/external item ID, amount, model data, glint, tooltip, item flags, name and lore composition | YAML item template |
| Required semantic IDs and which IDs may be omitted | Plugin `MenuContract` |
| Available safe value/flag/repeat tags | Plugin `PaperMenuTextContract` |
| Localized tag values, enabled state and accepted clicks | Plugin `PaperMenuContent` |
| Permission checks and domain action | Plugin `PaperMenuClickHandler` |
| Cancellation, one-dispatch guarantee, viewer ownership, render lifecycle | `PaperMenuRuntime` |

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
      # Available value tags: <player>, <price>, <balance>, <action>
      # Available flags: affordable, owned
      # Available repeats: effects(<effect>, <level>)
      name: '<gold><player>'
      lore:
        - '<gray>Цена: <price>'
        - { text: '<green><action>', when: affordable }
        - { text: '<red>Недостаточно средств', unless: affordable }
        - { repeat: effects, text: '<dark_gray>• <effect> <level>' }
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
val initial = PaperMenuConfigurationParser.require(
    config,
    "gui.layouts",
    "gui.templates",
    mapOf(marketId to contract),
    requiredTemplates = setOf("feedback-error"),
)
val menus = PaperMenuRuntime(plugin, BukkitTaskScheduler(plugin), initial)

fun reloadMenus() {
    val candidate = PaperMenuConfigurationParser.require(
        config,
        "gui.layouts",
        "gui.templates",
        mapOf(marketId to contract),
        requiredTemplates = setOf("feedback-error"),
    )
    menus.replace(candidate)
}
```

Parse the complete candidate before the primary-thread publication step. A
missing referenced template rejects the whole candidate. `replace` atomically
publishes layouts and templates, increments the catalog generation, and closes
old viewers; a player can therefore never observe a layout from one generation
with item templates from another.

## Rendering and semantic clicks

Inventory Framework classes do not appear in consumer APIs. The content
supplier is called again for refresh and delayed feedback restoration, so it
must return the latest domain state.

```kotlin
fun openMarket(player: Player) = menus.open(player, marketId) {
    val factory = PaperMenuItemFactory(itemsAdderResolver, log::warn)
    val configuration = menus.current()
    PaperMenuContent(
        title = messages.component("market.title", "<gold>Рынок"),
        background = factory.create(configuration.templates.getValue("background"), Component.empty(), emptyList()),
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

## Configured text and tags

Item `name` and `lore` are MiniMessage templates. Code declares the tags it can
actually provide; a candidate using an unknown value, flag, repeat, or repeat
row value is rejected before publication. Component placeholders are inserted
through Adventure's `Placeholder.component`, so a player name such as
`<red>Alex` stays literal and cannot inject formatting.

Lore accepts a plain string or a mapping:

- `text` is one configured lore line;
- `when` and `unless` accept one flag or a list of flags;
- `repeat` expands the line once per domain row and gives that line the row's
  declared tags.

The plugin may supply already-localized components as tags. This keeps one
layout/presentation composition in `config.yml` while wording remains in
`lang/ru.yml`, `lang/en.yml`, and other locale configs. Formatting, line order,
conditional branches, and repeated-row shape remain operator-owned.

```kotlin
val textContracts = mapOf(
    "premium" to PaperMenuTextContract(
        values = setOf("player", "price", "balance", "action"),
        flags = setOf("affordable", "owned"),
        repeats = mapOf("effects" to setOf("effect", "level")),
    ),
)
val configuration = PaperMenuConfigurationParser.require(
    config,
    "gui.layouts",
    "gui.templates",
    contracts,
    textContracts = textContracts,
)
val item = PaperMenuItemFactory().create(
    configuration.templates.getValue("premium"),
    PaperMenuItemRenderContext(
        values = mapOf("player" to Component.text(player.name), "price" to priceComponent),
        flags = buildSet { if (affordable) add("affordable") },
        repeats = mapOf("effects" to effects.map { mapOf("effect" to it.name, "level" to it.level) }),
    ),
)
```

Every click is cancelled before dispatch. Shift-left and shift-right may be
listed explicitly in `acceptedClicks` for controls that need modifier behavior;
the item still cannot move. Number-key swaps, double-click collection, drop,
creative clone, offhand swap, outside clicks, and drag paths touching the top
inventory remain non-dispatching.

`showFeedback(element, delayTicks, item)` temporarily replaces a fixed item.
Only the newest token may expire. A full refresh, catalog replacement, or close
invalidates delayed restoration, and restoration asks the content supplier for
the latest normal item rather than retaining a stale `ItemStack`.

Close `PaperMenuRuntime` during plugin shutdown. Opening another menu for the
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
