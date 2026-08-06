package ru.arc.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.RepoResult
import java.util.UUID

enum class ChatMode {
    LOCAL,
    GLOBAL,
}

enum class ChatModeSelection {
    CHANGED,
    ALREADY_SELECTED,
}

data class PlayerChatMode(
    val playerId: String,
    val mode: ChatMode = ChatMode.LOCAL,
) : Entity {
    override fun id(): String = playerId
}

class ChatModeRepository(
    private val repository: CachedRepository<PlayerChatMode>,
) {
    private val selectionMutex = Mutex()
    private val log = LoggerFactory.getLogger(ChatModeRepository::class.java)

    fun getModeNow(playerId: UUID): ChatMode =
        repository.getNow(playerId.toString())?.mode ?: ChatMode.LOCAL

    fun track(playerId: UUID) {
        repository.addContext(playerId.toString())
        log.debug("[ChatMode] tracking player={}", playerId)
    }

    fun untrack(playerId: UUID) {
        repository.removeContext(playerId.toString())
        log.debug("[ChatMode] stopped tracking player={}", playerId)
    }

    suspend fun selectMode(
        playerId: UUID,
        mode: ChatMode,
    ): RepoResult<ChatModeSelection> =
        selectionMutex.withLock {
            val id = playerId.toString()
            val current =
                when (val result = repository.getOrCreate(id) { PlayerChatMode(id) }) {
                    is RepoResult.Success -> result.data
                    is RepoResult.Error -> {
                        log.warn("[ChatMode] failed to load player={} requested={}: {}", playerId, mode, result.message)
                        return@withLock result
                    }
                }

            if (current.mode == mode) {
                log.debug("[ChatMode] player={} requested={} result=already-selected", playerId, mode)
                return@withLock RepoResult.success(ChatModeSelection.ALREADY_SELECTED)
            }

            when (val cached = repository.save(current.copy(mode = mode))) {
                is RepoResult.Success -> Unit
                is RepoResult.Error -> return@withLock cached
            }

            when (val flushed = repository.saveDirty()) {
                is RepoResult.Success -> {
                    log.debug("[ChatMode] player={} previous={} selected={} result=changed", playerId, current.mode, mode)
                    RepoResult.success(ChatModeSelection.CHANGED)
                }

                is RepoResult.Error -> {
                    log.warn("[ChatMode] failed to persist player={} requested={}: {}", playerId, mode, flushed.message)
                    flushed
                }
            }
        }

    companion object {
        const val REPOSITORY_ID = "chat_modes"
        const val STORAGE_KEY = "arc.chat_modes"
        const val UPDATE_CHANNEL = "arc.chat_modes_update"
    }
}
