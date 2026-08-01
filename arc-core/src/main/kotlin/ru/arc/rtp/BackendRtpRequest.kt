package ru.arc.rtp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Locale
import java.util.UUID

/**
 * Backend-to-proxy request that delegates a player RTP action to ProxyARC.
 *
 * The carrier player UUID is validated again at the Velocity boundary.
 */
data class BackendRtpRequest(
    val playerId: UUID,
    val worldName: String,
) {
    init {
        require(worldName == normalizeWorld(worldName)) {
            "Backend RTP world name must be normalized"
        }
    }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(playerId.mostSignificantBits)
            data.writeLong(playerId.leastSignificantBits)
            data.writeUTF(worldName)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Backend RTP request payload is too large" }
        }
    }

    companion object {
        const val CHANNEL = "ruscrafting:rtp_request"
        const val VERSION = 1
        const val MAX_PAYLOAD_BYTES = 128

        fun create(
            playerId: UUID,
            worldName: String,
        ): BackendRtpRequest = BackendRtpRequest(playerId, normalizeWorld(worldName))

        fun decode(payload: ByteArray): BackendRtpRequest {
            require(payload.size in 1..MAX_PAYLOAD_BYTES) {
                "Backend RTP request payload size must be between 1 and $MAX_PAYLOAD_BYTES bytes"
            }
            return try {
                val input = ByteArrayInputStream(payload)
                val request =
                    DataInputStream(input).use { data ->
                        require(data.readInt() == MAGIC) { "Invalid backend RTP request magic" }
                        require(data.readInt() == VERSION) { "Unsupported backend RTP request version" }
                        val playerId = UUID(data.readLong(), data.readLong())
                        create(playerId, data.readUTF())
                    }
                require(input.available() == 0) { "Trailing data in backend RTP request payload" }
                request
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException("Malformed backend RTP request payload", failure)
            }
        }

        private const val MAGIC = 0x52544231
        private val NAME_PATTERN = Regex("[a-z0-9_][a-z0-9_-]{0,31}")

        private fun normalizeWorld(raw: String): String =
            raw.trim().lowercase(Locale.ROOT).also {
                require(NAME_PATTERN.matches(it)) { "Invalid backend RTP world name" }
            }
    }
}
