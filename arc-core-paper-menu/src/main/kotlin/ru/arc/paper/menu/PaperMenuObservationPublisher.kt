package ru.arc.paper.menu

import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcTelemetryProvider

internal object PaperMenuObservationPublisher {
    fun publish(plugin: Plugin, payload: Map<String, Any>) {
        runCatching {
            plugin.server.servicesManager
                .load(ArcTelemetryProvider::class.java)
                ?.observeUi(payload)
        }
    }
}
