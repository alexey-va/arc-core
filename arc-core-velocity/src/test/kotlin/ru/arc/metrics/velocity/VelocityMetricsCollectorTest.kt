package ru.arc.metrics.velocity

import com.velocitypowered.api.event.EventManager
import com.velocitypowered.api.event.ResultedEvent
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.LoginEvent
import com.velocitypowered.api.event.player.ServerConnectedEvent
import com.velocitypowered.api.plugin.PluginManager
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import com.velocitypowered.api.proxy.server.ServerInfo
import com.velocitypowered.api.scheduler.Scheduler
import com.velocitypowered.api.util.ProxyVersion
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

class VelocityMetricsCollectorTest :
    FreeSpec({
        "exports bounded backend gauges and constant-time event counters" {
            val plugin = Any()
            val events = mockk<EventManager>()
            every { events.register(plugin, any()) } just runs
            every { events.unregisterListener(plugin, any()) } just runs
            val plugins = mockk<PluginManager>()
            every { plugins.plugins } returns emptyList()
            val scheduler = mockk<Scheduler>()
            every { scheduler.tasksByPlugin(plugin) } returns emptyList()
            val backend = mockk<RegisteredServer>()
            every { backend.playersConnected } returns emptyList()
            every { backend.serverInfo } returns ServerInfo("survival", java.net.InetSocketAddress("127.0.0.1", 25565))

            val proxy = mockk<ProxyServer>()
            every { proxy.eventManager } returns events
            every { proxy.playerCount } returns 3
            every { proxy.allServers } returns listOf(backend)
            every { proxy.pluginManager } returns plugins
            every { proxy.scheduler } returns scheduler
            every { proxy.version } returns ProxyVersion("Velocity", "PaperMC", "3.3.0")

            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val collector = VelocityMetricsCollector(proxy, plugin, registry)
            collector.start()
            val snapshot = collector.fastSnapshot()

            val login = mockk<LoginEvent>()
            every { login.result } returns ResultedEvent.ComponentResult.allowed()
            collector.onLogin(login)
            val disconnect = mockk<DisconnectEvent>()
            every { disconnect.loginStatus } returns DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
            collector.onDisconnect(disconnect)
            val connected = mockk<ServerConnectedEvent>()
            every { connected.server } returns backend
            collector.onServerConnected(connected)
            collector.stop()

            snapshot.first { it.name == "arc_players_online" }.value shouldBeExactly 3.0
            snapshot
                .first { it.name == "arc_velocity_backend_players" && it.tags["backend"] == "survival" }
                .value shouldBeExactly 0.0
            registry
                .get("arc_velocity_login_events")
                .tag("result", "allowed")
                .counter()
                .count() shouldBeExactly 1.0
            registry
                .get("arc_velocity_server_connections")
                .tag("backend", "survival")
                .counter()
                .count() shouldBeExactly 1.0
        }
    })
