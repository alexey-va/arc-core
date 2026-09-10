package ru.arc.dungeonparty

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Backend-to-proxy request to gather online members for one dungeon party. */
data class DungeonPartyGatherRequest(
    val operationId: UUID,
    val leaderId: UUID,
    val memberIds: List<UUID>,
) {
    init {
        require(memberIds.size in 1..MAX_CANDIDATES) { "Dungeon party candidate count is out of bounds" }
        require(memberIds.distinct().size == memberIds.size) { "Dungeon party candidates must be unique" }
        require(leaderId in memberIds) { "Dungeon party candidates must contain the leader" }
    }

    fun encode(): ByteArray = encode(MAGIC, operationId, leaderId, memberIds, MAX_PAYLOAD_BYTES)

    companion object {
        const val CHANNEL = "ruscrafting:dungeon_party_request"
        const val MAX_CANDIDATES = 64
        const val MAX_PAYLOAD_BYTES = 1_100
        private const val MAGIC = 0x44504751

        fun decode(payload: ByteArray): DungeonPartyGatherRequest {
            val values = decode(payload, MAGIC, MAX_CANDIDATES, MAX_PAYLOAD_BYTES)
            return DungeonPartyGatherRequest(values.operationId, values.leaderId, values.memberIds)
        }
    }
}

/** Trusted proxy-to-Paper roster after the proxy has selected online players and routed them. */
data class DungeonPartyGatherArrival(
    val operationId: UUID,
    val leaderId: UUID,
    val memberIds: List<UUID>,
) {
    init {
        require(memberIds.size in 1..MAX_PARTY_MEMBERS) { "Dungeon party roster count is out of bounds" }
        require(memberIds.distinct().size == memberIds.size) { "Dungeon party roster must be unique" }
        require(memberIds.first() == leaderId) { "Dungeon party leader must be first" }
    }

    fun encode(): ByteArray = encode(MAGIC, operationId, leaderId, memberIds, MAX_PAYLOAD_BYTES)

    companion object {
        const val CHANNEL = "ruscrafting:dungeon_party"
        const val MAX_PARTY_MEMBERS = 5
        const val MAX_PAYLOAD_BYTES = 160
        private const val MAGIC = 0x44504741

        fun decode(payload: ByteArray): DungeonPartyGatherArrival {
            val values = decode(payload, MAGIC, MAX_PARTY_MEMBERS, MAX_PAYLOAD_BYTES)
            return DungeonPartyGatherArrival(values.operationId, values.leaderId, values.memberIds)
        }
    }
}

private data class WireValues(
    val operationId: UUID,
    val leaderId: UUID,
    val memberIds: List<UUID>,
)

private fun encode(
    magic: Int,
    operationId: UUID,
    leaderId: UUID,
    memberIds: List<UUID>,
    maxPayloadBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeInt(magic)
        data.writeInt(1)
        data.writeLong(operationId.mostSignificantBits)
        data.writeLong(operationId.leastSignificantBits)
        data.writeLong(leaderId.mostSignificantBits)
        data.writeLong(leaderId.leastSignificantBits)
        data.writeInt(memberIds.size)
        memberIds.forEach { memberId ->
            data.writeLong(memberId.mostSignificantBits)
            data.writeLong(memberId.leastSignificantBits)
        }
    }
    return output.toByteArray().also {
        require(it.size <= maxPayloadBytes) { "Dungeon party payload is too large" }
    }
}

private fun decode(
    payload: ByteArray,
    expectedMagic: Int,
    maxMembers: Int,
    maxPayloadBytes: Int,
): WireValues {
    require(payload.size in 1..maxPayloadBytes) { "Dungeon party payload size is out of bounds" }
    return try {
        val input = ByteArrayInputStream(payload)
        val values = DataInputStream(input).use { data ->
            require(data.readInt() == expectedMagic) { "Invalid dungeon party payload magic" }
            require(data.readInt() == 1) { "Unsupported dungeon party payload version" }
            val operationId = UUID(data.readLong(), data.readLong())
            val leaderId = UUID(data.readLong(), data.readLong())
            val count = data.readInt()
            require(count in 1..maxMembers) { "Dungeon party member count is out of bounds" }
            val members = List(count) { UUID(data.readLong(), data.readLong()) }
            WireValues(operationId, leaderId, members)
        }
        require(input.available() == 0) { "Trailing data in dungeon party payload" }
        values
    } catch (failure: IllegalArgumentException) {
        throw failure
    } catch (failure: Exception) {
        throw IllegalArgumentException("Malformed dungeon party payload", failure)
    }
}
