package ru.arc.metrics.velocity

import com.velocitypowered.api.event.PostOrder
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.proxy.ProxyServer
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import ru.arc.metrics.core.MetricPoint
import java.util.concurrent.ConcurrentHashMap

/**
 * Velocity gauges plus constant-time event counters. Backend labels come only
 * from the registered-server set, keeping cardinality bounded.
 */
class VelocityMetricsCollector(
    private val proxy: ProxyServer,
    private val plugin: Any,
    private val registry: MeterRegistry,
) {
    private val loginCounters = ConcurrentHashMap<String, Counter>()
    private val disconnectCounters = ConcurrentHashMap<String, Counter>()
    private val switchCounters = ConcurrentHashMap<String, Counter>()

    @Volatile
    private var listening = false

    fun start() {
        if (listening) return
        proxy.eventManager.register(plugin, this)
        listening = true
    }

    fun stop() {
        if (!listening) return
        proxy.eventManager.unregisterListener(plugin, this)
        listening = false
    }

    fun fastSnapshot(): List<MetricPoint> =
        buildList {
            val players = proxy.playerCount.toDouble()
            add(point("arc_players_online", "Online proxy players", players))
            add(point("arc_velocity_players_online", "Online proxy players", players))
            add(
                point(
                    "arc_velocity_registered_servers",
                    "Registered Velocity backend servers",
                    proxy.allServers.size.toDouble(),
                ),
            )
            add(
                point(
                    "arc_velocity_plugins",
                    "Loaded Velocity plugins",
                    proxy.pluginManager.plugins.size.toDouble(),
                ),
            )
            add(
                point(
                    "arc_velocity_scheduler_tasks",
                    "Tasks scheduled by ProxyARC",
                    proxy.scheduler.tasksByPlugin(plugin).size.toDouble(),
                ),
            )

            val version = proxy.version
            add(
                point(
                    "arc_velocity_proxy_info",
                    "Static Velocity proxy version information",
                    1.0,
                    mapOf(
                        "name" to version.name,
                        "vendor" to version.vendor,
                        "version" to version.version,
                    ),
                ),
            )
            for (backend in proxy.allServers) {
                add(
                    point(
                        "arc_velocity_backend_players",
                        "Players connected to a Velocity backend",
                        backend.playersConnected.size.toDouble(),
                        mapOf("backend" to backend.serverInfo.name),
                    ),
                )
            }
        }

    @Subscribe(order = PostOrder.LAST)
    fun onLogin(event: LoginEvent) {
        val result = if (event.result.isAllowed) "allowed" else "denied"
        counter(loginCounters, "arc_velocity_login_events", "result", result).increment()
    }

    @Subscribe
    fun onDisconnect(event: DisconnectEvent) {
        val status = event.loginStatus.name.lowercase()
        counter(disconnectCounters, "arc_velocity_disconnect_events", "status", status).increment()
    }

    @Subscribe
    fun onServerConnected(event: ServerConnectedEvent) {
        val backend = event.server.serverInfo.name
        counter(switchCounters, "arc_velocity_server_connections", "backend", backend).increment()
    }

    private fun counter(
        cache: ConcurrentHashMap<String, Counter>,
        name: String,
        tagName: String,
        tagValue: String,
    ): Counter =
        cache.computeIfAbsent(tagValue) {
            Counter
                .builder(name)
                .tag(tagName, tagValue)
                .register(registry)
        }

    private fun point(
        name: String,
        description: String,
        value: Double,
        tags: Map<String, String> = emptyMap(),
    ) = MetricPoint(name, description, value, tags)
}
