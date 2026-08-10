package ru.arc.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID

class BackendRtpRequestTest :
    FreeSpec({
        "round trips and normalizes a backend request" {
            val playerId = UUID.randomUUID()
            val request = BackendRtpRequest.create(playerId, " Mining ")

            BackendRtpRequest.decode(request.encode()) shouldBe
                BackendRtpRequest.create(playerId, "mining")
        }

        "round trips an explicit first-entry request" {
            val playerId = UUID.randomUUID()
            val request =
                BackendRtpRequest.create(
                    playerId,
                    "survival",
                    NetworkRtpMode.FIRST_ENTRY,
                )

            BackendRtpRequest.decode(request.encode()) shouldBe request
        }

        "decodes legacy requests with their original first-entry semantics" {
            val playerId = UUID.randomUUID()
            val output = ByteArrayOutputStream()
            DataOutputStream(output).use { data ->
                data.writeInt(0x52544231)
                data.writeInt(1)
                data.writeLong(playerId.mostSignificantBits)
                data.writeLong(playerId.leastSignificantBits)
                data.writeUTF("survival")
            }

            BackendRtpRequest.decode(output.toByteArray()) shouldBe
                BackendRtpRequest.create(playerId, "survival", NetworkRtpMode.FIRST_ENTRY)
        }

        "rejects unsafe world names" {
            shouldThrow<IllegalArgumentException> {
                BackendRtpRequest.create(UUID.randomUUID(), "../world")
            }
        }

        "rejects malformed payloads" {
            shouldThrow<IllegalArgumentException> {
                BackendRtpRequest.decode(byteArrayOf(1, 2, 3))
            }
        }

        "rejects trailing payload data" {
            val valid = BackendRtpRequest.create(UUID.randomUUID(), "vanilla").encode()

            shouldThrow<IllegalArgumentException> {
                BackendRtpRequest.decode(valid + 0x01)
            }
        }
    })
