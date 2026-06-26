package ru.arc.ops.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OpsAuthTest : FreeSpec({
    "OpsAuth" - {
        "should accept Bearer token" {
            val headers = mapOf("Authorization" to "Bearer secret-token")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe true
        }

        "should accept X-ARC-Ops-Token header" {
            val headers = mapOf("X-ARC-Ops-Token" to "secret-token")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe true
        }

        "should reject missing token" {
            OpsAuth.isAuthorized(emptyMap(), "secret-token") shouldBe false
        }

        "should reject wrong token" {
            val headers = mapOf("Authorization" to "Bearer wrong")
            OpsAuth.isAuthorized(headers, "secret-token") shouldBe false
        }
    }
})
