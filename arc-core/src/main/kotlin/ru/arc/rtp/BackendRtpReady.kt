package ru.arc.rtp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Backend-to-proxy signal emitted after the carrier player's Bukkit join. */
data class BackendRtpReady(
    val playerId: UUID,
) {
    fun encode(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(playerId.mostSignificantBits)
            data.writeLong(playerId.leastSignificantBits)
        }
        return output.toByteArray()
    }

    companion object {
        const val CHANNEL = "ruscrafting:rtp_ready"
        const val VERSION = 1
        private const val PAYLOAD_BYTES = 24

        fun decode(payload: ByteArray): BackendRtpReady {
            require(payload.size == PAYLOAD_BYTES) {
                "Backend RTP ready payload must be $PAYLOAD_BYTES bytes"
            }
            return try {
                val input = ByteArrayInputStream(payload)
                val ready =
                    DataInputStream(input).use { data ->
                        require(data.readInt() == MAGIC) { "Invalid backend RTP ready magic" }
                        require(data.readInt() == VERSION) { "Unsupported backend RTP ready version" }
                        BackendRtpReady(UUID(data.readLong(), data.readLong()))
                    }
                require(input.available() == 0) { "Trailing data in backend RTP ready payload" }
                ready
            } catch (failure: IllegalArgumentException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException("Malformed backend RTP ready payload", failure)
            }
        }

        private const val MAGIC = 0x52545231
    }
}
