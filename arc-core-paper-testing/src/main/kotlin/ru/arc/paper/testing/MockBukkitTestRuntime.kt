package ru.arc.paper.testing

import org.bukkit.event.Event
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import net.kyori.adventure.title.Title
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.plugin.PluginMock
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

    /** Creates an enabled generic plugin while this runtime owns the singleton. */
    fun createSimplePlugin(name: String): PluginMock {
        require(name.matches(Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}"))) { "Mock plugin name must be a stable token" }
        requireOpen()
        return MockBukkit.createMockPlugin(name)
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

    /** Number of `Player.saveData()` calls observed for this player in the active scenario. */
    fun playerDataSaveCount(player: Player): Int {
        requireOpen()
        return MockBukkitObservations.playerDataSaveCount(player)
    }

    /** Snapshot of Adventure titles shown to this player in the active scenario. */
    fun adventureTitles(player: Player): List<Title> {
        requireOpen()
        return MockBukkitObservations.titles(player)
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
        fun open(): MockBukkitTestRuntime = open(ArcServerMock())

        @JvmStatic
        fun open(server: ServerMock): MockBukkitTestRuntime {
            MockBukkitCompatibility.install()
            return synchronized(lifecycleMonitor) {
                check(!MockBukkit.isMocked()) {
                    "MockBukkit already has an active server; close the owning MockBukkitTestRuntime first"
                }
                MockBukkitCompatibility.resetObservations()
                MockBukkitTestRuntime(MockBukkit.mock(server))
            }
        }
    }
}

inline fun <reified T : Plugin> MockBukkitTestRuntime.loadPlugin(vararg constructorArguments: Any): T =
    loadPlugin(T::class.java, *constructorArguments)

inline fun <reified T : Plugin> MockBukkitTestRuntime.loadSimplePlugin(vararg constructorArguments: Any): T =
    loadSimplePlugin(T::class.java, *constructorArguments)

/**
 * Turns MockBukkit API gaps into real failures instead of JUnit aborted/skipped tests.
 *
 * MockBukkit's `UnimplementedOperationException` is an assumption-abort exception. Without this
 * guard, a scenario can look green while none of its assertions ran. Wrap the complete scenario,
 * including scheduler advancement, so an unsupported operation cannot escape as a skipped test.
 */
fun <T> failOnUnsupportedMockBukkitOperation(block: () -> T): T = try {
    block()
} catch (failure: Throwable) {
    val unsupported = generateSequence(failure as Throwable?) { it.cause }
        .firstOrNull { it.javaClass.name == MOCK_BUKKIT_UNIMPLEMENTED_OPERATION }
    if (unsupported != null) {
        throw AssertionError("MockBukkit scenario reached an unsupported Paper API operation").apply {
            initCause(unsupported)
        }
    }
    throw failure
}

private const val MOCK_BUKKIT_UNIMPLEMENTED_OPERATION =
    "org.mockbukkit.mockbukkit.exception.UnimplementedOperationException"
