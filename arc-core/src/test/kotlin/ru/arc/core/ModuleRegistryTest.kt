package ru.arc.core

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

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
    }
})
