package ru.arc.paper.inspection

import com.github.retrooper.packetevents.protocol.item.ItemStack as PacketItemStack
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcInspectionFrame
import ru.arc.paper.api.InspectionViewMode
import ru.arc.paper.api.InspectionViewPreferences
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.paper.display.PacketDisplayAudienceSource
import ru.arc.paper.display.PacketDisplayConnection
import ru.arc.paper.display.PacketDisplayContent
import ru.arc.paper.display.PacketDisplayFrame
import ru.arc.paper.display.PacketDisplayTransport
import ru.arc.paper.display.PacketDisplayViewer
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.player.TestPaperPlugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.paper.testing.loadPlugin
import java.util.UUID

class PaperArcInspectionServiceTest : FreeSpec({
    "priority falls through null, empty frames suppress, and closing the winner restores the next source" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val player = paper.addPlayer("Viewer")
                val transport = RecordingTransport()
                val displays = packetDisplays(plugin, player, transport)
                val service = PaperArcInspectionService(plugin, displays, RecordingEffects())
                var lowerCalls = 0
                var higherFrame: ArcInspectionFrame? = null
                service.register(plugin, "backing", 10) {
                    lowerCalls++
                    ArcInspectionFrame(Component.text("Backing target"))
                }
                val higher = service.register(plugin, "model", 20) { higherFrame }

                service.update(player, InspectionViewPreferences())
                lowerCalls shouldBe 1
                displays.refresh()
                transport.lastText(player) shouldBe Component.text("Backing target")

                higherFrame = ArcInspectionFrame(Component.empty(), Component.empty())
                service.update(player, InspectionViewPreferences())
                lowerCalls shouldBe 1
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()

                higher.close()
                lowerCalls shouldBe 2
                displays.refresh()
                transport.lastText(player) shouldBe Component.text("Backing target")
                service.close()
            }
        }
    }

    "preferences select one presentation, clear disables it, and close hides active output" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val player = paper.addPlayer("Viewer")
                val transport = RecordingTransport()
                val displays = packetDisplays(plugin, player, transport)
                val effects = RecordingEffects()
                val service = PaperArcInspectionService(plugin, displays, effects)
                var resolutions = 0
                service.register(plugin, "engine", 1) {
                    resolutions++
                    ArcInspectionFrame(Component.text("Cylinder 1"), Component.text("Cylinder 1 · intake"))
                }

                service.update(player, InspectionViewPreferences(scale = 1.25f))
                displays.refresh()
                transport.lastContent(player)?.text shouldBe Component.text("Cylinder 1")
                transport.forPlayer(player).last().frames.last().metadata.transform.scale.x shouldBe 1.25f
                service.follow(player)

                service.update(player, InspectionViewPreferences(mode = InspectionViewMode.BOSSBAR))
                effects.shown shouldHaveSize 1
                effects.shown.single().name() shouldBe Component.text("Cylinder 1 · intake")
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()

                service.update(player, InspectionViewPreferences(mode = InspectionViewMode.OFF))
                resolutions shouldBe 2
                effects.hidden shouldHaveSize 1
                service.update(player, InspectionViewPreferences(mode = InspectionViewMode.BOSSBAR))
                effects.shown shouldHaveSize 2
                service.close()
                effects.hidden shouldHaveSize 2
                transport.closed shouldBe true
            }
        }
    }

    "equal priorities keep registration order, failures fall through, and lifecycle events clear output" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadPlugin<TestPaperPlugin>()
                val player = paper.addPlayer("Viewer")
                val transport = RecordingTransport()
                val displays = packetDisplays(plugin, player, transport)
                val service = PaperArcInspectionService(plugin, displays, RecordingEffects())
                var brokenCalls = 0
                var firstCalls = 0
                var secondCalls = 0
                service.register(plugin, "broken", 10) {
                    brokenCalls++
                    error("provider failure")
                }
                service.register(plugin, "first", 5) {
                    firstCalls++
                    ArcInspectionFrame(Component.text("First registered"))
                }
                service.register(plugin, "second", 5) {
                    secondCalls++
                    ArcInspectionFrame(Component.text("Second registered"))
                }

                service.update(player, InspectionViewPreferences())
                brokenCalls shouldBe 1
                firstCalls shouldBe 1
                secondCalls shouldBe 0
                displays.refresh()
                transport.lastText(player) shouldBe Component.text("First registered")

                paper.callEvent(PlayerChangedWorldEvent(player, player.world))
                displays.refresh()
                transport.forPlayer(player).last().frames shouldBe emptyList()

                service.update(player, InspectionViewPreferences())
                displays.refresh()
                transport.lastText(player) shouldBe Component.text("First registered")
                val quitConnection = transport.forPlayer(player)
                paper.callEvent(
                    PlayerQuitEvent(player, Component.text("Disconnected"), PlayerQuitEvent.QuitReason.DISCONNECTED),
                )
                quitConnection.last().frames shouldBe emptyList()
                transport.hasConnection(player) shouldBe false
                service.close()
            }
        }
    }
})

private fun packetDisplays(
    plugin: TestPaperPlugin,
    player: Player,
    transport: RecordingTransport,
): PaperPacketDisplays = PaperPacketDisplays(
    plugin,
    transport,
    LifecycleTaskScope(TestTaskScheduler()),
    MutableAudience(player),
)

private class MutableAudience(private val player: Player) : PacketDisplayAudienceSource {
    override fun checkThread() = Unit
    override fun capture(): List<PacketDisplayViewer> {
        val location = player.location
        return if (player.isOnline) listOf(
            PacketDisplayViewer(
                player, player.uniqueId, player.world.uid,
                location.x, location.y, location.z, setOf(0L),
            ),
        ) else emptyList()
    }
}

private class RecordingTransport : PacketDisplayTransport {
    private val connections = linkedMapOf<UUID, RecordingConnection>()
    private var nextId = 200
    var closed = false
        private set

    fun forPlayer(player: Player): RecordingConnection = connections.getValue(player.uniqueId)
    fun hasConnection(player: Player): Boolean = player.uniqueId in connections
    fun lastText(player: Player): Component? = lastContent(player)?.text
    fun lastContent(player: Player): PacketDisplayContent.Text? =
        forPlayer(player).last().frames.mapNotNull { it.metadata.content as? PacketDisplayContent.Text }.lastOrNull()

    override fun blockStateId(blockData: BlockData): Int = error("Inspection tests use text displays only")
    override fun itemSnapshot(item: ItemStack): PacketItemStack = error("Inspection tests use text displays only")
    override fun nextEntityId(): Int = nextId++
    override fun connection(player: Player): PacketDisplayConnection =
        connections.getOrPut(player.uniqueId) { RecordingConnection() }
    override fun forget(player: Player) { connections.remove(player.uniqueId) }
    override fun close() { closed = true }
}

private class RecordingConnection : PacketDisplayConnection {
    data class Snapshot(val frames: List<PacketDisplayFrame>)
    private val snapshots = mutableListOf<Snapshot>()
    override val identity: Any = Any()
    fun last() = snapshots.last()
    override fun submit(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean) {
        snapshots += Snapshot(desired)
    }
}

private class RecordingEffects : PaperAudienceEffects {
    val shown = mutableListOf<BossBar>()
    val hidden = mutableListOf<BossBar>()
    override fun sendMessage(player: Player, message: Component) = Unit
    override fun sendActionBar(player: Player, message: Component) = Unit
    override fun showTitle(player: Player, title: Title) = Unit
    override fun showBossBar(player: Player, bossBar: BossBar) { shown += bossBar }
    override fun hideBossBar(player: Player, bossBar: BossBar) { hidden += bossBar }
}
