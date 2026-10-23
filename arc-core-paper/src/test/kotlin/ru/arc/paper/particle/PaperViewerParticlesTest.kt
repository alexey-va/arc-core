package ru.arc.paper.particle

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.particle.data.ParticleBlockStateData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleColorData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustColorTransitionData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleDustData
import com.github.retrooper.packetevents.protocol.particle.data.ParticleItemStackData
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.netty.buffer.Unpooled
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.display.TestPacketEventsApi
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperViewerParticlesTest : FreeSpec({
    val previousApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(previousApi) }

    "captures dust values and sends one immutable viewer snapshot" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            val location = Location(viewer.world, 3.25, 64.5, -8.75)

            particles.dust(
                viewer,
                location,
                Particle.DustOptions(Color.fromRGB(120, 220, 255), 0.4f),
                count = 2,
                offsetX = 0.10,
                offsetY = 0.06,
                offsetZ = 0.10,
                extra = 0.0,
            )
            location.x = 99.0

            transport.emissions.size shouldBe 1
            transport.emissions.single().first shouldBe viewer
            val emission = transport.emissions.single()
            val packet = emission.second.roundTrip()
            packet.particle.type shouldBe ParticleTypes.DUST
            packet.position.x shouldBe 3.25
            packet.position.y shouldBe 64.5
            packet.position.z shouldBe -8.75
            packet.particleCount shouldBe 2
            packet.offset.x shouldBe 0.10f
            packet.offset.y shouldBe 0.06f
            packet.offset.z shouldBe 0.10f
            packet.maxSpeed shouldBe 0f
            packet.isLongDistance shouldBe false
            (packet.particle.data as ParticleDustData).color.asRGB() shouldBe Color.fromRGB(120, 220, 255).asRGB()
            (packet.particle.data as ParticleDustData).scale shouldBe 0.4f
            emission.third() shouldBe true
            particles.invalidatePending()
            emission.third() shouldBe false

            particles.dust(viewer, location, Particle.DustOptions(Color.WHITE, 0.2f), count = 1)
            transport.emissions.last().second.extra shouldBe 0f
            transport.emissions.last().third() shouldBe true
            particles.close()
            transport.emissions.last().third() shouldBe false
            particles.dust(viewer, location, Particle.DustOptions(Color.WHITE, 0.2f), count = 1)
            transport.emissions.size shouldBe 2
        }
    }

    "generic no-data emission preserves directional count zero force and native speed default" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            particles.emit(viewer, viewer.location, Particle.FLAME, 0, 0.25, -0.5, 0.75, 0.125, null, true)
            val packet = transport.emissions.single().second.roundTrip()
            packet.particle.type shouldBe ParticleTypes.FLAME
            packet.particleCount shouldBe 0
            packet.offset.x shouldBe 0.25f
            packet.offset.y shouldBe -0.5f
            packet.offset.z shouldBe 0.75f
            packet.maxSpeed shouldBe 0.125f
            packet.isLongDistance shouldBe true
            packet.isAlwaysShow shouldBe false

            particles.emit(viewer, viewer.location, Particle.CLOUD, 3)
            transport.emissions.last().second.extra shouldBe 1f
            transport.emissions.last().second.force shouldBe false
            val first = transport.emissions.first().third
            particles.invalidatePending()
            first() shouldBe false
            particles.emit(viewer, viewer.location, Particle.CLOUD, 3, 0.1, 0.2, 0.3, 0.4)
            transport.emissions.last().third() shouldBe true
            particles.close()
            transport.emissions.last().third() shouldBe false
            particles.emit(viewer, viewer.location, Particle.CLOUD, 3)
            transport.emissions.size shouldBe 3
        }
    }

    "dust transition and flash preserve both RGB endpoints and ARGB respectively" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            val from = Color.fromRGB(12, 54, 98)
            val to = Color.fromRGB(203, 179, 151)
            particles.emit(viewer, viewer.location, Particle.DUST_COLOR_TRANSITION, 1,
                0.0, 0.0, 0.0, 0.0, Particle.DustTransition(from, to, 0.75f))
            val transition = transport.emissions.single().second.roundTrip().particle.data as ParticleDustColorTransitionData
            transition.start.asRGB() shouldBe from.asRGB()
            transition.end.asRGB() shouldBe to.asRGB()
            transition.scale shouldBe 0.75f

            val color = Color.fromARGB(80, 210, 140, 30)
            particles.emit(viewer, viewer.location, Particle.FLASH, 1, 0.0, 0.0, 0.0, 0.0, color)
            val flash = transport.emissions.last().second.roundTrip()
            flash.particle.type shouldBe ParticleTypes.FLASH
            (flash.particle.data as ParticleColorData).color shouldBe color.asARGB()
        }
    }

    "block payload captures native state before the caller changes it" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            var state = "minecraft:oak_log[axis=x]"
            val data = mockk<BlockData> { every { getAsString(false) } answers { state } }
            val expectedId = WrappedBlockState.getByString(state).globalId
            particles.emit(viewer, viewer.location, Particle.BLOCK_CRUMBLE, 4, 0.0, 0.1, 0.0, 0.0, data)
            state = "minecraft:stone"
            val packet = transport.emissions.single().second.roundTrip()
            packet.particle.type shouldBe ParticleTypes.BLOCK_CRUMBLE
            (packet.particle.data as ParticleBlockStateData).blockState.globalId shouldBe expectedId
        }
    }

    "item capture isolates the Bukkit input conversion result and each encoded payload" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            val input = ItemStack(Material.DIAMOND, 4)
            val converted = PacketItemStack.builder().type(ItemTypes.DIAMOND).amount(4).build()
            var nativeInput: ItemStack? = null
            // MockBukkit has no CraftItemStack codec. Only that exact native conversion is replaced.
            mockkStatic(SpigotConversionUtil::class)
            try {
                every { SpigotConversionUtil.fromBukkitItemStack(any()) } answers {
                    nativeInput = firstArg()
                    converted
                }
                particles.emit(viewer, viewer.location, Particle.ITEM, 2, 0.1, 0.2, 0.3, 0.4, input)
                (nativeInput === input) shouldBe false
                nativeInput!!.amount shouldBe 4
            } finally {
                unmockkStatic(SpigotConversionUtil::class)
            }
            input.amount = 40
            converted.amount = 60
            val snapshot = transport.emissions.single().second
            val first = snapshot.packet().particle.data as ParticleItemStackData
            first.itemStack.amount shouldBe 4
            first.itemStack.amount = 30
            val encoded = snapshot.roundTrip()
            encoded.particle.type shouldBe ParticleTypes.ITEM
            (encoded.particle.data as ParticleItemStackData).itemStack.type shouldBe ItemTypes.DIAMOND
            (encoded.particle.data as ParticleItemStackData).itemStack.amount shouldBe 4
        }
    }

    "invalid or unsupported payloads fail before dispatch and never fall back to native effects" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val transport = RecordingParticleTransport()
            val particles = PaperViewerParticles(transport)
            shouldThrow<IllegalArgumentException> { particles.emit(viewer, viewer.location, Particle.DUST, 1) }
            shouldThrow<IllegalArgumentException> {
                particles.emit(viewer, viewer.location, Particle.FLAME, 1, 0.0, 0.0, 0.0, 0.0, Color.WHITE)
            }
            shouldThrow<IllegalArgumentException> {
                particles.emit(viewer, viewer.location, Particle.BLOCK, 1, 0.0, 0.0, 0.0, 0.0, ItemStack(Material.STONE))
            }
            shouldThrow<IllegalArgumentException> {
                particles.emit(viewer, viewer.location, Particle.SHRIEK, 1, 0.0, 0.0, 0.0, 0.0, 1)
            }
            shouldThrow<IllegalArgumentException> { particles.emit(viewer, viewer.location, Particle.FLAME, -1) }
            shouldThrow<IllegalArgumentException> {
                particles.emit(viewer, viewer.location, Particle.FLAME, 1, Double.MAX_VALUE, 0.0, 0.0, 0.0)
            }
            shouldThrow<IllegalArgumentException> {
                particles.dust(viewer, viewer.location, Particle.DustOptions(Color.WHITE, Float.NaN), 1)
            }
            transport.emissions.size shouldBe 0
        }
    }

    "new public overloads need no Kotlin default bridge and retain the original dust descriptor" {
        val api = PaperViewerParticles::class.java
        val prefix = arrayOf(Player::class.java, Location::class.java, Particle::class.java, Int::class.javaPrimitiveType!!)
        val values = Array(4) { Double::class.javaPrimitiveType!! }
        api.getMethod("emit", *prefix)
        api.getMethod("emit", *prefix, *values)
        api.getMethod("emit", *prefix, *values, Any::class.java)
        api.getMethod("emit", *prefix, *values, Any::class.java, Boolean::class.javaPrimitiveType!!)
        api.declaredMethods.none { it.name == "emit\$default" } shouldBe true
        api.getMethod("dust", Player::class.java, Location::class.java, Particle.DustOptions::class.java,
            Int::class.javaPrimitiveType!!, *values)
    }
})

private class RecordingParticleTransport : ViewerParticleTransport {
    val emissions = mutableListOf<Triple<Player, ViewerParticle, () -> Boolean>>()

    override fun emit(viewer: Player, particle: ViewerParticle, isCurrent: () -> Boolean) {
        emissions += Triple(viewer, particle, isCurrent)
    }
}

/** Real PacketEvents 2.12.1 particle codecs, using the existing 1.21.11 registry fixture. */
private fun ViewerParticle.roundTrip(): WrapperPlayServerParticle {
    val buffer = Unpooled.buffer()
    val encoded = packet()
    val decoded = packet()
    try {
        encoded.buffer = buffer
        encoded.write()
        decoded.buffer = buffer
        decoded.read()
        buffer.readableBytes() shouldBe 0
        return decoded
    } finally {
        encoded.buffer = null
        decoded.buffer = null
        buffer.release()
    }
}
