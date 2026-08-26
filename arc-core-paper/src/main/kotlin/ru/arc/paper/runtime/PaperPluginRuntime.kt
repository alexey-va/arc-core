package ru.arc.paper.runtime

import org.bukkit.plugin.Plugin
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.observability.RuntimeEvent
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthSnapshot
import ru.arc.observability.StructuredRuntimeEventLine
import ru.arc.observability.StructuredRuntimeHealthLine
import ru.arc.runtime.PluginRuntime
import ru.arc.runtime.PluginRuntimeState

enum class PaperPluginRuntimeState {
    CREATED,
    ACTIVE,
    CLOSED,
}

/**
 * Paper adapter for the platform-neutral [PluginRuntime].
 *
 * This is deliberately not a plugin superclass. The owning plugin composes it,
 * registers closeable resources and non-blocking health probes, and uses the
 * canonical task scope. Lifecycle methods stay on Paper's primary thread.
 */
class PaperPluginRuntime(
    plugin: Plugin,
    component: String = plugin.name.lowercase(),
    scheduler: TaskScheduler = Tasks.scheduler,
    eventSink: (RuntimeEvent) -> Unit = defaultEventSink(plugin),
) : AutoCloseable {
    private val delegate = PluginRuntime(component, scheduler, eventSink, defaultHealthSink(plugin))

    val tasks get() = delegate.tasks
    val health get() = delegate.health

    val state: PaperPluginRuntimeState
        get() = when (delegate.state) {
            PluginRuntimeState.CREATED -> PaperPluginRuntimeState.CREATED
            PluginRuntimeState.ACTIVE -> PaperPluginRuntimeState.ACTIVE
            PluginRuntimeState.CLOSED -> PaperPluginRuntimeState.CLOSED
        }

    fun start(vararg fields: Pair<String, Any?>) = delegate.start(*fields)

    fun reload() = delegate.reload()

    fun ready(vararg fields: Pair<String, Any?>) = delegate.ready(*fields)

    fun registerHealth(
        id: String,
        probe: () -> RuntimeHealthContribution,
    ): AutoCloseable = delegate.registerHealth(id, probe)

    fun emitHealth(): RuntimeHealthSnapshot = delegate.emitHealth()

    fun reportHealthEvery(
        periodTicks: Long,
        initialDelayTicks: Long = periodTicks,
    ) = delegate.reportHealthEvery(periodTicks, initialDelayTicks)

    fun snapshot(): RuntimeHealthSnapshot = delegate.snapshot()

    fun <T : AutoCloseable> own(resource: T): T = delegate.own(resource)

    fun ownedResourceCount(): Int = delegate.ownedResourceCount()

    override fun close() = delegate.close()

    private companion object {
        fun defaultEventSink(plugin: Plugin): (RuntimeEvent) -> Unit {
            val renderer = StructuredRuntimeEventLine()
            return { event -> plugin.logger.info(renderer.line(event)) }
        }

        fun defaultHealthSink(plugin: Plugin): (RuntimeHealthSnapshot) -> Unit {
            val renderer = StructuredRuntimeHealthLine()
            return { snapshot -> plugin.logger.info(renderer.line(snapshot)) }
        }
    }
}
