package ru.arc.paper.testing

import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock

/**
 * Exclusive owner of MockBukkit's process-global Bukkit singleton.
 *
 * Open one runtime per test and close it with Kotlin [use]. Tests that own this
 * runtime must not execute concurrently in the same JVM. Closing disables
 * loaded plugins and shuts down MockBukkit's scheduler through the exact
 * upstream lifecycle; a nested owner is rejected instead of sharing mutable
 * server state implicitly.
 *
 * MockBukkit is a platform test double, not evidence that every Paper API is
 * implemented. If an exact Paper operation is unsupported, keep the production
 * call behind a narrow seam and test the surrounding behavior without weakening
 * production semantics.
 */
class MockBukkitTestRuntime private constructor(
    val server: ServerMock,
) : AutoCloseable {
    @Volatile
    private var closed = false

    val isOpen: Boolean
        get() = synchronized(lifecycleMonitor) {
            !closed && MockBukkit.getMock() === server
        }

    fun addPlayer(name: String): PlayerMock {
        requireOpen()
        return server.addPlayer(name)
    }

    fun addSimpleWorld(name: String): WorldMock {
        requireOpen()
        return server.addSimpleWorld(name)
    }

    /** Loads and enables a plugin using its normal descriptor resource. */
    fun <T : Plugin> loadPlugin(type: Class<T>, vararg constructorArguments: Any): T {
        requireOpen()
        return MockBukkit.load(type, *constructorArguments)
    }

    /** Loads and enables a plugin without requiring a test `plugin.yml`. */
    fun <T : Plugin> loadSimplePlugin(type: Class<T>, vararg constructorArguments: Any): T {
        requireOpen()
        return MockBukkit.loadSimple(type, *constructorArguments)
    }

    /** Calls an event through the active mocked plugin manager and returns it for assertions. */
    fun <T : Event> callEvent(event: T): T {
        requireOpen()
        server.pluginManager.callEvent(event)
        return event
    }

    /** Advances MockBukkit's deterministic scheduler and world/entity clocks. */
    fun performTicks(ticks: Long) {
        require(ticks >= 0L) { "MockBukkit tick count must not be negative" }
        requireOpen()
        server.scheduler.performTicks(ticks)
    }

    override fun close() {
        synchronized(lifecycleMonitor) {
            if (closed) return
            val active = MockBukkit.getMock()
            if (active == null) {
                closed = true
                return
            }
            check(active === server) { "MockBukkit runtime no longer owns the active server singleton" }
            try {
                MockBukkit.unmock()
            } finally {
                closed = MockBukkit.getMock() !== server
            }
        }
    }

    private fun requireOpen() {
        check(isOpen) { "MockBukkit test runtime is closed or no longer owns the server singleton" }
    }

    companion object {
        private val lifecycleMonitor = Any()

        @JvmStatic
        fun open(): MockBukkitTestRuntime = open(ServerMock())

        @JvmStatic
        fun open(server: ServerMock): MockBukkitTestRuntime = synchronized(lifecycleMonitor) {
            check(!MockBukkit.isMocked()) {
                "MockBukkit already has an active server; close the owning MockBukkitTestRuntime first"
            }
            MockBukkitTestRuntime(MockBukkit.mock(server))
        }
    }
}

inline fun <reified T : Plugin> MockBukkitTestRuntime.loadPlugin(vararg constructorArguments: Any): T =
    loadPlugin(T::class.java, *constructorArguments)

inline fun <reified T : Plugin> MockBukkitTestRuntime.loadSimplePlugin(vararg constructorArguments: Any): T =
    loadSimplePlugin(T::class.java, *constructorArguments)
