# Configurable Paper menus design

## Goal

Provide one reusable, configuration-driven menu platform for RusCrafting Paper
plugins. Layout and presentation topology come from validated YAML while Kotlin
retains typed domain behavior, state transitions, permissions, persistence, and
side effects. The first release replaces duplicated slot constants and raw
`InventoryClickEvent` routing in every active in-house plugin that currently
owns a Bukkit chest menu.

## Scope

The platform consists of two optional arc-core artifacts:

- `ru.ruscrafting.arc:arc-core-menu:2.3.0` owns platform-neutral identifiers,
  layout definitions, YAML parsing, validation, catalog generations, pagination,
  and transient feedback state;
- `ru.ruscrafting.arc:arc-core-paper-menu:2.3.0` owns the Paper and Inventory
  Framework adapter, `ItemStack` templates, menu sessions, click dispatch,
  refresh, and viewer cleanup.

Version 2.3.0 is the first immutable release containing the menu artifacts.
Publishing that release is a separate artifact-publication operation and is not
implied by source implementation or an ordinary Git push.

The initial consumer migration covers:

- ArcFarms: main, market, enterprise, and farm-perk menus;
- ArcBuilder: build-book editor, preview decisions, and construction-site menu;
- ArcRanks: passport, contracts, perks, analytics, and weekly-kit menus;
- ARC: the existing `GuiDsl`, `ConfigGui`, board, scheduled-command, stock,
  parkour, mounts, and EliteMobs shop inventories;
- ArcDuels: duel and multiplayer menu services;
- ArcEcoJobs: every `JobsView` screen;
- ArcEvents: every event menu screen;
- ArcVotes: vote-site menu.

zMenu remains the owner of the public server navigation graph. ProxyARC,
LimboAuth, LimboFilter, third-party plugin menus, books, chat prompts, dialogs,
and non-inventory HUD surfaces are outside this migration.

No production deployment, plugin reload, restart, database write, Redis write,
or player-data mutation belongs to this work.

## Architectural boundaries

`arc-core-menu` has no Bukkit, Paper, Inventory Framework, ItemsAdder, or plugin
domain imports. It answers four questions only:

1. What semantic elements and regions does a menu declare?
2. Which concrete slots do those declarations resolve to?
3. Does a candidate satisfy the consumer's typed contract?
4. Which page and temporary feedback state should be displayed now?

`arc-core-paper-menu` depends on `arc-core-menu`, Paper 1.21.11, Adventure, and
Inventory Framework 0.12.0. It renders the already validated layout. It never
loads a domain repository, invokes arbitrary commands, evaluates permissions,
or decides whether a business action is legal.

Each consumer owns a small controller that builds a `PaperMenuContent` view
model and binds semantic action IDs to Kotlin handlers. The same binding creates
the visible `GuiItem` and its click handler. No migrated consumer may route its
own menu with a second `when (event.rawSlot)` or a slot-to-action map.

## Configuration model

Every menu root has `schema-version: 1`, `rows`, optional background and pattern,
named elements, and named regions. Consumer locale code supplies the rendered
inventory title, item names, and lore. YAML may reference presentation keys but
does not contain domain side effects.

Example:

```yaml
gui:
  layouts:
    main:
      schema-version: 1
      rows: 3
      background:
        template: background
      pattern:
        - '..F.L.M..'
        - '.........'
        - '.W..C..S.'
      legend:
        F: { element: farm }
        L: { element: lumber }
        M: { element: mine }
        W: { element: workday }
        C: { element: companies }
        S: { element: stats }
      regions:
        activities:
          elements: [farm, lumber, mine]
      templates:
        background:
          material: GRAY_STAINED_GLASS_PANE
          custom-model-data: 0
```

An element may use exactly one placement form:

- `slot: 22`;
- `position: { row: 2, column: 4 }` with zero-based coordinates;
- one character occurrence in `pattern` plus `legend`;
- `slots: [10, 11, 12]` for a decoration repeated over exact slots.

A region may use `slots`, inclusive textual ranges such as `10-16`, a union of
ranges, or `elements`. Resolved region slots retain declaration order and may
be sparse. Regions are content targets, not clickable elements by themselves.

The dot character is the pattern's empty cell. Every pattern contains exactly
`rows` strings of nine characters. Legend characters must occur, and every
non-dot character must have exactly one legend entry.

## Consumer contracts and validation

A consumer declares `MenuContract` next to its controller:

```kotlin
MenuContract(
    requiredElements = setOf("farm", "lumber", "mine", "companies"),
    optionalElements = setOf("workday", "stats"),
    requiredRegions = setOf("activities"),
)
```

Loading produces either a complete `MenuCatalog` or a non-empty list of typed
`MenuValidationIssue` values. Validation is deterministic and reports the menu,
field, offending value, and remediation-safe reason without dumping complete
operator configuration.

Validation rejects:

- unsupported schema versions;
- rows outside 1 through 6;
- negative, out-of-bounds, or duplicate occupied slots;
- malformed ranges, coordinates, patterns, and legends;
- missing required elements or regions;
- undeclared elements when the contract is closed;
- element and region references to unknown IDs;
- clickable elements with no registered handler at render time;
- handlers for unknown or non-clickable elements;
- pagination whose content, previous, and next controls overlap;
- a background without a valid portable fallback template;
- template amounts outside 1 through 99, invalid materials, negative custom
  model data, invalid namespaced external IDs, or mutually exclusive model
  fields.

Optional elements may be hidden by the view model. Required elements always
occupy their configured slots, including loading, disabled, and error states.

## Item templates and resource-pack boundary

`PaperMenuItemTemplate` supports a vanilla `Material`, amount, custom model
data, item-model key, glint override, tooltip flags, and optional external item
ID. Bundled plugin defaults use only vanilla materials and model data zero.
Tracked runtime configuration may add the verified `arc:background` external
ID or custom model data.

An optional `PaperMenuExternalItemResolver` resolves an external ID to a cloned
`ItemStack`. Resolution failure returns a typed result, emits one bounded
consumer-owned warning, and uses the validated vanilla fallback. The core module
does not depend directly on ItemsAdder.

Every item produced by `PaperMenuItemFactory` applies
`TextDecoration.ITALIC = FALSE` at the root of the display name and every lore
line, including empty, loading, disabled, error, selected, pagination, and
feedback states.

## Paper runtime API

The central entry point is `PaperMenuService`. A consumer opens a menu with a
validated definition, title component, rendered content, and lifecycle hooks:

```kotlin
val session = menus.open(
    player = player,
    definition = catalog.require("main"),
    title = locale.render("menu.title", player),
    content = content,
)
```

`PaperMenuContent` contains semantic `PaperMenuElement` instances and ordered
region entries. An element declares its ID, item stack, enabled state, accepted
click types, and optional handler. Decorations have no handler. Disabled items
remain visible and may have a local rejection-feedback handler but cannot invoke
the domain action.

`PaperMenuSession` owns one viewer, one Inventory Framework `ChestGui`, page
state, feedback state, generation token, and close callback. Its public
operations are `show`, `render`, `refresh`, `setPage`, `nextPage`,
`previousPage`, `showFeedback`, and `close`. All operations require the Paper
primary thread. Calls after close or against a stale catalog generation return a
typed no-op result rather than mutating another inventory.

Top-inventory clicks, bottom-inventory clicks, shift transfers, number-key
swaps, double-click collection, offhand swaps, drops, and drags touching the top
inventory are cancelled by default. A consumer can opt an exact element into a
bounded set of click types, but cannot globally enable item movement.

## Pagination and dynamic regions

`MenuPageState` uses zero-based pages internally and exposes one-based display
values. Page count is at least one, even for empty content. Page changes clamp
to the available range. Region entries are mapped in declaration order and
never overwrite controls or decorations.

The view model may declare a paged region with semantic previous and next
elements. The renderer automatically disables unavailable controls, but the
consumer supplies their enabled/disabled item presentation. A page change
rerenders the current content without closing and reopening the inventory.

Dynamic regions support empty, partially populated, and full states. Overflow
is rejected before any GUI mutation unless the content explicitly opts into
pagination.

## Transient feedback

`MenuFeedbackState` temporarily replaces the exact clicked element with a
consumer-supplied item and expiry tick. Repeated feedback replaces the previous
token. Closing, catalog replacement, or a later full render invalidates the old
restore. Expiry restores the newest normal state, never a captured stale
`ItemStack`.

The platform schedules feedback expiry through the consumer's arc-core
`LifecycleTaskScope`; it does not call Bukkit's scheduler directly.

## Reload and generations

`MenuCatalogRepository` stores one immutable current generation. Reload parses
and validates the complete candidate catalog first. Success atomically swaps the
catalog and increments the generation. Failure leaves the old generation and
open sessions intact.

Consumers choose one documented policy after a successful swap:

- rerender compatible open sessions from the new generation; or
- close old sessions and require reopening.

The default policy is close-on-generation-change because it cannot route an
already displayed button through a newly moved slot. Consumers with existing
safe refresh semantics may opt into rerender.

## Test contract

`arc-core-menu` tests cover every parser form, exact boundary value, error type,
pattern and range failure, required/optional contract, deterministic ordering,
pagination, catalog atomicity, generation changes, and feedback invalidation.
Property-style tables cover all 54 slots and row sizes 1 through 6.

`arc-core-paper-menu` tests use `MockBukkitTestRuntime` and real Inventory
Framework classes. They cover background fill, empty cells, semantic dispatch,
all denied inventory interactions, accepted click filtering, disabled actions,
dynamic regions, pagination, close cleanup, generation replacement, latest-state
feedback restore, external-item fallback, and final non-italic component roots.

Every migrated consumer adds:

- a layout contract test loading its real bundled YAML;
- at least one platform route test clicking a button after moving its slot in a
  temporary configuration, proving behavior follows configuration;
- invalid-overlap and out-of-range reload tests where that plugin supports
  reload;
- updated visual-preview data generated from the canonical layout rather than a
  second hand-maintained slot list.

The normal local gate excludes Testcontainers. Core uses
`./gradlew testAll publishToMavenLocal`; consumers use their repository-specific
unit, MockBukkit, package, architecture-verifier, and visual-preview commands.

## Migration and compatibility

Each consumer migration preserves visible wording, item identity, menu size,
slot composition, navigation, permissions, loading behavior, and business side
effects before making layout customizable. Existing config files are upgraded
additively: missing layout keys receive bundled defaults, operator overrides and
unknown keys survive, and explicitly invalid layouts fail the candidate reload
without partially activating it.

Old public Kotlin slot constants may remain only as deprecated compatibility
aliases when a documented external test or API consumes them. Internal render
and click paths must use semantic IDs from the menu catalog. Once all internal
callers are migrated, duplicate raw Bukkit listeners and local GUI frameworks
are removed.

The existing ARC `GuiDsl` remains as a source-compatible facade during the first
release but delegates layout, content panes, navigation, and click ownership to
`arc-core-paper-menu`. New code consumes the shared API directly.

## Delivery sequence

1. Add and verify both core modules, documentation, publication metadata, and
   consumer-architecture routing.
2. Commit and push arc-core source.
3. Publish immutable arc-core 2.3.0 only after separate owner authorization and
   green core CI.
4. Migrate simple consumers ArcVotes, ArcEvents, and ArcFarms.
5. Migrate ArcBuilder and ArcRanks stateful menus.
6. Migrate ArcEcoJobs and ArcDuels multi-screen services.
7. Adapt ARC's facade and migrate its remaining raw menus.
8. Run every consumer's complete allowed local gate, push each direct trunk,
   and inspect CI separately.
9. Produce complete visual previews for changed plugin menu families and wait
   for owner visual acceptance before any runtime activation.

