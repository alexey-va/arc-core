package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ModuleRegistryTest : FreeSpec({
    beforeTest { ModuleRegistry.resetForTests() }

    "ModuleRegistry" - {
        "should init modules in priority order" {
            val order = mutableListOf<String>()
            val low = object : PluginModule {
                override val name = "low"
                override val priority = 200
                override fun init() { order.add("low") }
                override fun shutdown() { order.add("shutdown-low") }
            }
            val high = object : PluginModule {
                override val name = "high"
                override val priority = 10
                override fun init() { order.add("high") }
                override fun shutdown() { order.add("shutdown-high") }
            }

            ModuleRegistry.registerAll(high, low)
            ModuleRegistry.initAll()
            order.take(2) shouldBe listOf("high", "low")

            ModuleRegistry.shutdownAll()
            order.drop(2) shouldBe listOf("shutdown-low", "shutdown-high")
        }

        "should skip disabled modules" {
            var called = false
            ModuleRegistry.register(
                object : PluginModule {
                    override val name = "off"
                    override val enabled = false
                    override fun init() { called = true }
                    override fun shutdown() {}
                },
            )
            ModuleRegistry.initAll()
            called shouldBe false
            ModuleRegistry.getRuntimeStatuses() shouldBe emptyList()
            ModuleRegistry.shutdownAll()
        }

        "should notify lifecycle reporter" {
            var initCount = 0
            var completedOk = -1
            ModuleRegistry.lifecycleReporter =
                object : ModuleLifecycleReporter {
                    override fun onInitStart(moduleCount: Int) {
                        initCount = moduleCount
                    }

                    override fun onInitComplete(ok: Int, failed: Int, totalMs: Long) {
                        completedOk = ok
                    }
                }
            ModuleRegistry.register(
                object : PluginModule {
                    override val name = "a"
                    override fun init() {}
                    override fun shutdown() {}
                },
            )
            ModuleRegistry.initAll()
            initCount shouldBe 1
            completedOk shouldBe 1
            ModuleRegistry.shutdownAll()
        }

        "should clean up a failed module once and exclude it from later lifecycle calls" {
            var failedShutdowns = 0
            var failedReloads = 0
            var healthyShutdowns = 0
            var healthyReloads = 0
            val failed =
                object : PluginModule {
                    override val name = "failed"
                    override fun init() {
                        error("boom")
                    }

                    override fun reload() {
                        failedReloads++
                    }

                    override fun shutdown() {
                        failedShutdowns++
                    }
                }
            val healthy =
                object : PluginModule {
                    override val name = "healthy"
                    override fun init() {}

                    override fun reload() {
                        healthyReloads++
                    }

                    override fun shutdown() {
                        healthyShutdowns++
                    }
                }

            ModuleRegistry.registerAll(failed, healthy)
            ModuleRegistry.initAll()
            ModuleRegistry.reloadAll()
            ModuleRegistry.shutdownAll()

            failedShutdowns shouldBe 1
            failedReloads shouldBe 0
            healthyReloads shouldBe 1
            healthyShutdowns shouldBe 1
        }

        "should ignore duplicate module names" {
            var initializedCount = 0
            fun module() =
                object : PluginModule {
                    override val name = "same-name"
                    override fun init() {
                        initializedCount++
                    }

                    override fun shutdown() {}
                }

            ModuleRegistry.registerAll(module(), module())
            ModuleRegistry.getModules() shouldHaveSize 1
            ModuleRegistry.initAll()
            initializedCount shouldBe 1
            ModuleRegistry.shutdownAll()
        }

        "should not shut down modules before initialization" {
            var shutdowns = 0
            ModuleRegistry.register(
                object : PluginModule {
                    override val name = "not-started"
                    override fun init() {}
                    override fun shutdown() {
                        shutdowns++
                    }
                },
            )

            ModuleRegistry.shutdownAll()

            shutdowns shouldBe 0
        }

        "should expose bounded lifecycle status for metrics" {
            val module =
                object : PluginModule {
                    override val name = "observable"
                    override fun init() {}

                    override fun reload() {
                        error("reload failed")
                    }

                    override fun shutdown() {}
                }

            ModuleRegistry.register(module)
            ModuleRegistry.initAll()

            ModuleRegistry.getRuntimeStatuses().single().apply {
                name shouldBe "observable"
                ready shouldBe true
                initDurationMs shouldNotBe null
                failures shouldBe 0
            }

            ModuleRegistry.reloadAll()

            ModuleRegistry.getRuntimeStatuses().single().apply {
                ready shouldBe false
                reloadDurationMs shouldNotBe null
                failures shouldBe 1
            }
            ModuleRegistry.shutdownAll()
        }
    }
})
