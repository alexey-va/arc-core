# Configurable Paper menus and native dialogs

`arc-core-menu` and `arc-core-paper-menu` separate screen composition from
gameplay. Operators can move buttons, reshape content regions, change the
background, and select safe item presentation without recompiling a plugin.
The plugin still owns authorization, state transitions, prices, persistence,
messages, and every click action.

Native Minecraft dialogs use the same boundary: feature configuration owns the
visible text and composition, feature code supplies current domain rows and
typed handlers, and `PaperDialogRuntime` owns Paper event dispatch and session
lifecycle. Dialogs are intentionally modeled in `arc-core-paper-menu` because
their public API contains Paper and Adventure presentation types.

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
| Native dialog body, inputs, columns and buttons | Consumer configuration rendered into `PaperDialogScreen` |
| Dialog action/input identifiers and handlers | Consumer code using `PaperDialogActionId` and `PaperDialogInputId` |
| Dialog nonce, player ownership, one-shot dispatch and listener cleanup | `PaperDialogRuntime` |

There is deliberately no `commands:` field. A typo or config edit must never
turn an inventory file into an arbitrary command executor.

## Native Paper dialogs

Create one runtime per plugin lifecycle and close it on disable. Visible text
should come from the consumer's validated locale or module configuration; insert
player and domain values as Adventure component placeholders before building the
screen.

```kotlin
val dialogs = PaperDialogRuntime(plugin)
val nameInput = PaperDialogInputId.of("land_name")

fun openCreate(player: Player) {
    dialogs.open(
        player,
        PaperDialogScreen(
            title = messages.component("lands.create.title"),
            body = listOf(PaperDialogBody(messages.component("lands.create.body"))),
            inputs = listOf(
                PaperDialogTextInput(nameInput, messages.component("lands.create.input"), maxLength = 32),
            ),
            buttons = listOf(
                PaperDialogButton(
                    id = PaperDialogActionId.of("create"),
                    label = messages.component("common.continue"),
                    onClick = PaperDialogClickHandler { context ->
                        createLand(context.player, context.text(nameInput).orEmpty())
                    },
                ),
            ),
        ),
    )
}

override fun onDisable() = dialogs.close()
```

The runtime registers one `PlayerCustomClickEvent` listener. Opening a new
screen replaces the player's previous registration; the action key contains a
fresh nonce, is matched to that player, and is consumed before its handler runs.
Old, foreign, duplicate, and post-shutdown clicks therefore do nothing. The
runtime keeps the visible dialog open for normal navigation (`afterAction=NONE`)
and closes it only for `closeDialogBeforeAction`. Input and action IDs are bounded and validated
before a native dialog is shown.

Escape and the native Back footer follow actual visits, not the consumer's
hard-coded hierarchy. A root with no previous visit closes. Same-page refreshes
and asynchronous replacements do not add history steps; history is bounded to
64 visits per player. Direct player commands clear the old flow. Call
`beginFlow(player)` for programmatic command/hotkey roots (calls from an existing
dialog action deliberately preserve the flow). The four-argument `open` accepts
a reopener for fresh domain data and an `onDismiss` callback to invalidate async
work on Back and Close. An explicitly closing footer closes the complete flow.

Minecraft 1.21.11's `DialogScreen.onClose()` forces `CLOSE`, even when
`after_action=none`: unlike normal buttons, Escape followed by a server reply
can recenter the cursor. This is a client limitation, not a server cursor API.
An inline client `show_dialog` avoids that close but does not acknowledge the
navigation to the server and is not a safe replacement for dynamic menus with
pending updates. Do not claim physical cursor preservation from server tests.

Do not use per-button Paper callback registrations for lifecycle-owned plugin
dialogs. Do not put raw commands into dialog YAML. Re-resolve and authorize the
domain target inside every handler because the state may have changed after the
screen was rendered.

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

Menus whose domain is an actual container (for example, per-player dungeon
loot) may opt one entry into `PaperMenuTransferHandler`. The handler reserves
or removes the exact domain item first and returns `ALLOW` only on success.
The runtime then uncancels only top-slot pickup actions; placement, cursor
swaps, drops, hotbar/offhand swaps, creative cloning, bottom shift-clicks and
all top-touching drags stay blocked. Ordinary menu entries never inherit this
exception.

`showFeedback(element, delayTicks, item)` temporarily replaces a fixed item.
Only the newest token may expire. A full refresh, catalog replacement, or close
invalidates delayed restoration, and restoration asks the content supplier for
the latest normal item rather than retaining a stale `ItemStack`.

`refresh()` is diff-aware: an unchanged render returns `UNCHANGED`, preserves
the existing Inventory Framework item/handler identity and sends no inventory
redraw. Changed items are patched only in their physical slots. The stable
handler resolves the latest entry at click time, so an unchanged-looking button
still receives current domain behavior. A title or background change is the
only refresh path that requires a full render.

Live menus should call `requestRefresh()` for timer, progress and click-driven
updates. Requests within the same tick coalesce into one next-tick refresh;
this also keeps mutation out of the active `InventoryClickEvent` dispatch. Use
immediate `refresh()` only when the caller requires the new item state before
returning.

### Migrating slot-oriented renderers

`PaperMenuFrame` lets an existing renderer keep its incremental `setItem`
composition while moving inventory ownership, click safety, generation checks,
and refreshes into `PaperMenuRuntime`. A physical frame accepts only slots that
the current layout declares as fixed elements or content regions. A logical
region frame addresses positions `0..region.size-1`, which is useful when YAML
reorders a complete legacy grid. Sparse region positions are padded with a
disabled copy of the background so later entries never shift to another slot.

```kotlin
menus.open(player, marketId) {
    val frame = menus.physicalFrame(marketId, title(player), backgroundItem())
    frame.setItem(layout.slot("back").index, backItem(player))
    offers.forEachIndexed { index, offer ->
        frame.setItem(layout.region("offers")[index].index, offerItem(offer))
    }
    frame.content { rawSlot, context -> handleClick(context.player, rawSlot) }
}
```

Frames are render-local and defensively clone their items. They are not a
reason to keep raw Bukkit listeners: the handler is attached to semantic
runtime entries and receives only safe clicks from the owning viewer.

Close `PaperMenuRuntime` during plugin shutdown. Opening another menu for the
same player closes the old session exactly once.

## Verification

The platform tests use IF 0.12.0 with the shared MockBukkit runtime and cover
layout failures, range order, collisions, catalog replacement, item metadata,
background priority, click filtering, top/bottom cancellation, drag safety,
viewer ownership, duplicate dispatch, rerendered handlers, pagination,
feedback tokens, no-op and slot-diff refresh, refresh/click races, next-tick
coalescing, stale generations, replacement, and idempotent close.

```bash
./gradlew :arc-core-menu:test :arc-core-paper-menu:test
python3 -m unittest scripts.tests.test_verify_consumer_architecture
./gradlew stageRelease -PreleaseVersion=<version> -PpublicationGroup=ru.ruscrafting.arc
```

## Cloud chest storage

Use `PaperMenuRuntime.openStorage(player, menu, region, storage, content)` for a
chest backed by domain data, such as a player store or personal dungeon loot.
This variant uses a native Bukkit inventory and does not attach IF metadata to
real items. Keep `PaperMenuContent` for ordinary action menus.

`PaperCloudStorage.snapshot()` returns detached items, preserving each logical
slot and using null for empty cells. `compareAndSet(expected, replacement)`
must synchronously validate the complete expected state and atomically apply all
replacements or change nothing. Both calls run on the Paper primary thread.
The consumer owns access checks, item restrictions, persistence and recovery;
this UI contract does not add cross-server locking or disk durability. A true
result is the commit boundary, immediately followed by player slot/cursor
updates. Backend exceptions disable the session without retrying an uncertain
commit. A stale snapshot or rejected edit transfers nothing.

Left click takes a stack to the cursor; right click takes half. With deposits
enabled, cursor placement, merge and swap also work. Shift moves merge stacks
before filling empty slots and preserve any remainder. Player destinations use
vanilla reverse hotbar/main-inventory order. Bottom clicks and bottom-only drags
remain native, allowing a player to choose a destination slot. Top drag, hotbar
swap, drop, clone and cross-inventory double-click collection are blocked.

`PaperCloudStorageContent` supplies title and `allowDeposits`, optional
`slotOrder` (a complete permutation of the configured region), non-transferable
padding `decorations`, and semantic navigation `buttons`. Decorations may only
use logical offsets beyond the backing snapshot. The optional background fills
slots outside the storage region; empty storage cells stay empty. Navigation
runs next tick through the session task scope. Runtime replacement, close, quit
and plugin shutdown end the session and cancel pending navigation. Paper owns
normal cursor return on inventory close.

Verification: `PaperCloudStorageTest` covers cursor, shift, metadata, rejected and
stale edits, decorations, native bottom interactions and lifecycle. MockBukkit
dispatches events but does not execute the vanilla client/server click algorithm;
these tests prove the shared handler's decisions and commits, not client packets.
