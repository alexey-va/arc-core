package ru.arc.config

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import ru.arc.core.BoundedAsyncExecutor
import org.snakeyaml.engine.v2.nodes.MappingNode

class ConfigManagerTest : FreeSpec({
    "a prepared reload cannot erase an acknowledged concurrent edit" {
        val root = Files.createTempDirectory("arc-config-stale-reload")
        val worker = BoundedAsyncExecutor("config-stale-test")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        try {
            Files.writeString(root.resolve("feature.yml"), "value: original\n")
            val config = object : Config(root, "feature.yml") {
                override fun prepareReload(): MappingNode {
                    val candidate = super.prepareReload()
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    return candidate
                }
            }
            val reload = worker.submit { config.reload() }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            config.setString("value", "acknowledged edit")
            config.saveStrict()
            unblock.countDown()
            runCatching { reload.get(5, TimeUnit.SECONDS) }
            reload.isCompletedExceptionally shouldBe true
            config.string("value", "") shouldBe "acknowledged edit"
            Files.readString(root.resolve("feature.yml")).contains("acknowledged edit") shouldBe true
        } finally {
            unblock.countDown()
            worker.closeAsync().get(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }

    "a delayed reload source never owns the ready getter lock" {
        val root = Files.createTempDirectory("arc-config-delayed")
        val worker = BoundedAsyncExecutor("config-reload-test")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        try {
            Files.writeString(root.resolve("feature.yml"), "value: previous\n")
            val config = object : Config(root, "feature.yml") {
                override fun prepareReload(): MappingNode {
                    entered.countDown()
                    check(unblock.await(5, TimeUnit.SECONDS))
                    return super.prepareReload()
                }
            }
            Files.writeString(root.resolve("feature.yml"), "value: next\n")
            val reload = worker.submit { config.reload() }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            config.string("value", "") shouldBe "previous"
            reload.isDone shouldBe false
            unblock.countDown()
            reload.get(5, TimeUnit.SECONDS)
            config.string("value", "") shouldBe "next"
        } finally {
            unblock.countDown()
            worker.closeAsync().get(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }

    "prepared module resolution stays memory-only until explicit worker reload" {
        val root = Files.createTempDirectory("arc-module-path")
        try {
            ConfigManager.clear()
            Files.writeString(root.resolve("feature.yml"), "value: legacy\n")
            ConfigManager.ofModule(root, "feature.yml").string("value", "") shouldBe "legacy"
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(root.resolve("modules/feature.yml"), "value: modular\n")
            // A ready lookup must not inspect changed filesystem metadata.
            ConfigManager.moduleYamlRelative(root, "feature.yml") shouldBe "feature.yml"
            ConfigManager.ofModule(root, "feature.yml").string("value", "") shouldBe "legacy"
            ConfigManager.reloadAll()
            ConfigManager.moduleYamlRelative(root, "feature.yml") shouldBe "modules/feature.yml"
            ConfigManager.ofModule(root, "feature.yml").string("value", "") shouldBe "modular"
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
