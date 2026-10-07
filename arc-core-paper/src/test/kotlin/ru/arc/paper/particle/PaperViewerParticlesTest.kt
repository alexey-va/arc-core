package ru.arc.paper.particle

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperViewerParticlesTest : FreeSpec({
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
            emission.second shouldBe ViewerDustParticle(
                3.25, 64.5, -8.75,
                120, 220, 255, 0.4f,
                2, 0.10f, 0.06f, 0.10f, 0.0f,
            )
            emission.third() shouldBe true
            particles.invalidatePending()
            emission.third() shouldBe false

            particles.dust(viewer, location, Particle.DustOptions(Color.WHITE, 0.2f), count = 1)
            transport.emissions.last().third() shouldBe true
            particles.close()
            transport.emissions.last().third() shouldBe false
            particles.dust(viewer, location, Particle.DustOptions(Color.WHITE, 0.2f), count = 1)
            transport.emissions.size shouldBe 2
        }
    }
})

private class RecordingParticleTransport : ViewerParticleTransport {
    val emissions = mutableListOf<Triple<Player, ViewerDustParticle, () -> Boolean>>()

    override fun emit(viewer: Player, particle: ViewerDustParticle, isCurrent: () -> Boolean) {
        emissions += Triple(viewer, particle, isCurrent)
    }
}
