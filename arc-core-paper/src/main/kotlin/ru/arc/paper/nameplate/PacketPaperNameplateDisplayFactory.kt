package ru.arc.paper.nameplate

import net.kyori.adventure.text.Component
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

/**
 * Opt-in client-only nameplates using the existing visual packet budget and attachment queue.
 *
 * Construct and use on Paper's primary thread. [owner] is borrowed: closing a nameplate removes
 * only its handle. The caller must keep the owner alive until its nameplates are closed, then
 * track [PaperPacketDisplays.closeAsync] when retiring the owner to observe client cleanup.
 * Styling and explicit per-viewer visibility match [NativePaperNameplateDisplayFactory].
 */
class PacketPaperNameplateDisplayFactory(
    private val owner: PaperPacketDisplays,
    private val options: PaperNameplateOptions,
) : PaperNameplateDisplayFactory {
    private var nativeNameTagAnchor = false

    companion object {
        /**
         * Anchors at the 1.21.11 native name-tag point: current player head plus 0.5 world blocks.
         * [PaperNameplateOptions.verticalOffset] is then a local screen-plane row offset under
         * CENTER billboarding. Follows at the borrowed owner's cadence with one-tick position
         * interpolation and stays current during AFK/rear-view pauses. Privacy and normal packet
         * admission budgets remain in force; no passenger prediction or server entity is involved.
         */
        @JvmStatic
        fun atNativeNameTag(owner: PaperPacketDisplays, options: PaperNameplateOptions): PacketPaperNameplateDisplayFactory =
            PacketPaperNameplateDisplayFactory(owner, options).also { it.nativeNameTagAnchor = true }
    }

    override fun create(target: Player, content: Component): PaperNameplateDisplay {
        val display = owner.spawnText(target.location, content)
        display.billboard = Display.Billboard.CENTER
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.lineWidth = options.lineWidth
        display.viewRange = options.viewRange
        display.transformation = Transformation(
            Vector3f(0.0F, options.verticalOffset, 0.0F),
            Quaternionf(),
            Vector3f(options.scale, options.scale, options.scale),
            Quaternionf(),
        )
        display.isShadowed = options.shadowed
        display.brightness = options.brightness
        display.isSeeThrough = false
        display.isDefaultBackground = false
        display.backgroundColor = options.backgroundColor
        display.isVisibleByDefault = false
        if (nativeNameTagAnchor) {
            display.pauseWhenNotObserved = false
            display.teleportDuration = 1
            display.followPlayerHead(target, Vector(0.0, 0.5, 0.0))
        } else {
            display.attachTo(target)
        }
        return PacketPaperNameplateDisplay(target.uniqueId, target.world.uid, target.entityId, display)
    }
}

private class PacketPaperNameplateDisplay(
    override val targetId: UUID,
    private val worldId: UUID,
    private val entityId: Int,
    private val display: PacketTextDisplay,
) : PaperNameplateDisplay {
    override fun isAttachedTo(target: Player): Boolean =
        display.isValid && target.isValid && !target.isDead &&
            target.uniqueId == targetId && target.world.uid == worldId && target.entityId == entityId &&
            display.attachmentPlayer === target

    override fun update(content: Component) {
        display.text(content)
    }

    override fun show(viewer: Player) {
        display.showTo(viewer)
    }

    override fun hide(viewer: Player) {
        display.hideFrom(viewer)
    }

    override fun close() {
        display.remove()
    }
}
