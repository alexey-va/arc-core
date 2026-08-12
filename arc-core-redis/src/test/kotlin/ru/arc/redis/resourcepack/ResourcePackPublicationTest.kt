package ru.arc.redis.resourcepack

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class ResourcePackPublicationTest :
    FreeSpec({
        "resource-pack publication wire" - {
            "round-trips a normalized SHA-256" {
                val uppercase = "AB".repeat(32)
                val requestId = "CD".repeat(16)

                val payload = ResourcePackPublication.encode(uppercase, requestId)

                payload shouldBe "v1:${"ab".repeat(32)}:${"cd".repeat(16)}"
                ResourcePackPublication.decode(payload) shouldBe
                    ResourcePackPublication.Request("ab".repeat(32), "cd".repeat(16))
                ResourcePackPublication.encodeAcknowledgement(checkNotNull(ResourcePackPublication.decode(payload))) shouldBe
                    "v1:${"cd".repeat(16)}:${"ab".repeat(32)}"
            }

            "rejects invalid hashes and payloads" {
                shouldThrow<IllegalArgumentException> {
                    ResourcePackPublication.encode("not-a-hash", "ab".repeat(16))
                }
                shouldThrow<IllegalArgumentException> {
                    ResourcePackPublication.encode("ab".repeat(32), "not-a-request")
                }
                ResourcePackPublication.decode("v1:short:${"ab".repeat(16)}") shouldBe null
                ResourcePackPublication.decode("command:velocityresourcepacks generatehashes") shouldBe null
                ResourcePackPublication.decode("v1:${"ab".repeat(32)}:${"cd".repeat(16)}:extra") shouldBe null
            }
        }
    })
