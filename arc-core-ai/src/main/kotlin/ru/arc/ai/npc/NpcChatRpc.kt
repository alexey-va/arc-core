package ru.arc.ai.npc

import com.google.gson.Gson
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

const val NPC_CHAT_PROTOCOL = "arc.npc.chat.v1"
const val NPC_CHAT_MAX_INPUT_CHARS = 240
const val NPC_CHAT_MAX_OUTPUT_CHARS = 280
const val NPC_CHAT_MAX_HISTORY_TURNS = 6

data class NpcChatTurn(
    val role: String,
    val content: String,
)

data class NpcChatRequest(
    val id: UUID,
    val protocol: String = NPC_CHAT_PROTOCOL,
    val playerUuid: UUID,
    val playerName: String,
    val personaId: String,
    val message: String,
    val history: List<NpcChatTurn>,
    val maxOutputChars: Int = NPC_CHAT_MAX_OUTPUT_CHARS,
)

data class NpcChatResponse(
    val id: UUID,
    val protocol: String = NPC_CHAT_PROTOCOL,
    val text: String? = null,
    val error: String? = null,
)

fun interface NpcChatRequestHandler {
    fun complete(request: NpcChatRequest): CompletableFuture<String?>
}

/** Paper-side request client. Session state stays on Paper; only bounded dialogue context crosses Redis. */
class NpcChatRpcClient(
    private val redis: RedisOperations,
    private val config: LlmModuleConfig,
) : ChannelListener,
    AutoCloseable {

    private val gson = Gson()
    private val pending = ConcurrentHashMap<UUID, PendingCall>()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val timeoutScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "arc-npc-chat-timeout-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    private data class PendingCall(
        val future: CompletableFuture<String?>,
        @Volatile var timeoutTask: ScheduledFuture<*>? = null,
    )

    @Synchronized
    fun start() {
        check(!closed.get()) { "Cannot start a closed NpcChatRpcClient" }
        if (!started.compareAndSet(false, true)) return
        try {
            redis.registerChannelUnique(config.npcChatResponseChannel, this)
        } catch (error: Exception) {
            started.set(false)
            throw error
        }
    }

    fun complete(
        playerUuid: UUID,
        playerName: String,
        personaId: String,
        message: String,
        history: List<NpcChatTurn>,
        maxOutputChars: Int = NPC_CHAT_MAX_OUTPUT_CHARS,
    ): CompletableFuture<String?> {
        if (closed.get()) return CompletableFuture.failedFuture(IllegalStateException("NpcChatRpcClient is closed"))
        if (!started.get()) return CompletableFuture.failedFuture(IllegalStateException("NpcChatRpcClient is not started"))

        val request =
            NpcChatRequest(
                id = UUID.randomUUID(),
                playerUuid = playerUuid,
                playerName = playerName.trim().take(32),
                personaId = personaId.trim(),
                message = message.trim().take(NPC_CHAT_MAX_INPUT_CHARS),
                history = history.takeLast(NPC_CHAT_MAX_HISTORY_TURNS),
                maxOutputChars = maxOutputChars.coerceIn(1, NPC_CHAT_MAX_OUTPUT_CHARS),
            )
        val future = CompletableFuture<String?>()
        val call = PendingCall(future)
        pending[request.id] = call
        try {
            call.timeoutTask =
                timeoutScheduler.schedule(
                    {
                        if (pending.remove(request.id, call)) {
                            future.completeExceptionally(TimeoutException("NPC chat request timed out"))
                        }
                    },
                    config.npcChatTimeoutMs.coerceAtLeast(1),
                    TimeUnit.MILLISECONDS,
                )
            future.whenComplete { _, _ ->
                pending.remove(request.id, call)
                call.timeoutTask?.cancel(false)
                call.timeoutTask = null
            }
            redis.publish(config.npcChatRequestChannel, gson.toJson(request))
        } catch (error: Exception) {
            pending.remove(request.id, call)
            future.completeExceptionally(error)
        }
        return future
    }

    override fun consume(channel: String, message: String, originServer: String) {
        val response = runCatching { gson.fromJson(message, NpcChatResponse::class.java) }.getOrNull() ?: return
        val call =
            runCatching {
                if (response.protocol != NPC_CHAT_PROTOCOL) return
                pending.remove(response.id)
            }.getOrNull() ?: return
        if (response.error != null) {
            call.future.completeExceptionally(IllegalStateException(response.error))
        } else {
            call.future.complete(response.text?.take(NPC_CHAT_MAX_OUTPUT_CHARS))
        }
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (started.compareAndSet(true, false)) {
            redis.unregisterChannel(config.npcChatResponseChannel, this)
        }
        pending.values.forEach { call ->
            call.timeoutTask?.cancel(false)
            call.future.cancel(true)
        }
        pending.clear()
        timeoutScheduler.shutdownNow()
    }

    internal fun pendingCount(): Int = pending.size
    internal fun hasActiveTimeoutScheduler(): Boolean = !timeoutScheduler.isShutdown

    private companion object {
        val threadNumber = AtomicInteger()
    }
}

/** Proxy-side responder. It validates the envelope before handing it to the OpenRouter dialogue service. */
class NpcChatRpcServer(
    private val redis: RedisOperations,
    private val config: LlmModuleConfig,
    private val handler: NpcChatRequestHandler,
) : ChannelListener,
    AutoCloseable {

    private val gson = Gson()
    private val started = AtomicBoolean()

    @Synchronized
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            redis.registerChannelUnique(config.npcChatRequestChannel, this)
        } catch (error: Exception) {
            started.set(false)
            throw error
        }
    }

    override fun consume(channel: String, message: String, originServer: String) {
        if (!started.get()) return
        val request = runCatching { gson.fromJson(message, NpcChatRequest::class.java) }.getOrNull() ?: return
        val invalid = runCatching { validate(request) }.getOrElse { return }
        if (invalid != null) {
            publish(NpcChatResponse(request.id, error = invalid))
            return
        }

        val future =
            try {
                handler.complete(request)
            } catch (error: Exception) {
                CompletableFuture.failedFuture(error)
            }
        future.whenComplete { text, failure ->
            if (!started.get()) return@whenComplete
            if (failure != null) {
                log.warn("NPC chat request {} failed for persona {}", request.id, request.personaId, failure)
                publish(NpcChatResponse(request.id, error = "NPC dialogue service unavailable"))
            } else {
                publish(
                    NpcChatResponse(
                        request.id,
                        text = normalizeOutput(text, request.maxOutputChars),
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun close() {
        if (started.compareAndSet(true, false)) {
            redis.unregisterChannel(config.npcChatRequestChannel, this)
        }
    }

    private fun publish(response: NpcChatResponse) {
        runCatching { redis.publish(config.npcChatResponseChannel, gson.toJson(response)) }
            .onFailure { log.warn("Failed to publish NPC chat response {}", response.id, it) }
    }

    private fun validate(request: NpcChatRequest): String? =
        when {
            request.protocol != NPC_CHAT_PROTOCOL -> "Unsupported NPC chat protocol"
            !PERSONA_ID.matches(request.personaId) -> "Invalid NPC persona"
            request.playerName.isBlank() || request.playerName.length > 32 -> "Invalid player name"
            request.message.isBlank() || request.message.length > NPC_CHAT_MAX_INPUT_CHARS -> "Invalid NPC chat message"
            request.history.size > NPC_CHAT_MAX_HISTORY_TURNS -> "NPC chat history is too long"
            request.history.any { it.role !in VALID_ROLES || it.content.isBlank() || it.content.length > NPC_CHAT_MAX_OUTPUT_CHARS } ->
                "Invalid NPC chat history"
            request.maxOutputChars !in 1..NPC_CHAT_MAX_OUTPUT_CHARS -> "Invalid NPC chat output limit"
            else -> null
        }

    private fun normalizeOutput(text: String?, maxChars: Int): String? =
        text
            ?.replace(CONTROL_CHARS, "")
            ?.replace(Regex("[\\t ]+"), " ")
            ?.replace(Regex("\\n{3,}"), "\n\n")
            ?.trim()
            ?.take(maxChars)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private companion object {
        val log = LoggerFactory.getLogger(NpcChatRpcServer::class.java)
        val PERSONA_ID = Regex("[a-z0-9_]{1,48}")
        val VALID_ROLES = setOf("user", "assistant")
        val CONTROL_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    }
}
