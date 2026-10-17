package ru.arc.paper.menu

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class PaperDialogSessionStore(namespace: String) {
    private val namespace = namespace.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
    private val instance = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    private val sessions = mutableMapOf<UUID, PaperDialogSessionRegistration>()

    init {
        require(this.namespace.matches(Regex("[a-z0-9][a-z0-9_.-]{0,63}"))) {
            "Paper dialog namespace is invalid: '${this.namespace}'"
        }
    }

    val size: Int get() = sessions.size

    fun replace(
        playerId: UUID,
        actions: Map<PaperDialogActionId, () -> Unit>,
    ): PaperDialogSessionRegistration {
        return prepare(actions).also { publish(playerId, it) }
    }

    /** Detached nonce/handlers cannot dispatch until their complete native dialog is ready. */
    fun prepare(actions: Map<PaperDialogActionId, () -> Unit>): PaperDialogSessionRegistration =
        PaperDialogSessionRegistration(namespace, "$instance-${sequence.incrementAndGet().toString(36)}", actions)

    fun publish(playerId: UUID, registration: PaperDialogSessionRegistration) {
        sessions[playerId] = registration
    }

    fun consume(playerId: UUID, key: String): (() -> Unit)? {
        val session = sessions[playerId] ?: return null
        val prefix = "$namespace:dialog/${session.nonce}/"
        if (!key.startsWith(prefix)) return null
        val action = runCatching { PaperDialogActionId.of(key.removePrefix(prefix)) }.getOrNull() ?: return null
        val handler = session.actions[action] ?: return null
        sessions.remove(playerId)
        return handler
    }

    fun remove(playerId: UUID) {
        sessions.remove(playerId)
    }

    fun clear() {
        sessions.clear()
    }
}

internal data class PaperDialogSessionRegistration(
    private val namespace: String,
    val nonce: String,
    internal val actions: Map<PaperDialogActionId, () -> Unit>,
) {
    fun key(action: PaperDialogActionId): String = "$namespace:dialog/$nonce/${action.value}"
}
