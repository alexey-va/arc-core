package ru.arc.metrics.paper

import org.bukkit.Server
import org.bukkit.entity.SpawnCategory
import ru.arc.metrics.core.MetricPoint
import kotlin.math.ceil

/**
 * Paper-only snapshots. Call from the server thread; no Bukkit API is touched
 * by the HTTP scrape thread.
 */
class PaperMetricsCollector(
    private val server: Server,
) {
    fun fastSnapshot(): List<MetricPoint> =
        buildList {
            val tps = server.tps
            add(tpsPoint("1m", tps.getOrElse(0) { 20.0 }))
            add(tpsPoint("5m", tps.getOrElse(1) { 20.0 }))
            add(tpsPoint("15m", tps.getOrElse(2) { 20.0 }))
            add(point("arc_players_online", "Online players", server.onlinePlayers.size.toDouble()))
            add(point("arc_players_max", "Configured maximum players", server.maxPlayers.toDouble()))
            add(point("arc_worlds_loaded", "Loaded worlds", server.worlds.size.toDouble()))
            add(
                point(
                    "arc_paper_server_info",
                    "Static Paper server version information",
                    1.0,
                    mapOf(
                        "minecraft_version" to server.minecraftVersion,
                        "bukkit_version" to server.bukkitVersion,
                    ),
                ),
            )

            val tickManager = server.serverTickManager
            add(point("arc_paper_tick_rate", "Configured Paper tick rate", tickManager.tickRate.toDouble()))
            add(tickStatePoint("normal", tickManager.isRunningNormally))
            add(tickStatePoint("frozen", tickManager.isFrozen))
            add(tickStatePoint("stepping", tickManager.isStepping))
            add(tickStatePoint("sprinting", tickManager.isSprinting))

            val tickMillis = server.tickTimes.map { it.coerceAtLeast(0L) / 1_000_000.0 }
            if (tickMillis.isNotEmpty()) {
                val sorted = tickMillis.sorted()
                add(tickDurationPoint("mean_5s", tickMillis.average()))
                add(tickDurationPoint("p95_5s", sorted[ceil(sorted.lastIndex * 0.95).toInt()]))
                add(tickDurationPoint("max_5s", sorted.last()))
            }
            add(tickDurationPoint("average", server.averageTickTime.coerceAtLeast(0.0)))
        }

    fun heavySnapshot(): List<MetricPoint> =
        buildList {
            var chunks = 0L
            var entities = 0L
            var tileEntities = 0L
            var tickableTileEntities = 0L

            for (world in server.worlds) {
                val tags = mapOf("world" to world.name)
                chunks += world.chunkCount
                entities += world.entityCount
                tileEntities += world.tileEntityCount
                tickableTileEntities += world.tickableTileEntityCount

                add(point("arc_paper_world_info", "Loaded Paper world", 1.0, tags + ("environment" to world.environment.name.lowercase())))
                add(point("arc_paper_world_players", "Players in a world", world.players.size.toDouble(), tags))
                add(point("arc_paper_world_entities", "Entities in a world", world.entityCount.toDouble(), tags))
                add(point("arc_paper_world_loaded_chunks", "Loaded chunks in a world", world.chunkCount.toDouble(), tags))
                add(point("arc_paper_world_tile_entities", "Block entities in a world", world.tileEntityCount.toDouble(), tags))
                add(
                    point(
                        "arc_paper_world_tickable_tile_entities",
                        "Ticking block entities in a world",
                        world.tickableTileEntityCount.toDouble(),
                        tags,
                    ),
                )
                add(
                    point(
                        "arc_paper_world_force_loaded_chunks",
                        "Force-loaded chunks in a world",
                        world.forceLoadedChunks.size.toDouble(),
                        tags,
                    ),
                )
                add(point("arc_paper_world_view_distance", "World view distance in chunks", world.viewDistance.toDouble(), tags))
                add(
                    point(
                        "arc_paper_world_simulation_distance",
                        "World simulation distance in chunks",
                        world.simulationDistance.toDouble(),
                        tags,
                    ),
                )
                for (category in SpawnCategory.entries) {
                    runCatching { world.getSpawnLimit(category) }.getOrNull()?.let { limit ->
                        add(
                            point(
                                "arc_paper_world_spawn_limit",
                                "Configured entity spawn limit by category",
                                limit.toDouble(),
                                tags + ("category" to category.name.lowercase()),
                            ),
                        )
                    }
                }
            }

            add(point("arc_loaded_chunks_total", "Loaded chunks across all worlds", chunks.toDouble()))
            add(point("arc_entities_total", "Entities across all worlds", entities.toDouble()))
            add(point("arc_paper_tile_entities_total", "Block entities across all worlds", tileEntities.toDouble()))
            add(
                point(
                    "arc_paper_tickable_tile_entities_total",
                    "Ticking block entities across all worlds",
                    tickableTileEntities.toDouble(),
                ),
            )

            val plugins = server.pluginManager.plugins
            add(
                point(
                    "arc_paper_plugins",
                    "Paper plugins by state",
                    plugins.count { it.isEnabled }.toDouble(),
                    mapOf("state" to "enabled"),
                ),
            )
            add(
                point(
                    "arc_paper_plugins",
                    "Paper plugins by state",
                    plugins.count { !it.isEnabled }.toDouble(),
                    mapOf("state" to "disabled"),
                ),
            )
            add(
                point(
                    "arc_paper_scheduler_pending_tasks",
                    "Tasks known to the Bukkit scheduler",
                    server.scheduler.pendingTasks.size.toDouble(),
                ),
            )
        }

    private fun tpsPoint(
        window: String,
        value: Double,
    ) = point("arc_tps", "Paper ticks per second", value.coerceAtLeast(0.0), mapOf("window" to window))

    private fun tickStatePoint(
        state: String,
        active: Boolean,
    ) = point("arc_paper_tick_state", "Paper tick-manager state", if (active) 1.0 else 0.0, mapOf("state" to state))

    private fun tickDurationPoint(
        stat: String,
        value: Double,
    ) = point(
        "arc_paper_tick_duration_milliseconds",
        "Paper main-thread tick duration",
        value,
        mapOf("stat" to stat),
    )

    private fun point(
        name: String,
        description: String,
        value: Double,
        tags: Map<String, String> = emptyMap(),
    ) = MetricPoint(name, description, value, tags)
}
