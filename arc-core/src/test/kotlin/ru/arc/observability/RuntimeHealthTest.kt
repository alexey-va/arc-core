package ru.arc.observability

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class RuntimeHealthTest : FreeSpec({
    "registry aggregates deterministic bounded probes for agent readback" {
        val registry = RuntimeHealthRegistry("arc-events") { 1_234L }
        registry.register("network") {
            RuntimeHealthContribution(
                activeLeases = 3,
                schemas = mapOf("wire" to 2),
                dependencies = mapOf("redis" to true),
            )
        }
        registry.register("recovery") {
            RuntimeHealthContribution(
                state = RuntimeHealthState.DEGRADED,
                recoveryBacklog = 4,
                schemas = mapOf("journal" to 2),
            )
        }
        registry.markReady()

        val snapshot = registry.snapshot()
        snapshot.state shouldBe RuntimeHealthState.DEGRADED
        snapshot.ready shouldBe false
        snapshot.generatedAtMillis shouldBe 1_234L
        snapshot.recoveryBacklog shouldBe 4
        snapshot.activeLeases shouldBe 3
        snapshot.schemas shouldContainExactly mapOf("network.wire" to 2, "recovery.journal" to 2)
        snapshot.dependencies shouldContainExactly mapOf("network.redis" to true)
        snapshot.probes.map(RuntimeHealthProbeSnapshot::id) shouldContainExactly listOf("network", "recovery")

        StructuredRuntimeHealthLine().line(snapshot) shouldBe
            "ARC_HEALTH component=arc-events state=degraded ready=false generated_at_ms=1234 " +
            "recovery_backlog=4 active_leases=3 schemas=network.wire:2,recovery.journal:2 " +
            "dependencies=network.redis:true probes=network:up,recovery:degraded"
    }

    "probe failure fails closed without leaking its exception" {
        val registry = RuntimeHealthRegistry("arc-giveaways") { 9L }
        registry.register("journal") { error("secret-bearing failure") }
        registry.markReady()

        val snapshot = registry.snapshot()
        snapshot.state shouldBe RuntimeHealthState.DOWN
        snapshot.dependencies shouldContainExactly mapOf("journal.probe" to false)
        StructuredRuntimeHealthLine().line(snapshot).contains("secret-bearing") shouldBe false
    }

    "registration handles and close are idempotent" {
        val registry = RuntimeHealthRegistry("arc-farms") { 10L }
        val registration = registry.register("network") { RuntimeHealthContribution() }
        registry.probeCount() shouldBe 1
        registration.close()
        registration.close()
        registry.probeCount() shouldBe 0
        registry.close()
        registry.close()
        registry.snapshot().state shouldBe RuntimeHealthState.DOWN
        shouldThrow<IllegalStateException> {
            registry.register("late") { RuntimeHealthContribution() }
        }
    }

    "health values reject unbounded or unstable names" {
        shouldThrow<IllegalArgumentException> { RuntimeHealthRegistry("Bad Component") }
        shouldThrow<IllegalArgumentException> {
            RuntimeHealthContribution(recoveryBacklog = -1)
        }
        shouldThrow<IllegalArgumentException> {
            RuntimeHealthContribution(schemas = mapOf("Bad Schema" to 1))
        }
    }
})
