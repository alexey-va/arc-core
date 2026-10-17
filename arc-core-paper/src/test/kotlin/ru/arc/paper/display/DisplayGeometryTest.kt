package ru.arc.paper.display

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Vector3d
import kotlin.math.sqrt

class DisplayGeometryTest : FreeSpec({
    "pitched oriented volume rejects an empty AABB corner and keeps translated swept clearance" {
        val k = sqrt(0.5)
        val center = Vector3d(0.0, 0.0, 0.0)
        val volume = DisplayOrientedVolume(center, Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, k, k), Vector3d(0.0, -k, k), Vector3d(1.0, 1.0, 0.05))
        val emptyCorner = BoundingBox(-0.1, 0.62, -0.68, 0.1, 0.68, -0.62)
        volume.bounds.overlaps(emptyCorner) shouldBe true
        volume.overlaps(emptyCorner) shouldBe false
        volume.overlaps(BoundingBox(-0.1, -0.05, -0.05, 0.1, 0.05, 0.05)) shouldBe true
        val obstacle = BoundingBox(1.4, -0.1, -0.1, 1.6, 0.1, 0.1)
        volume.overlaps(obstacle) shouldBe false
        volume.swept(Vector3d(2.0, 0.0, 0.0)).overlaps(obstacle) shouldBe true
        center.x = 100.0
        volume.bounds.centerX shouldBe 0.0
        volume.bounds.shift(100.0, 0.0, 0.0)
        volume.bounds.centerX shouldBe 0.0
        shouldThrow<IllegalArgumentException> {
            DisplayOrientedVolume(Vector3d(), Vector3d(2.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0),
                Vector3d(0.0, 0.0, 1.0), Vector3d(1.0))
        }
    }

    "camera rectangle samples copy inputs and cover every scaled corner" {
        val center = Vector(0.0, 1.0, 2.0)
        val points = DisplayGeometry.rectangleSamples(center, Vector(1.0, 0.0, 0.0), Vector(0.0, 1.0, 0.0), 0.8, 0.3)
        points.size shouldBe 5
        points.first() shouldBe center
        points.minOf { it.x } shouldBe -0.8
        points.maxOf { it.y } shouldBe 1.3
        center.x = 100.0
        points.first().x shouldBe 0.0
    }

    "wall pullback keeps clearance and proportional scale while caller retains domain fallback" {
        DisplayGeometry.clipDistance(2.4, 0.7, 0.18, 0.12) shouldBe (0.52 plusOrMinus 1e-9)
        DisplayGeometry.clipDistance(2.4, 0.1, 0.18, 0.12) shouldBe 0.12
        DisplayGeometry.clipDistance(2.4, null, 0.18, 0.12) shouldBe 2.4
        DisplayGeometry.distanceCompensatedScale(0.8f, 4.0, 2.0) shouldBe 0.4f
        shouldThrow<IllegalArgumentException> { DisplayGeometry.clipDistance(Double.NaN, null, 0.18, 0.12) }
    }
})
