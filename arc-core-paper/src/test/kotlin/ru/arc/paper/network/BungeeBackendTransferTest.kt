package ru.arc.paper.network

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger
import ru.arc.network.BackendServerId

class BungeeBackendTransferTest : FreeSpec({
    "owns channel registration, emits the strict payload and unregisters once" {
        val messenger = mockk<Messenger>(relaxed = true)
        val server = mockk<Server> { every { this@mockk.messenger } returns messenger }
        val plugin = mockk<Plugin> { every { this@mockk.server } returns server }
        val player = mockk<Player>(relaxed = true) { every { isOnline } returns true }
        val transfer = BungeeBackendTransfer(plugin)

        transfer.connect(player, BackendServerId.of("survival")) shouldBe BackendTransferResult.SENT
        val payload = mutableListOf<ByteArray>()
        verify(exactly = 1) { messenger.registerOutgoingPluginChannel(plugin, BungeeBackendTransfer.CHANNEL) }
        verify(exactly = 1) { player.sendPluginMessage(plugin, BungeeBackendTransfer.CHANNEL, capture(payload)) }
        BungeeConnectPayload.decodeForTest(payload.single()).value shouldBe "survival"

        transfer.close()
        transfer.close()
        verify(exactly = 1) { messenger.unregisterOutgoingPluginChannel(plugin, BungeeBackendTransfer.CHANNEL) }
        transfer.connect(player, BackendServerId.of("spawn")) shouldBe BackendTransferResult.TRANSFER_CLOSED
    }

    "distinguishes offline players from send failures" {
        val messenger = mockk<Messenger>(relaxed = true)
        val server = mockk<Server> { every { this@mockk.messenger } returns messenger }
        val plugin = mockk<Plugin> { every { this@mockk.server } returns server }
        val player = mockk<Player>(relaxed = true)
        val failures = mutableListOf<String>()
        val transfer = BungeeBackendTransfer(plugin) { failures += requireNotNull(it.message) }

        every { player.isOnline } returns false
        transfer.connect(player, BackendServerId.of("spawn")) shouldBe BackendTransferResult.PLAYER_OFFLINE
        verify(exactly = 0) { player.sendPluginMessage(any(), any(), any()) }

        every { player.isOnline } returns true
        every { player.sendPluginMessage(any(), any(), any()) } throws IllegalStateException("channel unavailable")
        transfer.connect(player, BackendServerId.of("spawn")) shouldBe BackendTransferResult.SEND_FAILED
        failures shouldBe listOf("channel unavailable")
    }
})
