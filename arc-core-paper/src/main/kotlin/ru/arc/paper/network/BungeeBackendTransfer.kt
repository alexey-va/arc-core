package ru.arc.paper.network

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.network.BackendServerId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class BackendTransferResult {
    SENT,
    PLAYER_OFFLINE,
    TRANSFER_CLOSED,
    SEND_FAILED,
}

fun interface BackendTransfer {
    fun connect(player: Player, destination: BackendServerId): BackendTransferResult
}

/** Strict encoder for the BungeeCord `Connect` plugin-message payload. */
object BungeeConnectPayload {
    @JvmStatic
    fun encode(destination: BackendServerId): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF("Connect")
            output.writeUTF(destination.value)
        }
        bytes.toByteArray()
    }

    internal fun decodeForTest(payload: ByteArray): BackendServerId = DataInputStream(payload.inputStream()).use { input ->
        require(input.readUTF() == "Connect") { "Not a BungeeCord Connect payload" }
        val destination = BackendServerId.of(input.readUTF())
        require(input.read() == -1) { "Trailing bytes in BungeeCord Connect payload" }
        destination
    }
}

/**
 * Owns registration and delivery of the Paper-to-proxy `Connect` channel.
 * Destination ids are typed before payload construction; failures never fall
 * through to a command string.
 */
class BungeeBackendTransfer(
    private val plugin: Plugin,
    private val onSendFailure: (RuntimeException) -> Unit = {},
) : BackendTransfer, AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    override fun connect(player: Player, destination: BackendServerId): BackendTransferResult {
        if (closed.get()) return BackendTransferResult.TRANSFER_CLOSED
        if (!player.isOnline) return BackendTransferResult.PLAYER_OFFLINE
        return try {
            player.sendPluginMessage(plugin, CHANNEL, BungeeConnectPayload.encode(destination))
            BackendTransferResult.SENT
        } catch (failure: RuntimeException) {
            onSendFailure(failure)
            BackendTransferResult.SEND_FAILED
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
        }
    }

    companion object {
        const val CHANNEL = "BungeeCord"
    }
}
