package ru.arc.paper.display

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SharedDisplayPassengersTest : StringSpec({
    "native replacements and every observer share admitted ownership without promoting fake IDs to native" {
        SharedDisplayPassengers().use { service ->
            val channel = Any()
            val a = service.open(channel)
            val b = service.open(channel)
            a.seedNative(77, listOf(11))
            b.seedNative(77, listOf(11))
            a.setMounted(77, listOf(100))
            b.nativePassengers(77, emptyList()) shouldBe listOf(11, 100)
            b.setMounted(77, listOf(200))
            a.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12, 100, 200)
            b.observeNative(77, intArrayOf(12, 100, 200)).toList() shouldBe listOf(12, 100, 200)
            a.duringOwnPassengerWrites(setOf(77)) {
                b.isOwnPassengerWrite(77) shouldBe true
                b.observeNative(77, intArrayOf(999)).toList() shouldBe listOf(999)
            }
            a.nativePassengers(77, emptyList()) shouldBe listOf(12, 200)
            b.nativePassengers(77, emptyList()) shouldBe listOf(12, 100)
            a.setMounted(77, emptyList()); a.close()
            b.nativePassengers(77, emptyList()) shouldBe listOf(12)
            b.setMounted(77, emptyList()); b.close()
        }
    }

    "vehicle invalidation and connection loss notify all owners but a fresh channel cannot inherit stale links" {
        SharedDisplayPassengers().use { service ->
            val channel = Any()
            val a = service.open(channel)
            val b = service.open(channel)
            a.seedNative(77, listOf(11)); b.seedNative(77, listOf(11))
            a.setMounted(77, listOf(100)); b.setMounted(77, listOf(200))
            a.forgetVehicles(intArrayOf(77))
            a.invalidatedVehicles() shouldBe setOf(77)
            b.invalidatedVehicles() shouldBe setOf(77)
            b.isDead(77) shouldBe true
            a.seedNative(77, listOf(12))
            a.nativePassengers(77, emptyList()) shouldBe listOf(12)
            a.acknowledgeInvalidations(setOf(77))
            b.invalidatedVehicles() shouldBe setOf(77)
            a.closeConnection()
            b.isDisconnected() shouldBe true
            shouldThrow<IllegalStateException> { service.open(channel) }
            a.close(); b.close()
            val next = service.open(Any())
            next.nativePassengers(77, listOf(13)) shouldBe listOf(13)
            next.close()
        }
    }

    "deferred admission keeps observing native changes and bounded owner slots are reclaimed" {
        SharedDisplayPassengers().use { service ->
            val channel = Any()
            val first = service.open(channel)
            first.seedNative(77, listOf(11))
            first.setMounted(77, listOf(100))
            first.setMounted(77, emptyList()) // rejected admission
            first.observeNative(77, intArrayOf(12)).toList() shouldBe listOf(12)
            first.nativePassengers(77, emptyList()) shouldBe listOf(12)
            val rest = (1 until 256).map { service.open(channel) }
            shouldThrow<IllegalStateException> { service.open(channel) }
            first.close()
            service.open(channel).close()
            rest.forEach { it.close() }
        }
    }
})
