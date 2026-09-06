# Measured component text layout

`ru.arc.text.ComponentTextLayout` wraps resolved Adventure text into GUI-pixel
lines and pads each line to the same advance width. A renderer that centers
every line then preserves the selected left, center or right text edge.

```kotlin
val layout = ComponentTextLayout(verifiedGlyphWidths, Key.key("arc:dialog_alignment"))
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

The separate spacer font must define U+E000 through U+E009 as `space` provider
advances 1, 2, 4, 8, 16, 32, 64, 128, 256 and 512. Spacers explicitly disable
decorations and carry no hover/click events. Each output line has the requested
advance width; italic overhang/shadows are visual effects outside that contract.

Paper 1.21.11 plain-message widgets subtract 4px of internal padding on each
side: pass `bodyWidth - 8`. There is no native left/right body property. ARC's
`ru.arc.gui.DialogTextLayout` owns this Paper adapter and a generated server-pack
metric snapshot; its `/arc dialogdemo alignment` page compares all three modes.
Fonts replaced by a client-side resource pack or forced Unicode settings need
their own verified metrics. Packet-level checks do not establish native visual
alignment at different GUI scales.

Focused check: `./gradlew :arc-core:test --tests ru.arc.text.ComponentTextLayoutTest`.
Full local gate: `./gradlew testAll`; storage integration remains a separate CI gate.
