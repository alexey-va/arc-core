package ru.arc.paper.nameplate

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

/**
 * Resolves a visible viewer's content from cached gameplay state on the primary thread.
 * Null hides the plate. Equal components share one private-audience display; at most
 * sixteen distinct components may be visible for one target. Excess variants stay hidden.
 * Implementations must remain bounded and must not access persistence or mutate gameplay.
 */
fun interface PaperNameplateViewerContent {
    fun render(viewer: Player, target: Player, base: Component): Component?
}
