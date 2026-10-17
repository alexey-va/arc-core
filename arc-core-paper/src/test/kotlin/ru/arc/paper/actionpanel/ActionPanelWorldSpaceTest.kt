package ru.arc.paper.actionpanel

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime

class ActionPanelWorldSpaceTest : FreeSpec({
    "actual local collision boxes are translated once and unknown chunks never trigger block reads" {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>()
            val player = mockk<Player>()
            val block = mockk<Block>()
            every { player.world } returns world
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            every { world.isChunkLoaded(any<Int>(), any<Int>()) } returns true
            every { player.isChunkSent(any<Long>()) } returns true
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns block
            every { block.collisionShape.boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 0.125, 1.0))
            val space = ActionPanelWorldSpace()
            val panel = ActionPanelBounds(4.2, 65.4, 2.2, 4.8, 65.6, 2.8)
            space.clearSweep(player, panel) shouldBe true
            every { block.collisionShape.boundingBoxes } returns listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            space.clearSweep(player, panel) shouldBe false
            every { player.isChunkSent(any<Long>()) } returns false
            space.clearSweep(player, panel) shouldBe false
            verify(exactly = 2) { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            space.rayClear(player, ActionPanelPoint(0.0, 65.0, 0.0), ActionPanelPoint(20.0, 65.0, 0.0)) { true } shouldBe false
        }
    }
})
