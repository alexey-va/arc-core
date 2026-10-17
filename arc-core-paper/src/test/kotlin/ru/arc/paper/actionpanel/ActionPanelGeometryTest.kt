package ru.arc.paper.actionpanel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component

class ActionPanelGeometryTest : FreeSpec({
    "nearest visible disabled plane blocks farther actions and occlusion includes the whole enlarged edge" {
        val origin = ActionPanelPoint(0.0, 0.0, 0.0)
        val direction = ActionPanelPoint(0.45, 0.0, 1.0)
        val near = ActionPanelPlane("near", ActionPanelPoint(0.0, 0.0, 2.0), 180f, 2.0, 1.0, false)
        val far = ActionPanelPlane("far", ActionPanelPoint(0.0, 0.0, 3.0), 180f, 3.0, 1.0, true)
        ActionPanelGeometry.nearestTarget(origin, direction, listOf(near, far), 4.0, null) shouldBe null
        ActionPanelGeometry.nearestTarget(origin, direction, listOf(near.copy(enabled = true), far), 4.0, null) shouldBe "near"
        ActionPanelGeometry.nearestTarget(origin, direction, listOf(near.copy(enabled = true)), 4.0, 1.8) shouldBe null
        ActionPanelGeometry.nearestTarget(origin, direction, listOf(near.copy(enabled = true)), 1.8, null) shouldBe null
        ActionPanelGeometry.nearestTarget(origin, ActionPanelPoint(0.0, 0.0, 0.0), listOf(far), 4.0, null) shouldBe null
    }

    "two-column profile keeps odd buttons centred and six plus two pagination in stable slots" {
        fun actions(count: Int) = (0 until count).map { ActionPanelAction(it, Component.text("Action $it")) }
        val odd = ActionPanelLayouts.twoColumn(actions(5), Component.text("Selection"))
        odd.buttons.last().rect.center.x shouldBe 0.0
        val full = ActionPanelLayouts.twoColumn(actions(8), Component.text("Selection"), Component.text("1 / 2"), 2,
            ActionPanelGridSettings())
        val short = ActionPanelLayouts.twoColumn(actions(3), Component.text("Selection"), Component.text("2 / 2"), 2,
            ActionPanelGridSettings())
        full.buttons.takeLast(2).map { it.rect.center } shouldBe short.buttons.takeLast(2).map { it.rect.center }
        full.labels.first().rect shouldBe short.labels.first().rect
        full.buttons.takeLast(2).map { it.rect.width } shouldBe listOf(0.45f.toDouble(), 0.45f.toDouble())
        full.labels.last().key shouldBe "pagination"
        shouldThrow<IllegalArgumentException> {
            ActionPanelLayouts.twoColumn(actions(9), Component.empty(), Component.empty(), 2, ActionPanelGridSettings())
        }
    }

    "composed title and controls are centred and every rendered corner participates in reach" {
        val layout = ActionPanelLayouts.twoColumn((0..5).map { ActionPanelAction(it, Component.text("A")) }, Component.text("Title"))
        val rects = layout.rectangles()
        val eye = ActionPanelPoint(10.0, 65.62, 10.0)
        listOf(0f, 45f, 90f, 180f, 270f).forEach { yaw ->
            val anchor = ActionPanelGeometry.followingAnchor(eye, yaw, rects, 2.4, 0.0, 0.0)
            val volume = ActionPanelGeometry.volume(anchor, rects, 0.0)
            volume.center.y shouldBe (eye.y plusOrMinus 1e-7)
            ActionPanelGeometry.allReachable(eye, anchor, rects, 4.0) shouldBe true
        }
        val title = ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 6.0, 1.0)
        ActionPanelGeometry.allReachable(ActionPanelPoint(0.0, 0.0, 0.0),
            ActionPanelAnchor(ActionPanelPoint(0.0, 0.0, 3.0), 0f), listOf(title), 4.0) shouldBe false
    }

    "gaze includes the full panel with separate enter and restore margins" {
        val anchor = ActionPanelAnchor(ActionPanelPoint(0.0, 0.0, 2.4), 180f)
        val rects = listOf(ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 3.0, 0.9))
        val eye = ActionPanelPoint(0.0, 0.0, 0.0)
        val direction = ActionPanelPoint(1.96, 0.0, 2.4)
        ActionPanelGeometry.gazeIntersects(eye, direction, anchor, rects, 8.0) shouldBe false
        ActionPanelGeometry.gazeIntersects(eye, direction, anchor, rects, 13.0) shouldBe true
    }

    "obstacle shifts and distance recover only after a clearance probe" {
        val previous = ActionPanelShift(0.0, 0.5)
        ActionPanelGeometry.adaptiveShift(1.25, previous) { _, margin -> margin == 0.0 } shouldBe previous
        ActionPanelGeometry.adaptiveShift(1.25, previous) { _, _ -> true } shouldBe ActionPanelShift(0.0, 0.44)
        ActionPanelGeometry.adaptiveShift(1.25, null) { shift, _ -> shift.up >= 0.5 } shouldBe previous
        ActionPanelGeometry.stableDistance(2.4, 1.8) { it <= 1.9 } shouldBe 1.8
        ActionPanelGeometry.stableDistance(2.4, 1.8) { true } shouldBe (1.86 plusOrMinus 1e-9)
        ActionPanelGeometry.firstSafeDistance(2.4, 0.35) { it in 1.69..1.71 } shouldBe (1.7 plusOrMinus 1e-8)
        var probes = 0
        ActionPanelGeometry.firstSafeDistance(2.4, 0.35) { probes++; false } shouldBe null
        (probes <= 22) shouldBe true
    }

    "swept bounds cover intermediate rotation and do not become a full cylinder for a small turn" {
        val rects = listOf(ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 3.0, 0.6))
        val from = ActionPanelAnchor(ActionPanelPoint(0.0, 65.0, 0.0), -45f)
        val to = from.copy(yaw = 45f)
        val swept = ActionPanelGeometry.sweptBounds(from, to, rects, 0.04)
        val middle = ActionPanelGeometry.bounds(ActionPanelGeometry.volume(from.copy(yaw = 0f), rects, 0.04))
        (swept.minX <= middle.minX && swept.maxX >= middle.maxX) shouldBe true
        val small = ActionPanelGeometry.sweptBounds(from, from.copy(yaw = -44f), rects, 0.04)
        (small.maxZ - small.minZ < 2.4) shouldBe true
        ActionPanelGeometry.yawDelta(-179f, 179f) shouldBe 2.0
        ActionPanelGeometry.yawDelta(720f, 0f) shouldBe 0.0
        val upright = ActionPanelAnchor(ActionPanelPoint(0.0, 70.0, 0.0), 0f)
        val translated = ActionPanelGeometry.sweptBounds(upright,
            ActionPanelAnchor(ActionPanelPoint(0.0, 70.2, 0.1), 0f), rects, 0.04)
        translated.overlaps(ActionPanelBounds(-0.2, 68.0, 0.45, 0.2, 72.0, 0.6)) shouldBe false
        translated.overlaps(ActionPanelBounds(-0.2, 68.0, 0.08, 0.2, 72.0, 0.12)) shouldBe true
        val smallYaw = ActionPanelGeometry.sweptBounds(upright, upright.copy(yaw = 2f), rects, 0.04)
        smallYaw.overlaps(ActionPanelBounds(-0.2, 68.0, 0.4, 0.2, 72.0, 0.6)) shouldBe false
        smallYaw.overlaps(ActionPanelBounds(-0.2, 68.0, 0.06, 0.2, 72.0, 0.08)) shouldBe true
    }

    "specs reject invalid boundaries and copy caller collections" {
        shouldThrow<IllegalArgumentException> { ActionPanelPoint(Double.NaN, 0.0, 0.0) }
        shouldThrow<IllegalArgumentException> { ActionPanelOptions(ActionPanelInputProfile.EXTERNAL_RAY, 20.0, false) }
        val buttons = mutableListOf(ActionPanelButton("a", ActionPanelRect(ActionPanelPoint(0.0, 0.0, 0.0), 1.0, 0.2),
            Component.text("A"), ActionPanelTextStyle.button(0.8f, 1f, 0.2f)))
        val layout = ActionPanelLayout(buttons, emptyList())
        buttons.clear()
        layout.buttons.size shouldBe 1
    }
})
