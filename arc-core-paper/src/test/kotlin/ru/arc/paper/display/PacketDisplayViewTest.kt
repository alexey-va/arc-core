package ru.arc.paper.display

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.item.ItemStack
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class PacketDisplayViewTest : FunSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }
    val view = PacketDisplayView(0.0, 64.0, 0.0, 0.0, 0.0, 1.0)
    val item by lazy { PacketDisplayFrame(1, UUID(0, 1), 0.0, 64.0, -2.5, 0f, 0f,
        PacketDisplayMetadata(PacketDisplayContent.Item(ItemStack.EMPTY, 0),
            transform = DisplayTransform(scale = DisplayVector(0.51f, 0.51f, 0.51f)), billboard = 3,
            shadowRadius = 0.12f)) }

    test("ordinary crate items with default native bounds suspend behind at player distance") {
        view.behind(item, false) shouldBe true
        view.behind(item.copy(z = 2.5), false) shouldBe false
        view.behind(item.copy(x = 2.5, z = 0.0), false) shouldBe false
        view.behind(item.copy(z = -0.5), false) shouldBe false
    }

    test("authored size scale translation and attachments preserve potentially visible parts") {
        view.behind(item.copy(metadata = item.metadata.copy(displayWidth = 4f, displayHeight = 4f)), false) shouldBe false
        view.behind(item.copy(metadata = item.metadata.copy(transform = DisplayTransform(scale = DisplayVector(5f, 5f, 5f)))), false) shouldBe false
        view.behind(item.copy(metadata = item.metadata.copy(transform = item.metadata.transform.copy(translation = DisplayVector(0f, 0f, 3f)))), false) shouldBe false
        view.behind(item.copy(attachmentVehicleId = 10), false) shouldBe false
        view.behind(item.copy(metadata = PacketDisplayMetadata(PacketDisplayContent.Text(Component.text("unbounded")))), false) shouldBe false
    }

    test("right-rotated blocks preserve the diagonal amplified by nonuniform scale at the rear margin") {
        val angle = Math.toRadians(145.0)
        val block = PacketDisplayFrame(2, UUID(0, 2), sin(angle) * 8.0, 64.0, cos(angle) * 8.0, 0f, 0f,
            PacketDisplayMetadata(PacketDisplayContent.Block(1), transform = DisplayTransform(
                // Rotate (1,1,1) onto X before stretching it, then turn that corner toward the view.
                rightRotation = DisplayRotation(0f, 0.3250576f, -0.3250576f, 0.8880738f),
                scale = DisplayVector(2f, 0.1f, 0.1f),
                leftRotation = DisplayRotation(0f, -0.3826834f, 0f, 0.9238795f),
            )))
        view.behind(block, false) shouldBe false
        view.behind(block.copy(x = 0.0, z = -12.0), false) shouldBe true
    }

    test("rear margin has hysteresis and unsafe snapshots fail open") {
        val angle = Math.toRadians(120.0)
        val small = item.copy(x = sin(angle) * 100, z = cos(angle) * 100)
        view.behind(small, false) shouldBe false
        view.behind(small, true) shouldBe true
        view.copy(directionZ = 0.0).behind(item, false) shouldBe false
        view.copy(x = Double.NaN).behind(item, false) shouldBe false
        view.behind(item.copy(metadata = item.metadata.copy(displayWidth = Float.NaN)), false) shouldBe false
    }
})
