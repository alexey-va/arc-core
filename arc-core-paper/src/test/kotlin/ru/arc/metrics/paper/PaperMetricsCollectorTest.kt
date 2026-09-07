package ru.arc.metrics.paper

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.SpawnCategory
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.ServerTickManager

class PaperMetricsCollectorTest :
    FreeSpec({
        "uses constant-time Paper counters and preserves compatibility metrics" {
            val world = mockk<World>()
            every { world.name } returns "survival"
            every { world.environment } returns World.Environment.NORMAL
            every { world.players } returns emptyList()
            every { world.entityCount } returns 321
            every { world.chunkCount } returns 44
            every { world.tileEntityCount } returns 12
            every { world.tickableTileEntityCount } returns 7
            every { world.forceLoadedChunks } returns emptySet()
            every { world.viewDistance } returns 10
            every { world.simulationDistance } returns 8
            every { world.getSpawnLimit(any<SpawnCategory>()) } returns 70

            val tickManager = mockk<ServerTickManager>()
            every { tickManager.tickRate } returns 20.0f
            every { tickManager.isRunningNormally } returns true
            every { tickManager.isFrozen } returns false
            every { tickManager.isStepping } returns false
            every { tickManager.isSprinting } returns false

            val enabledPlugin = mockk<Plugin>()
            every { enabledPlugin.isEnabled } returns true
            val pluginManager = mockk<PluginManager>()
            every { pluginManager.plugins } returns arrayOf(enabledPlugin)
            val scheduler = mockk<BukkitScheduler>()
            every { scheduler.pendingTasks } returns emptyList()

            val server = mockk<Server>()
            every { server.tps } returns doubleArrayOf(19.9, 19.8, 19.7)
            every { server.onlinePlayers } returns emptyList()
            every { server.maxPlayers } returns 100
            every { server.worlds } returns listOf(world)
            every { server.minecraftVersion } returns "1.21.11"
            every { server.bukkitVersion } returns "1.21.11-R0.1"
            every { server.serverTickManager } returns tickManager
            every { server.tickTimes } returns LongArray(100) { 5_000_000L }
            every { server.averageTickTime } returns 5.5
            every { server.pluginManager } returns pluginManager
            every { server.scheduler } returns scheduler

            val collector = PaperMetricsCollector(server)
            val fast = collector.fastSnapshot()
            val heavy = collector.heavySnapshot()

            fast.point("arc_tps", "window", "1m") shouldBeExactly 19.9
            fast.point("arc_players_online") shouldBeExactly 0.0
            heavy.point("arc_paper_world_entities", "world", "survival") shouldBeExactly 321.0
            heavy.point("arc_paper_world_loaded_chunks", "world", "survival") shouldBeExactly 44.0
            heavy.point("arc_loaded_chunks_total") shouldBeExactly 44.0
            verify(exactly = 1) { world.chunkCount }
            verify(exactly = 1) { world.entityCount }
            verify(exactly = 1) { world.tileEntityCount }
            verify(exactly = 1) { world.tickableTileEntityCount }
        }
    })

private fun List<ru.arc.metrics.core.MetricPoint>.point(
    name: String,
    tagName: String? = null,
    tagValue: String? = null,
): Double =
    first {
        it.name == name && (tagName == null || it.tags[tagName] == tagValue)
    }.value
