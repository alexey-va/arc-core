package ru.arc.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BackendRtpRequestTest :
    FreeSpec({
        "round trips and normalizes a backend request" {
            val playerId = UUID.randomUUID()
            val request = BackendRtpRequest.create(playerId, " Mining ")

            BackendRtpRequest.decode(request.encode()) shouldBe
                BackendRtpRequest.create(playerId, "mining")
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
