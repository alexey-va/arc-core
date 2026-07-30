package ru.arc.rtp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale
import java.util.UUID

enum class NetworkRtpMode(val wireId: Int) {
    FIRST_ENTRY(1),
    REGULAR(2),
    ;

    companion object {
        fun fromWireId(value: Int): NetworkRtpMode =
            entries.firstOrNull { it.wireId == value }
                ?: throw IllegalArgumentException("Unknown network RTP mode")
    }
}

/**
 * Trusted proxy-to-Paper request for the network RTP flow.
 *
 * The binary contract lives in arc-core so ProxyARC and ARC cannot drift.
 */
data class NetworkRtpRequest(
    val requestId: UUID,
    val playerId: UUID,
    val worldName: String,
    val targetServer: String,
    val mode: NetworkRtpMode,
) {
    fun encode(): ByteArray {
        val normalizedWorld = normalizeName(worldName, "world")
        val normalizedServer = normalizeName(targetServer, "server")
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(requestId.mostSignificantBits)
            data.writeLong(requestId.leastSignificantBits)
            data.writeLong(playerId.mostSignificantBits)
            data.writeLong(playerId.leastSignificantBits)
            data.writeUTF(normalizedWorld)
            data.writeUTF(normalizedServer)
            data.writeByte(mode.wireId)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "RTP request payload is too large" }
        }
    }

    companion object {
        const val CHANNEL = "ruscrafting:rtp"
        /**
         * ProxyARC cannot see a player's Bukkit world. For a bare /rtp issued
         * while already connected to the Paper target, ARC resolves this
         * marker from the carrier player's current world before allowlisting.
         */
        const val CURRENT_WORLD = "_current"
        const val VERSION = 1
        const val MAX_PAYLOAD_BYTES = 256

        fun decode(payload: ByteArray): NetworkRtpRequest {
            require(payload.size in 1..MAX_PAYLOAD_BYTES) {
                "RTP request payload size must be between 1 and $MAX_PAYLOAD_BYTES bytes"
            }
            return try {
                val input = ByteArrayInputStream(payload)
                val request =
                    DataInputStream(input).use { data ->
                        require(data.readInt() == MAGIC) { "Invalid RTP request magic" }
                        require(data.readInt() == VERSION) { "Unsupported RTP request version" }
                        val requestId = UUID(data.readLong(), data.readLong())
                        val playerId = UUID(data.readLong(), data.readLong())
                        val worldName = normalizeName(data.readUTF(), "world")
                        val targetServer = normalizeName(data.readUTF(), "server")
                        val mode = NetworkRtpMode.fromWireId(data.readUnsignedByte())
                        NetworkRtpRequest(requestId, playerId, worldName, targetServer, mode)
                    }
                require(input.available() == 0) { "Trailing data in RTP request payload" }
                request
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException("Malformed RTP request payload", failure)
            }
        }

        private const val MAGIC = 0x52545031
        private val NAME_PATTERN = Regex("[a-z0-9_][a-z0-9_-]{0,31}")

        private fun normalizeName(
            raw: String,
            field: String,
        ): String =
            raw.trim().lowercase(Locale.ROOT).also {
                require(NAME_PATTERN.matches(it)) { "Invalid RTP $field name" }
            }
    }
}
