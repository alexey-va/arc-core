package ru.arc.paper.teleport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ScopedTeleportAuthorizerTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "authorization is player, world, position, rotation and scope bound" {
        val server = paper.server
        val firstWorld = server.addSimpleWorld("first")
        val secondWorld = server.addSimpleWorld("second")
        val playerId = UUID.randomUUID()
        val destination = Location(firstWorld, 1.0, 64.0, 3.0, 90f, 10f)
        val authorizer = ScopedTeleportAuthorizer()

        authorizer.authorize(playerId, destination) {
            authorizer.isAuthorized(playerId, destination.clone()) shouldBe true
            authorizer.isAuthorized(UUID.randomUUID(), destination) shouldBe false
            authorizer.isAuthorized(playerId, destination.clone().add(0.02, 0.0, 0.0)) shouldBe false
            authorizer.isAuthorized(playerId, Location(secondWorld, 1.0, 64.0, 3.0, 90f, 10f)) shouldBe false
            authorizer.activeAuthorizationCount() shouldBe 1
        }
        authorizer.isAuthorized(playerId, destination) shouldBe false
        authorizer.activeAuthorizationCount() shouldBe 0
    }

    "tolerance is explicit and inclusive" {
        val world = paper.addSimpleWorld("tolerance")
        val id = UUID.randomUUID()
        val destination = Location(world, 0.0, 80.0, 0.0, 0f, 0f)
        val authorizer = ScopedTeleportAuthorizer(TeleportMatchTolerance(0.5, 1f))
        authorizer.authorize(id, destination) {
            authorizer.isAuthorized(id, Location(world, 0.5, 80.0, 0.0, 1f, -1f)) shouldBe true
            authorizer.isAuthorized(id, Location(world, 0.5001, 80.0, 0.0, 0f, 0f)) shouldBe false
        }
    }

    "exceptions and nested attempts cannot leak authorization" {
        val world = paper.addSimpleWorld("cleanup")
        val id = UUID.randomUUID()
        val destination = Location(world, 0.0, 64.0, 0.0)
        val authorizer = ScopedTeleportAuthorizer()
        shouldThrow<IllegalStateException> {
            authorizer.authorize(id, destination) {
                shouldThrow<IllegalStateException> { authorizer.authorize(id, destination) {} }
                error("teleport failed")
            }
        }
        authorizer.activeAuthorizationCount() shouldBe 0
    }

    "rejects non-finite and worldless destinations before opening a scope" {
        val world = paper.addSimpleWorld("invalid")
        val authorizer = ScopedTeleportAuthorizer()
        shouldThrow<IllegalArgumentException> {
            authorizer.authorize(UUID.randomUUID(), Location(world, Double.NaN, 64.0, 0.0)) {}
        }
        shouldThrow<IllegalArgumentException> {
            authorizer.authorize(UUID.randomUUID(), Location(null, 0.0, 64.0, 0.0)) {}
        }
    }
})
