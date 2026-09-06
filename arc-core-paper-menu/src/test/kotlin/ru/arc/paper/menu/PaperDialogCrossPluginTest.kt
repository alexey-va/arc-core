package ru.arc.paper.menu

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URL
import java.net.URLClassLoader
import java.util.function.Consumer

class PaperDialogCrossPluginTest : FreeSpec({
    "separate plugin classloaders dispatch actual events and restore a fresh parent without closing" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("CrossLoader"))
            every { player.closeDialog() } just Runs
            DialogProbeHandle(paper.createSimplePlugin("DialogA"), player).use { a ->
                DialogProbeHandle(paper.createSimplePlugin("DialogB"), player).use { b ->
                    a.runtimeClass shouldNotBe b.runtimeClass
                    a.screenClass shouldNotBe b.screenClass
                    var dismissedA = 0
                    var dismissedB = 0
                    var value = 120
                    fun root() {
                        a.beginFlow() // Must also preserve the flow when invoked during Back restoration.
                        a.open("saved-$value", Runnable {
                            b.beginFlow() // A's real callback must be visible to B's core copy.
                            b.open("settings", dismiss = Runnable { dismissedB++ })
                        }, Runnable { root() }, Runnable { dismissedA++ })
                    }
                    root()
                    val staleA = a.key("next")
                    a.click("next")
                    b.shown shouldBe listOf("settings")
                    dismissedA shouldBe 0
                    a.clickKey(staleA) // A's obsolete click cannot navigate while B is active.
                    a.open("late-result")
                    a.shown shouldBe listOf("saved-120")
                    b.open("settings", dismiss = Runnable { dismissedB++ })
                    value = 60
                    b.click("back")
                    a.shown shouldBe listOf("saved-120", "saved-60")
                    dismissedB shouldBe 1
                    dismissedA shouldBe 0
                    verify(exactly = 0) { player.closeDialog() }
                    a.click("back")
                    verify(exactly = 1) { player.closeDialog() }
                    dismissedA shouldBe 1
                    a.open("closed-flow-late-result")
                    a.shown.size shouldBe 2
                }
            }
        }
    }

    "a direct foreign root discards ancestors and rejects old completions while loading" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("DirectEntry"))
            every { player.closeDialog() } just Runs
            DialogProbeHandle(paper.createSimplePlugin("DialogA"), player).use { a ->
                DialogProbeHandle(paper.createSimplePlugin("DialogB"), player).use { b ->
                    var restored = 0
                    a.open("root", Runnable { b.open("child") }, Runnable { restored++ })
                    a.click("next")
                    b.beginFlow()
                    a.open("old-completion")
                    a.shown shouldBe listOf("root")
                    b.open("direct")
                    b.click("back")
                    restored shouldBe 0
                    verify(exactly = 1) { player.closeDialog() }
                    b.beginFlow()
                    b.closePlayer() // An explicit close also cancels a pending root.
                    b.open("closed-pending-result")
                    b.shown shouldBe listOf("child", "direct")
                    verify(exactly = 2) { player.closeDialog() }
                }
            }
        }
    }

    "inactive plugin unload preserves the active foreign metadata and removes its own closures" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("InactiveUnload"))
            every { player.closeDialog() } just Runs
            val pluginA = paper.createSimplePlugin("DialogA")
            val pluginB = paper.createSimplePlugin("DialogB")
            DialogProbeHandle(pluginA, player).use { a ->
                DialogProbeHandle(pluginB, player).use { b ->
                    var dismissedA = 0
                    var dismissedB = 0
                    a.open("root", Runnable { b.open("child", dismiss = Runnable { dismissedB++ }) },
                        dismiss = Runnable { dismissedA++ })
                    a.click("next")
                    a.shutdown()
                    player.getMetadata("arc:paper_dialog_history:v1").map { it.owningPlugin } shouldBe listOf(pluginB)
                    dismissedA shouldBe 1
                    dismissedB shouldBe 0
                    verify(exactly = 0) { player.closeDialog() }
                    a.open("after-shutdown")
                    a.shown shouldBe listOf("root")
                    b.click("back")
                    dismissedB shouldBe 1
                    verify(exactly = 1) { player.closeDialog() }
                }
            }
            player.hasMetadata("arc:paper_dialog_history:v1") shouldBe false
        }
    }

    "active shutdown and quit dispose every owner and obsolete runtime keys cannot collide" {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("ActiveUnload"))
            every { player.closeDialog() } just Runs
            val pluginA = paper.createSimplePlugin("DialogA")
            val pluginB = paper.createSimplePlugin("DialogB")
            DialogProbeHandle(pluginA, player).use { a ->
                DialogProbeHandle(pluginB, player).use { b ->
                    var dismissed = 0
                    var oldClicks = 0
                    a.open("root", Runnable { oldClicks++; b.open("child", dismiss = Runnable { dismissed++ }) },
                        dismiss = Runnable { dismissed++ })
                    val obsolete = a.key("next")
                    a.click("next")
                    b.shutdown()
                    dismissed shouldBe 2
                    verify(exactly = 1) { player.closeDialog() }
                    a.open("late")
                    a.shown shouldBe listOf("root")
                    a.shutdown()
                    DialogProbeHandle(pluginA, player).use { replacement ->
                        var newClicks = 0
                        replacement.beginFlow()
                        replacement.open("replacement", Runnable { newClicks++ }, dismiss = Runnable { dismissed++ })
                        replacement.clickKey(obsolete)
                        oldClicks shouldBe 1
                        newClicks shouldBe 0
                        every { player.isOnline } returns false
                        replacement.quit()
                        replacement.open("completion-after-quit")
                        replacement.shown shouldBe listOf("replacement")
                        replacement.click("next")
                        newClicks shouldBe 0
                        dismissed shouldBe 3
                        player.hasMetadata("arc:paper_dialog_history:v1") shouldBe false
                        verify(exactly = 1) { player.closeDialog() }
                    }
                }
            }
        }
    }
})

/** Test driver never casts a child's DTO, Kotlin function or core runtime. */
private class DialogProbeHandle(plugin: Plugin, player: Player) : AutoCloseable {
    private val loader = ChildFirstMenuLoader(arrayOf(
        PaperDialogRuntime::class.java.protectionDomain.codeSource.location,
        IsolatedDialogProbe::class.java.protectionDomain.codeSource.location,
    ), IsolatedDialogProbe::class.java.classLoader)
    private val type = loader.loadClass("ru.arc.paper.menu.IsolatedDialogProbe")
    val shown = mutableListOf<String>()
    private val probe = type.getConstructor(Plugin::class.java, Player::class.java, Consumer::class.java)
        .newInstance(plugin, player, Consumer<String> { shown += it })
    val runtimeClass: Class<*> get() = loader.loadClass("ru.arc.paper.menu.PaperDialogRuntime")
    val screenClass: Class<*> get() = loader.loadClass("ru.arc.paper.menu.PaperDialogScreen")
    fun open(id: String, action: Runnable? = null, reopen: Runnable? = null, dismiss: Runnable? = null) {
        type.getMethod("open", String::class.java, Runnable::class.java, Runnable::class.java, Runnable::class.java, Boolean::class.javaPrimitiveType)
            .invoke(probe, id, action, reopen, dismiss, false)
    }
    fun key(id: String): String = type.getMethod("key", String::class.java).invoke(probe, id) as String
    fun click(id: String) { type.getMethod("click", String::class.java).invoke(probe, id) }
    fun clickKey(key: String) { type.getMethod("clickKey", String::class.java).invoke(probe, key) }
    fun beginFlow() { type.getMethod("beginFlow").invoke(probe) }
    fun closePlayer() { type.getMethod("closePlayer").invoke(probe) }
    fun shutdown() { type.getMethod("shutdown").invoke(probe) }
    fun quit() { type.getMethod("quit").invoke(probe) }
    override fun close() { try { shutdown() } finally { loader.close() } }
}

private class ChildFirstMenuLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("ru.arc.paper.menu.")) synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: try { findClass(name) } catch (_: ClassNotFoundException) { null }
            if (loaded != null) {
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
        return super.loadClass(name, resolve)
    }
}
