package ru.arc.ai.tools

import com.google.gson.Gson
import com.google.gson.JsonElement
import org.slf4j.LoggerFactory
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ToolRpcClient(
    private val redis: RedisOperations,
    private val config: LlmModuleConfig,
    private val playerResolver: PlayerServerResolver? = null,
    private val expectedResponses: Int = 2,
) : ChannelListener, AutoCloseable {

    private val gson = Gson()
    private val pending = ConcurrentHashMap<UUID, PendingCall>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "arc-tool-rpc-timeout-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    private class PendingCall(
        val future: CompletableFuture<ToolInvokeResult>,
        val result: ToolInvokeResult,
        val atLeastOne: Boolean,
        val expectedCount: Int,
    ) {
        @Volatile
        var timeoutTask: ScheduledFuture<*>? = null

        fun cancelTimeout() {
            timeoutTask?.cancel(false)
            timeoutTask = null
        }
    }

    @Synchronized
    fun start() {
        check(!closed.get()) { "Cannot start a closed ToolRpcClient" }
        if (started.compareAndSet(false, true)) {
            try {
                redis.registerChannelUnique(config.toolResultChannel, this)
            } catch (error: Exception) {
                started.set(false)
                throw error
            }
        }
    }

    override fun consume(channel: String, message: String, originServer: String) {
        val response = gson.fromJson(message, ToolInvokeResponse::class.java) ?: return
        val pendingCall = pending[response.id] ?: return
        synchronized(pendingCall) {
            if (pendingCall.future.isDone) return
            pendingCall.result.merge(response)

            val done =
                when {
                    pendingCall.atLeastOne && pendingCall.result.serverResults.isNotEmpty() -> true
                    pendingCall.result.serverResults.size + pendingCall.result.errors.size >= pendingCall.expectedCount -> true
                    else -> false
                }

            if (done && pending.remove(response.id, pendingCall)) {
                pendingCall.future.complete(pendingCall.result)
            }
        }
    }

    @Synchronized
    fun invoke(
        tool: String,
        payload: JsonElement,
        routing: ToolRouting,
        atLeastOneResponse: Boolean = true,
    ): CompletableFuture<ToolInvokeResult> {
        if (closed.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("ToolRpcClient is closed"))
        }
        if (!started.get()) {
            return CompletableFuture.failedFuture(IllegalStateException("ToolRpcClient is not started"))
        }
        val id = UUID.randomUUID()
        val targetServers = resolveTargets(routing)
        val expectedCount =
            when {
                atLeastOneResponse -> 1
                targetServers != null -> targetServers.size.coerceAtLeast(1)
                else -> expectedResponses
            }

        val request =
            ToolInvokeRequest(
                id = id,
                tool = tool,
                payload = payload,
                routing = ToolRoutingDto.from(routing),
                targetServers = targetServers,
                timeoutMs = config.toolDefaultTimeoutMs,
            )

        val future = CompletableFuture<ToolInvokeResult>()
        val pendingCall =
            PendingCall(
                future = future,
                result = ToolInvokeResult(),
                atLeastOne = atLeastOneResponse,
                expectedCount = expectedCount,
            )
        pending[id] = pendingCall

        try {
            pendingCall.timeoutTask =
                timeoutScheduler.schedule(
                    {
                        val timedOut = pending.remove(id, pendingCall)
                        if (timedOut) {
                            synchronized(pendingCall) {
                                pendingCall.future.complete(pendingCall.result)
                            }
                        }
                    },
                    config.toolDefaultTimeoutMs.coerceAtLeast(1),
                    TimeUnit.MILLISECONDS,
                )
        } catch (error: RuntimeException) {
            pending.remove(id, pendingCall)
            future.completeExceptionally(error)
            return future
        }
        future.whenComplete { _, _ ->
            pending.remove(id, pendingCall)
            pendingCall.cancelTimeout()
        }

        try {
            redis.publish(config.toolInvokeChannel, gson.toJson(request))
        } catch (error: Exception) {
            pending.remove(id, pendingCall)
            future.completeExceptionally(error)
            return future
        }

        return future
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (started.compareAndSet(true, false)) {
            redis.unregisterChannel(config.toolResultChannel, this)
        }
        pending.values.forEach { call ->
            synchronized(call) {
                call.cancelTimeout()
                call.future.complete(call.result)
            }
        }
        pending.clear()
        timeoutScheduler.shutdownNow()
    }

    internal fun hasActiveTimeoutScheduler(): Boolean = !timeoutScheduler.isShutdown

    private fun resolveTargets(routing: ToolRouting): List<String>? =
        when (routing) {
            ToolRouting.Broadcast -> null
            is ToolRouting.TargetServer -> listOf(routing.serverName)
            is ToolRouting.ByPlayer -> {
                val server = playerResolver?.serverForPlayer(routing.playerName)
                if (server.isNullOrBlank()) listOf("__none__") else listOf(server)
            }
        }

    companion object {
        private val log = LoggerFactory.getLogger(ToolRpcClient::class.java)
        private val threadNumber = AtomicInteger()

        @JvmField
        var instance: ToolRpcClient? = null
    }
}
