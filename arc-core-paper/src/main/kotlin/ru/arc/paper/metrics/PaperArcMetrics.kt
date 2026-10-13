package ru.arc.paper.metrics

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcMetricsScope
import ru.arc.paper.api.ArcMetricsService

/** Optional ServicesManager lookup for Paper consumers; returns null when ARC metrics are unavailable. */
object PaperArcMetrics {
    @JvmStatic
    fun find(plugin: Plugin): ArcMetricsService? =
        plugin.server.servicesManager.load(ArcMetricsService::class.java)

    @JvmStatic
    fun open(plugin: Plugin, ownerId: String): ArcMetricsScope? = find(plugin)?.open(ownerId)
}
