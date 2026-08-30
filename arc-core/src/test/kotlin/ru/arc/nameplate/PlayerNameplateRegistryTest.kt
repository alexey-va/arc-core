package ru.arc.nameplate

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PlayerNameplateRegistryTest : FreeSpec({
    val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val health = NameplateLayerKey("arcduels", "health")
    val role = NameplateLayerKey("arcranks", "role")

    "layers compose top-to-bottom by priority and stable key" {
        val registry = PlayerNameplateRegistry()
        registry.upsert(player, NameplateLayer(health, 100, Component.text("20 ❤")))
        registry.upsert(player, NameplateLayer(role, 200, Component.text("Дуэлянт")))
        registry.upsert(
            player,
            NameplateLayer(NameplateLayerKey("arcevents", "state"), 100, Component.text("В бою")),
        )

        val snapshot = registry.snapshot(player)!!
        snapshot.layers.map(NameplateLayer::key) shouldContainExactly listOf(
            role,
            health,
            NameplateLayerKey("arcevents", "state"),
        )
        PlainTextComponentSerializer.plainText().serialize(snapshot.content) shouldBe "Дуэлянт\n20 ❤\nВ бою"
    }

    "same row replaces in place while an identical update is unchanged" {
        val registry = PlayerNameplateRegistry()
        val first = registry.upsert(player, NameplateLayer(health, 10, Component.text("20 ❤")))
            as NameplateUpsertResult.Added
        registry.upsert(player, NameplateLayer(health, 10, Component.text("20 ❤"))) shouldBe
            NameplateUpsertResult.Unchanged(first.revision)

        val replaced = registry.upsert(player, NameplateLayer(health, 10, Component.text("19 ❤")))
            as NameplateUpsertResult.Replaced
        replaced.revision shouldBe first.revision + 1
        PlainTextComponentSerializer.plainText().serialize(registry.snapshot(player)!!.content) shouldBe "19 ❤"
    }

    "target and row capacities reject additions without evicting existing state" {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val registry = PlayerNameplateRegistry(maxTargets = 1, maxLayersPerTarget = 1)
        registry.upsert(player, NameplateLayer(health, 0, Component.text("20")))

        registry.upsert(player, NameplateLayer(role, 0, Component.text("Игрок"))) shouldBe
            NameplateUpsertResult.Rejected(NameplateRejectionReason.LAYER_CAPACITY)
        registry.upsert(other, NameplateLayer(health, 0, Component.text("20"))) shouldBe
            NameplateUpsertResult.Rejected(NameplateRejectionReason.TARGET_CAPACITY)
        registry.targetCount() shouldBe 1
        registry.layerCount() shouldBe 1
    }

    "invalid visible rows are typed rejections" {
        val registry = PlayerNameplateRegistry(maxPlainCharactersPerLayer = 5)

        registry.upsert(player, NameplateLayer(health, 0, Component.empty())) shouldBe
            NameplateUpsertResult.Rejected(NameplateRejectionReason.EMPTY_CONTENT)
        registry.upsert(player, NameplateLayer(health, 0, Component.text("a\nb"))) shouldBe
            NameplateUpsertResult.Rejected(NameplateRejectionReason.MULTILINE_CONTENT)
        registry.upsert(player, NameplateLayer(health, 0, Component.text("123456"))) shouldBe
            NameplateUpsertResult.Rejected(NameplateRejectionReason.CONTENT_TOO_LONG)
        registry.targetCount() shouldBe 0
    }

    "owner cleanup removes only its rows across every player" {
        val other = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val registry = PlayerNameplateRegistry()
        registry.upsert(player, NameplateLayer(health, 0, Component.text("20")))
        registry.upsert(player, NameplateLayer(role, 0, Component.text("Воин")))
        registry.upsert(other, NameplateLayer(health, 0, Component.text("15")))

        registry.clearOwner("arcduels") shouldBe 2
        registry.targetIds() shouldContainExactly setOf(player)
        registry.snapshot(player)!!.layers.map(NameplateLayer::key) shouldContainExactly listOf(role)
        registry.snapshot(other) shouldBe null
    }

    "remove and target cleanup are idempotent" {
        val registry = PlayerNameplateRegistry()
        registry.upsert(player, NameplateLayer(health, 0, Component.text("20")))

        (registry.remove(player, health) is NameplateRemoveResult.Removed) shouldBe true
        registry.remove(player, health) shouldBe NameplateRemoveResult.Absent
        registry.clearTarget(player) shouldBe 0
        registry.targetIds() shouldBe emptySet()
    }

    "concurrent callers publish complete rows without corrupting composition" {
        val registry = PlayerNameplateRegistry(maxLayersPerTarget = 8)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 8).map { index ->
                executor.submit {
                    start.await()
                    registry.upsert(
                        player,
                        NameplateLayer(
                            NameplateLayerKey("plugin$index", "row"),
                            index,
                            Component.text("row-$index"),
                        ),
                    )
                }
            }
            start.countDown()
            futures.forEach { it.get() }

            registry.layerCount() shouldBe 8
            registry.snapshot(player)!!.layers.map { it.priority } shouldBe (7 downTo 0).toList()
        } finally {
            executor.shutdownNow()
        }
    }
})
