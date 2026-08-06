package ru.arc.chat

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.repository.CachedRepository
import ru.arc.repository.InMemoryStorage
import ru.arc.repository.InMemorySyncService
import ru.arc.repository.RepoConfig
import java.util.UUID

class ChatModeRepositoryTest {
    private lateinit var storage: InMemoryStorage<PlayerChatMode>
    private lateinit var sync: InMemorySyncService<PlayerChatMode>
    private lateinit var cachedRepository: CachedRepository<PlayerChatMode>
    private lateinit var chatModes: ChatModeRepository

    @BeforeEach
    fun setUp() =
        runTest {
            storage = InMemoryStorage()
            sync = InMemorySyncService()
            cachedRepository =
                CachedRepository(
                    config =
                        RepoConfig
                            .builder<PlayerChatMode>("chat_modes")
                            .loadAllOnStart(true)
                            .enableCleanup(false)
                            .build(),
                    storage = storage,
                    syncService = sync,
                )
            cachedRepository.init().getOrThrow()
            chatModes = ChatModeRepository(cachedRepository)
        }

    @AfterEach
    fun tearDown() =
        runTest {
            cachedRepository.shutdown()
        }

    @Test
    fun `missing player defaults to local mode`() {
        assertEquals(ChatMode.LOCAL, chatModes.getModeNow(UUID.randomUUID()))
    }

    @Test
    fun `selecting global mode persists and broadcasts before reporting success`() =
        runTest {
            val playerId = UUID.randomUUID()

            val result = chatModes.selectMode(playerId, ChatMode.GLOBAL).getOrThrow()

            assertEquals(ChatModeSelection.CHANGED, result)
            assertEquals(ChatMode.GLOBAL, storage.get(playerId.toString())?.mode)
            assertEquals(ChatMode.GLOBAL, sync.getBroadcastedUpdates().single().mode)
        }

    @Test
    fun `selecting the current mode reports already selected without another write`() =
        runTest {
            val playerId = UUID.randomUUID()
            chatModes.selectMode(playerId, ChatMode.GLOBAL).getOrThrow()
            val savedBefore = storage.saveCount
            sync.clear()

            val result = chatModes.selectMode(playerId, ChatMode.GLOBAL).getOrThrow()

            assertEquals(ChatModeSelection.ALREADY_SELECTED, result)
            assertEquals(savedBefore, storage.saveCount)
            assertTrue(sync.getBroadcastedUpdates().isEmpty())
        }

    @Test
    fun `tracked player accepts mode updates created on another server`() =
        runTest {
            val playerId = UUID.randomUUID()
            chatModes.track(playerId)

            sync.simulateRemoteUpdate(PlayerChatMode(playerId.toString(), ChatMode.GLOBAL))

            assertEquals(ChatMode.GLOBAL, chatModes.getModeNow(playerId))
        }
}
