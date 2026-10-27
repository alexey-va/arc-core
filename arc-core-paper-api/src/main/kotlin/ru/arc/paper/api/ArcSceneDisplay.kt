package ru.arc.paper.api

import java.util.Collections
import java.util.UUID

/** Detached native SRT components. Quaternion order is x, y, z, w. */
class ArcSceneVector3(val x: Float, val y: Float, val z: Float) {
    init { require(listOf(x, y, z).all(Float::isFinite)) { "Scene vector must be finite" } }
}

class ArcSceneQuaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
    init { require(listOf(x, y, z, w).all(Float::isFinite)) { "Scene quaternion must be finite" } }
}

class ArcSceneDisplayPose(
    val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
    val translation: ArcSceneVector3, val scale: ArcSceneVector3,
    val leftRotation: ArcSceneQuaternion, val rightRotation: ArcSceneQuaternion,
) {
    init {
        require(listOf(x, y, z).all(Double::isFinite) && yaw.isFinite() && pitch.isFinite()) { "Scene pose must be finite" }
    }
}

/** Rendering allowlist only: no native item, NBT, lore, inventory, owner or persistent data. */
class ArcSceneItem(
    val material: String, val amount: Int, val customModelData: Int?, val tint: Int?, val itemModel: String?,
) {
    init {
        require(sceneResourceKey(material) && (itemModel == null || sceneResourceKey(itemModel))) { "Invalid scene item resource key" }
        require(amount in 1..99 && (tint == null || tint in 0..0xFFFFFF)) { "Invalid scene item appearance" }
    }
}

class ArcSceneTextRun(
    val text: String, val color: Int, val font: String,
    val bold: Boolean, val italic: Boolean, val underlined: Boolean,
    val strikethrough: Boolean, val obfuscated: Boolean,
) {
    init {
        require(text.codePointCount(0, text.length) <= 256 && color in 0..0xFFFFFF && sceneResourceKey(font)) { "Invalid scene text run" }
    }
}

/** Bounded literal text; click/hover events and native Adventure components never cross this API. */
class ArcSceneTextContent(val text: String, runs: List<ArcSceneTextRun>?) {
    val runs: List<ArcSceneTextRun>?
    init {
        require(text.codePointCount(0, text.length) <= 256 && (runs == null || runs.size <= 64)) { "Scene text exceeds its bounds" }
        require(runs == null || runs.joinToString("") { it.text } == text) { "Scene text runs must match the literal fallback" }
        this.runs = runs?.let { Collections.unmodifiableList(ArrayList(it)) }
    }
}

sealed class ArcSceneDisplayContent {
    class Item(val item: ArcSceneItem, val mode: String) : ArcSceneDisplayContent() {
        init { require(mode in setOf("NONE", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND", "FIRSTPERSON_LEFTHAND", "FIRSTPERSON_RIGHTHAND", "HEAD", "GUI", "GROUND", "FIXED")) { "Invalid native item display context" } }
    }
    class Text(
        val content: ArcSceneTextContent, val lineWidth: Int, val alignment: String,
        val backgroundColor: Int, val defaultBackground: Boolean, val opacity: Int,
        val shadow: Boolean, val seeThrough: Boolean,
    ) : ArcSceneDisplayContent() {
        init { require(lineWidth in 1..4096 && alignment in setOf("LEFT", "CENTER", "RIGHT") && opacity in 0..255) { "Invalid native text display style" } }
    }
}

class ArcSceneDisplayKeyframe(val pose: ArcSceneDisplayPose, val itemIndex: Int) {
    init { require(itemIndex in 0..7) { "Scene timeline item index exceeds its bound" } }
}

class ArcSceneDisplayItemSwitch(val offsetMillis: Int, val itemIndex: Int) {
    init { require(offsetMillis in 0..2000 && itemIndex in 0..7) { "Invalid scene item-switch boundary" } }
}

/**
 * Forward native-owner prediction, never a loop. Sample zero is [capturedAtMillis]/[capturedFrame].
 * Interpolate position/SRT continuously. Apply the most recent [itemSwitches] entry at its
 * native timestamp, without interpolating item identities. Stop at the final pose after at
 * most 2000 ms; a refresh with the same display
 * ID replaces the window. No modulo/wrap across the final and first samples is permitted.
 */
class ArcSceneDisplayTimeline(
    val capturedAtMillis: Long, val capturedFrame: Double, val samplePeriodMillis: Int,
    samples: List<ArcSceneDisplayKeyframe>, items: List<ArcSceneItem>, itemSwitches: List<ArcSceneDisplayItemSwitch>,
) {
    val samples: List<ArcSceneDisplayKeyframe>
    val items: List<ArcSceneItem>
    val itemSwitches: List<ArcSceneDisplayItemSwitch>
    init {
        require(capturedAtMillis >= 0 && capturedFrame.isFinite() && capturedFrame >= 0) { "Invalid scene timeline origin" }
        require(samples.size in 1..21 && samplePeriodMillis in 1..1000 && (samples.size - 1) * samplePeriodMillis <= 2000) { "Scene timeline exceeds its time/sample bound" }
        require(items.size in 1..8 && samples.all { it.itemIndex in items.indices }) { "Invalid scene timeline item palette" }
        require(itemSwitches.size in 1..8 && itemSwitches.first().offsetMillis == 0 &&
            itemSwitches.all { it.itemIndex in items.indices && it.offsetMillis <= (samples.size - 1) * samplePeriodMillis } &&
            itemSwitches.zipWithNext().all { (before, after) -> before.offsetMillis < after.offsetMillis } &&
            itemSwitches.first().itemIndex == samples.first().itemIndex) { "Invalid scene timeline item switches" }
        this.samples = Collections.unmodifiableList(ArrayList(samples))
        this.items = Collections.unmodifiableList(ArrayList(items))
        this.itemSwitches = Collections.unmodifiableList(ArrayList(itemSwitches))
    }
}

/** Public decoration explicitly selected by its logical owner, detached on Paper's primary thread. */
class ArcSceneDisplay(
    val worldId: UUID, val id: String, val pose: ArcSceneDisplayPose, val billboard: String,
    val blockLight: Int?, val skyLight: Int?, val viewRange: Float,
    val shadowRadius: Float, val shadowStrength: Float,
    val content: ArcSceneDisplayContent, val timeline: ArcSceneDisplayTimeline?,
) {
    init {
        require(id.length in 1..128 && billboard in setOf("FIXED", "VERTICAL", "HORIZONTAL", "CENTER")) { "Invalid scene display identity or billboard" }
        require((blockLight == null) == (skyLight == null) && (blockLight == null || blockLight in 0..15) && (skyLight == null || skyLight in 0..15)) { "Invalid scene display brightness" }
        require(listOf(viewRange, shadowRadius, shadowStrength).all { it.isFinite() && it >= 0f }) { "Invalid scene display style" }
        require(timeline == null || content is ArcSceneDisplayContent.Item) { "Only public item displays have forward timelines" }
    }
}

private fun sceneResourceKey(value: String): Boolean = value.length in 3..128 &&
    value.count { it == ':' } == 1 && value.substringBefore(':').all { it in 'a'..'z' || it in '0'..'9' || it in "_.-" } &&
    value.substringAfter(':').all { it in 'a'..'z' || it in '0'..'9' || it in "/._-" } &&
    value.substringBefore(':').isNotEmpty() && value.substringAfter(':').isNotEmpty()
