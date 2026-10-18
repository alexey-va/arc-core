package ru.arc.paper.runtime

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcNativeCleanupService
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

/** One native cleanup budget owned by ArcCore, independent of consumer task scopes. */
class PaperNativeCleanupServiceProvider internal constructor(
    plugin: Plugin,
    maximumSteps: Int,
    maximumNanos: Long,
    nanoTime: () -> Long,
) : ArcNativeCleanupService, AutoCloseable {
    constructor(plugin: Plugin) : this(plugin, 512, 4_000_000L, System::nanoTime)

    private val services = plugin.server.servicesManager
    private val scheduler = BukkitTaskScheduler(plugin)
    private val scope = LifecycleTaskScope(scheduler)
    private val queue = BudgetedTaskQueue(scope, maximumSteps, maximumNanos, nanoTime)
    private var closed = false

    init {
        requirePrimaryThread()
        var registered = false
        try {
            check(services.load(ArcNativeCleanupService::class.java) == null) {
                "An ArcNativeCleanupService provider is already registered"
            }
            services.register(ArcNativeCleanupService::class.java, this, plugin, ServicePriority.Normal)
            registered = true
        } catch (failure: Throwable) {
            closed = true
            if (registered) runCatching { services.unregister(ArcNativeCleanupService::class.java, this) }
            queue.close()
            scope.close()
            scheduler.close()
            throw failure
        }
    }

    override fun submit(owner: String, step: BooleanSupplier): CompletionStage<Void> {
        requirePrimaryThread()
        require(owner.length in 1..80 && OWNER.matches(owner)) { "Cleanup owner must be a stable token of 1..80 characters" }
        if (closed) {
            return CompletableFuture.failedFuture<Void>(CancellationException("Native cleanup provider is closed"))
                .minimalCompletionStage()
        }
        return queue.submit({ !closed }, step::getAsBoolean).thenApply<Void> { null }.minimalCompletionStage()
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try { operation() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        attempt { services.unregister(ArcNativeCleanupService::class.java, this) }
        attempt(queue::close)
        attempt(scope::close)
        attempt(scheduler::close)
        failure?.let { throw it }
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Native cleanup service must be used on Paper's primary thread" }
    }

    private companion object {
        val OWNER = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")
    }
}
