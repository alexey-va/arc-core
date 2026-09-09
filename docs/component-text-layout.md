# Measured component text layout

`ru.arc.text.PixelSpacing` creates pixel padding independently of ARC, Paper,
ItemsAdder APIs or a specific menu. Any plugin using Adventure can reuse it:

```kotlin
// Existing RusCrafting ItemsAdder font: no additional assets to install.
val spacing = PixelSpacing(Key.key("minecraft:default"), 0xF0F01)
val label = Component.empty().append(Component.text("Name"))
    .append(spacing.padding(23)).append(Component.text("Value"))
```

Supply the font key and the first of ten consecutive code points with advances
1,2,4,..512. `padding(0..1023)` returns a Component with explicit font and disabled
decorations. Keep its parent free of click/hover/insertion events; put interactions
on the text siblings. Spacing is an advance, not text measurement or a universal
screen layout: each renderer still owns its content area and GUI scaling.

`ru.arc.text.ComponentTextLayout` wraps resolved Adventure text into GUI-pixel
lines and pads each line to the same advance width. A renderer that centers
every line then preserves the selected left, center or right text edge.

```kotlin
val layout = ComponentTextLayout(verifiedGlyphWidths, spacing)
when (val result = layout.layout(component, 392, TextAlignment.LEFT)) {
    is TextLayoutResult.Aligned -> render(result.component)
    is TextLayoutResult.Unsupported -> render(component) // readable native fallback
}
```

The consumer supplies immutable metrics for the exact loaded resource pack and
client font options. Width means glyph advance, including the bold offset, not
character count or image bounding-box width. Unsupported fonts, unresolved
translation/keybind components and right-to-left text return a typed failure;
resolve client-dependent content before calling when exact layout is required.
The engine preserves effective text styles and interactions, explicit blank
paragraphs and supplementary Unicode characters. It wraps at spaces and splits
oversized words; input/depth/line limits bound work. Callers handle failures as a
whole, never by mixing measured and unmeasured fragments.

The 2.7.0 constructor `(GlyphWidths, Key)` remains binary-compatible and selects
legacy U+E000..U+E009 in the supplied font. New consumers pass `PixelSpacing`
explicitly. RusCrafting uses ItemsAdder's existing U+F0F01..U+F0F0A in
`minecraft:default`; do not create a duplicate font. Each output line has the
requested advance width; italic overhang/shadows are outside that contract.

Consumers depend on `ru.ruscrafting.arc:arc-core` and import `ru.arc.text.*`;
they do not depend on the ARC plugin or copy its padding loop. Connect this API
when editing a consumer's text renderer; publishing core does not automatically
rewrite independent third-party plugin menus or their native layout rules.

Paper 1.21.11 plain-message widgets subtract 4px of internal padding on each
side: pass `bodyWidth - 8`. There is no native left/right body property.
`arc-core-paper-menu` owns the typed `ru.arc.paper.menu.DialogTextLayout` adapter
and generated server-pack metric snapshot; ARC's `/arc dialogdemo alignment` page
is a consumer that compares all three modes.
Fonts replaced by a client-side resource pack or forced Unicode settings need
their own verified metrics. Packet-level checks do not establish native visual
alignment at different GUI scales.

Focused check: `./gradlew :arc-core:test --tests ru.arc.text.ComponentTextLayoutTest`.
Full local gate: `./gradlew testAll`; storage integration remains a separate CI gate.
