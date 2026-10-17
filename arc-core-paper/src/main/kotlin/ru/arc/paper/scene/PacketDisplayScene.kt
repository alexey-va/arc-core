package ru.arc.paper.scene

import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.display.PacketDisplayCaptureParticipant
import ru.arc.paper.display.PacketDisplayViewer

/** Refreshes cached spatial presentation, never persistent/domain plans. Runs every five capture ticks. */
fun interface SceneRefreshController { fun refresh() }

/** Borrowed display owner. This is the sole keyed visual reconciler, not a second packet transport. */
class PacketDisplayScene<K : Any>(private val displays: PaperPacketDisplays) : AutoCloseable {
    private class Entry<K : Any>(val handle: PacketDisplay, var part: ScenePart<K>)
    private val entries = linkedMapOf<K, Entry<K>>()
    private var audience: SceneAudience? = null
    private var closed = false
    private var hidden = false
    private var refreshController: SceneRefreshController? = null
    private var refreshRegion: SceneRefreshRegion? = null
    private var refreshTicks = 0
    private val participant = object : PacketDisplayCaptureParticipant {
        override fun capture(viewers: List<PacketDisplayViewer>) {
            val region = refreshRegion
            if (region != null && viewers.none(region::observes)) return
            val gate = audience
            if (region == null && gate != null && entries.isNotEmpty() && viewers.none { viewer ->
                gate.allows(viewer.player) && entries.values.any { entry ->
                    val location = entry.part.pose.location()
                    val range = entry.part.style.viewRange * 64.0
                    location.world?.uid == viewer.worldId &&
                        (location.x - viewer.x) * (location.x - viewer.x) + (location.y - viewer.y) * (location.y - viewer.y) +
                        (location.z - viewer.z) * (location.z - viewer.z) <= range * range
                }
            }) return
            if (++refreshTicks % 5 == 0) try { refreshController?.refresh() } catch (failure: Exception) {
                runCatching { reset() }.exceptionOrNull()?.let(failure::addSuppressed)
                displays.captureFailure(failure)
            }
        }
        override fun ownerClosed() = close()
    }

    /** Opt-in idle placement refresh on the existing display capture; manual scenes install none. */
    fun refreshWith(controller: SceneRefreshController) {
        refreshRegion = null
        installRefresh(controller)
    }

    fun refreshWith(region: SceneRefreshRegion, controller: SceneRefreshController) {
        refreshRegion = region
        installRefresh(controller)
    }

    private fun installRefresh(controller: SceneRefreshController) {
        displays.checkThread(); check(!closed)
        refreshController = controller; refreshTicks = 0; displays.participate(participant)
    }

    fun apply(frame: SceneFrame<K>) {
        displays.checkThread()
        check(!closed) { "Scene is closed" }
        val wanted = frame.parts.mapTo(hashSetOf()) { it.key }
        (entries.keys - wanted).forEach { entries.remove(it)?.handle?.remove() }
        try {
            frame.parts.forEach { part ->
                var entry = entries[part.key]
                if (entry != null && (!entry.handle.isValid || entry.part::class != part::class)) {
                    entry.handle.remove(); entries.remove(part.key); entry = null
                }
                val created = entry == null
                val current = entry ?: Entry(spawn(part), part).also { entries[part.key] = it }
                val old = current.part
                val handle = current.handle
                if (created || old.style != part.style) configure(handle, part.style)
                if (created || old.pose != part.pose) {
                    handle.transformation = part.pose.transformation()
                    handle.teleportDuration = frame.teleportTicks
                    if (!created && handle.location != part.pose.location()) {
                        handle.teleport(part.pose.location())
                    }
                }
                when (part) {
                    is ScenePart.Text -> (handle as PacketTextDisplay).also { text ->
                        if (created || (old as ScenePart.Text).text != part.text) text.text(part.text)
                        if (created || (old as ScenePart.Text).textStyle != part.textStyle) part.textStyle.let {
                            text.lineWidth = it.lineWidth; text.backgroundColor = it.background; text.textOpacity = it.opacity
                            text.isShadowed = it.shadowed; text.isSeeThrough = it.seeThrough
                            text.isDefaultBackground = it.defaultBackground; text.alignment = it.alignment
                        }
                    }
                    is ScenePart.Item -> (handle as PacketItemDisplay).also { item ->
                        if (!created && !(old as ScenePart.Item).sameContent(part)) item.itemStack = part.item()
                        if (created || (old as ScenePart.Item).itemTransform != part.itemTransform) item.itemDisplayTransform = part.itemTransform
                    }
                    is ScenePart.Block -> if (!created && !(old as ScenePart.Block).sameContent(part))
                        (handle as PacketBlockDisplay).blockData = part.block()
                }
                if (created || hidden || old.visible != part.visible || audience != frame.audience) visibility(handle, part.visible, frame.audience)
                current.part = part
            }
            audience = frame.audience
            hidden = false
        } catch (failure: Exception) {
            runCatching { reset() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** Removes current handles but leaves this scene reusable for a different source/session. */
    fun reset() {
        displays.checkThread()
        val removed = entries.values.toList()
        entries.clear(); audience = null; hidden = false
        var failure: Exception? = null
        removed.forEach { try { it.handle.remove() } catch (e: Exception) { if (failure == null) failure = e else failure!!.addSuppressed(e) } }
        failure?.let { throw it }
    }
    fun hide() {
        displays.checkThread()
        val gate = audience ?: return
        if (hidden) return
        entries.values.forEach { visibility(it.handle, false, gate) }; hidden = true
    }
    fun isValid(): Boolean = entries.values.all { it.handle.isValid }
    fun entityId(key: K): Int? = entries[key]?.handle?.entityId
    override fun close() {
        if (closed) return
        closed = true; refreshController = null; refreshRegion = null; displays.remove(participant); reset()
    }

    private fun spawn(part: ScenePart<K>): PacketDisplay = when (part) {
        is ScenePart.Text -> displays.spawnText(part.pose.location(), part.text)
        is ScenePart.Item -> displays.spawnItem(part.pose.location(), part.item())
        is ScenePart.Block -> displays.spawnBlock(part.pose.location(), part.block())
    }
    private fun visibility(handle: PacketDisplay, visible: Boolean, gate: SceneAudience) {
        if (audience != gate) handle.clearAudienceOverrides()
        handle.audienceGroup = gate.group
        // A scene's effective audience is one stable gate, including its private recipient. This
        // avoids storing Player objects and makes permission changes affect both visual and input.
        handle.isVisibleByDefault = visible && gate is SceneAudience.Public
        if (gate is SceneAudience.Private) {
            if (visible) handle.showTo(gate.viewerId) else handle.hideFrom(gate.viewerId)
        }
    }
    private fun configure(handle: PacketDisplay, s: SceneDisplayStyle) {
        handle.billboard = s.billboard; handle.brightness = s.brightness
        handle.interpolationDelay = s.interpolationDelay; handle.interpolationDuration = s.interpolationDuration
        handle.viewRange = s.viewRange; handle.shadowRadius = s.shadowRadius; handle.shadowStrength = s.shadowStrength
        handle.displayWidth = s.displayWidth; handle.displayHeight = s.displayHeight
        handle.isGlowing = s.glowing; handle.glowColorOverride = s.glowColor
    }
}
