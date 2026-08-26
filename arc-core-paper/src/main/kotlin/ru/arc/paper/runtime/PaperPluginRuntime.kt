package ru.arc.paper.runtime

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.observability.RuntimeEvent
import ru.arc.observability.RuntimeEventOutcome
import ru.arc.observability.RuntimeEventType
import ru.arc.observability.StructuredRuntimeEventLine
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

enum class PaperPluginRuntimeState {
    CREATED,
    ACTIVE,
    CLOSED,
}

/**
 * Explicit composition root for one Paper plugin lifecycle.
 *
 * This is deliberately not a plugin superclass. The owning plugin constructs
 * it, registers closeable Redis/subscription/executor resources with [own], and
 * schedules reloadable work through [tasks]. [start], [reload], [ready] and
 * [close] are expected on the Paper primary lifecycle thread; owned resources
 * remain responsible for their own blocking-I/O shutdown policy.
 *
 * Reload invalidates and cancels the prior task epoch but does not replace
 * long-lived owned resources. Close is terminal, idempotent, cancels tasks
 * first and then closes resources in reverse registration order.
 */
class PaperPluginRuntime(
    private val plugin: Plugin,
    private val component: String = plugin.name.lowercase(),
    scheduler: TaskScheduler = Tasks.scheduler,
    private val eventSink: (RuntimeEvent) -> Unit = defaultEventSink(plugin),
) : AutoCloseable {
    private val monitor = Any()
    private val resources = ArrayDeque<AutoCloseable>()
    private val ownedIdentities = Collections.newSetFromMap(IdentityHashMap<AutoCloseable, Boolean>())
    val tasks = LifecycleTaskScope(scheduler = scheduler, initiallyActive = false)

    @Volatile
    var state: PaperPluginRuntimeState = PaperPluginRuntimeState.CREATED
        private set

    fun start(vararg fields: Pair<String, Any?>): LifecycleTaskScope.Token = synchronized(monitor) {
        check(state == PaperPluginRuntimeState.CREATED) { "Paper plugin runtime may only start once" }
        eventSink(
            RuntimeEvent(
                RuntimeEventType.PLUGIN_BOOTSTRAP,
                component,
                RuntimeEventOutcome.STARTED,
                fields.toList(),
            ),
        )
        val token = tasks.activate()
        state = PaperPluginRuntimeState.ACTIVE
        token
    }

    fun reload(): LifecycleTaskScope.Token = synchronized(monitor) {
        check(state == PaperPluginRuntimeState.ACTIVE) { "Paper plugin runtime is not active" }
        tasks.restart()
    }

    fun ready(vararg fields: Pair<String, Any?>) {
        synchronized(monitor) {
            check(state == PaperPluginRuntimeState.ACTIVE) { "Paper plugin runtime is not active" }
        }
        eventSink(
            RuntimeEvent(
                RuntimeEventType.PLUGIN_READY,
                component,
                RuntimeEventOutcome.OK,
                fields.toList(),
            ),
        )
    }

    /** Registers one long-lived resource for reverse-order shutdown. */
    fun <T : AutoCloseable> own(resource: T): T = synchronized(monitor) {
        check(state != PaperPluginRuntimeState.CLOSED) { "Paper plugin runtime is closed" }
        check(ownedIdentities.add(resource)) { "Paper plugin runtime already owns this resource" }
        resources.addLast(resource)
        resource
    }

    fun ownedResourceCount(): Int = synchronized(monitor) { resources.size }

    override fun close() {
        val toClose = synchronized(monitor) {
            if (state == PaperPluginRuntimeState.CLOSED) return
            state = PaperPluginRuntimeState.CLOSED
            resources.reversed().toList().also {
                resources.clear()
                ownedIdentities.clear()
            }
        }
        var firstFailure: Throwable? = null
        try {
            tasks.close()
        } catch (failure: Throwable) {
            firstFailure = failure
        }
        toClose.forEach { resource ->
            try {
                resource.close()
            } catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw IllegalStateException("Could not close every Paper plugin runtime resource", it) }
    }

    private companion object {
        fun defaultEventSink(plugin: Plugin): (RuntimeEvent) -> Unit {
            val renderer = StructuredRuntimeEventLine()
            return { event -> plugin.logger.info(renderer.line(event)) }
        }
    }
}
