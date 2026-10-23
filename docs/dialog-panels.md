# Modular native dialog panels

`arc-core-paper-menu` owns these pure compositions. Plugins supply resolved
Adventure components, domain values and their existing pack opt-in. The API
does not install a pack, infer state, change a dialog runtime or restyle other
menus. Pass explicit arguments across plugin classloaders.

```kotlin
val spec = DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 468, 1, true)
val body = DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS, spec)
val groups = DialogTables.groupedFactsBody(
    listOf(DialogTables.FactsGroup(walletTitle, walletFacts),
        DialogTables.FactsGroup(territoryTitle, territoryFacts)),
    DialogTables.PanelSpec(DialogTables.PanelSkin.COCOA, 468, 2, true),
)
```

Facts are `Pair<Component, Component>` values. The caller owns punctuation and
wording; the renderer preserves each label and value. `ROWS` makes compact
consecutive columns, `METRICS` places labels above values, and `CAPSULES` keeps
individual pairs in separate cards. Groups supply explicit headings and use
centred title tabs. An incomplete final grid row is centred.

`maxWidth` is 9–1024 GUI pixels including native padding and wrap headroom;
`columns` is 1–8. `fitContent=true` shrinks the composition up to that cap.
False fills the available grid width while keeping each label/value block
together. Side insets are 12px, pair and horizontal grid gaps are 8px, and rows
follow the native 9px rhythm. Cells wrap independently. Long prose cannot
reduce every short label to a few pixels merely because its text is longer.

Complete presets are `NAVY` (thin), `PLUM` (double), `EVERGREEN` (stepped),
`COCOA` (paired brackets) and `ORNATE` (warm corner plates). They are separate
from the six `Frame` tooltip styles. The original four-argument
`filledBody(rows, headers, width, columnWeights)` keeps v03; its additive
five-argument overload selects a `PanelSkin` for the same weighted columns.

Compact compositions accept at most 64 facts, including facts in groups.
The complete extent must fit 194 native rows including borders, tabs and gaps;
the old weighted panel supports 192 content rows plus two borders. Unknown
metrics, insufficient width and excessive height return readable unframed
text containing every title, label and value. Large datasets should use the
caller's existing pagination. No content is silently dropped.

Every panel background is emitted before all foreground, on the first native
baseline. Row-bank glyphs use negative ascent to reach later rows without
covering text shadows. A 2px reserve beyond the final panel advance accounts
for the client's immediate glyph-width check before a negative spacer.
Background strings remain split at 8192 codepoints to fit the 65535-byte NBT
`StringTag.writeUTF` limit. Native foreground retains caller styles/shadows;
missing prose color becomes warm light `#E8DFD2`.

## Existing library elements

`DialogDecoration.library()` selects `arc:dialog_ui` explicitly. Its helpers
reuse registered artwork without feature-local codepoint constants:

- `listBody(entries, width)` accepts `ListEntry(Marker, text)`, where marker is
  `BULLET`, `PENDING` or `COMPLETE`. Continuations retain the hanging indent.
  Limit: 64 entries and 192 wrapped rows before plain fallback.
- `stepsBody(steps, width)` accepts explicit `COMPLETE`, `CURRENT` and `PENDING`
  states. Existing rail glyphs join up to 32 steps; progression is not inferred.
- `progressBody(label, current, total, value, width, segments, color)` requires
  finite nonnegative current and positive total, with 1–32 requested segments.
  Null numbers retain textual value without a fabricated empty bar. Progress
  above target fills the bar and retains the exact supplied value.
- `headingBody(title, width)` places a measured title between complete divider
  caps with 12px outer insets and 12px title gaps. A title that cannot fit stays
  readable native text. The additive `headingBody(title, width, HeadingStyle.DIAMOND)`
  uses native gold line/diamond wings for ornate panels; the default stays plain.
- `DialogDecoration.stateBadge(label, tone)` preserves explicit textual state
  with a readable accent; color alone never defines the state.
- `DialogDecoration.fieldLabel(icon, label)` preserves the native label after
  one white icon in a 12px slot. Typed icons cover player, rank, homes, lands,
  world, coordinates, chat, online, balance and tokens. All ten use the same
  native 9px field-icon font; ordinary coin/token assets remain separate.
  A caller may show amount-only values when the field label already names and
  illustrates the currency, retaining informative values in its plain fallback.

The caller retains its plain fallback when custom fonts are disabled. Ordinary
buttons stay native. Source checks and offline provider renders do not establish
the installed pack's appearance in an ordinary Minecraft client.
