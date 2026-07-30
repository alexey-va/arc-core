package ru.arc.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class NetworkRtpRequestTest :
    FreeSpec({
        "round trips the trusted proxy request" {
            val request =
                NetworkRtpRequest(
                    requestId = UUID.randomUUID(),
                    playerId = UUID.randomUUID(),
                    worldName = "Survival",
                    targetServer = "SURVIVAL",
                    mode = NetworkRtpMode.FIRST_ENTRY,
                )

            NetworkRtpRequest.decode(request.encode()) shouldBe
                request.copy(worldName = "survival", targetServer = "survival")
        }

        "rejects malformed and trailing payloads" {
            shouldThrow<IllegalArgumentException> {
                NetworkRtpRequest.decode(byteArrayOf(1, 2, 3))
            }

            val valid =
                NetworkRtpRequest(
                    requestId = UUID.randomUUID(),
                    playerId = UUID.randomUUID(),
                    worldName = "mining",
                    targetServer = "survival",
                    mode = NetworkRtpMode.REGULAR,
                ).encode()
            shouldThrow<IllegalArgumentException> {
                NetworkRtpRequest.decode(valid + 0x01)
            }
        }

        "rejects unsafe names before encoding" {
            shouldThrow<IllegalArgumentException> {
                NetworkRtpRequest(
                    requestId = UUID.randomUUID(),
                    playerId = UUID.randomUUID(),
                    worldName = "../world",
                    targetServer = "survival",
                    mode = NetworkRtpMode.FIRST_ENTRY,
                ).encode()
            }
        }
    })
