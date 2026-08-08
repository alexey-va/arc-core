package ru.arc.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class BackendRtpReadyTest :
    FreeSpec({
        "round trips a backend-ready signal" {
            val signal = BackendRtpReady(UUID.randomUUID())

            BackendRtpReady.decode(signal.encode()) shouldBe signal
        }

        "rejects malformed payloads" {
            shouldThrow<IllegalArgumentException> {
                BackendRtpReady.decode(byteArrayOf(1, 2, 3))
            }
        }

        "rejects trailing payload data" {
            val valid = BackendRtpReady(UUID.randomUUID()).encode()

            shouldThrow<IllegalArgumentException> {
                BackendRtpReady.decode(valid + 0x01)
            }
        }
    })
