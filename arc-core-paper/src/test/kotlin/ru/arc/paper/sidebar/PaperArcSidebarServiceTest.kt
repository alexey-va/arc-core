package ru.arc.paper.sidebar

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Team
import ru.arc.paper.api.ArcSidebarFrame
import ru.arc.paper.api.ArcSidebarPriorities
import ru.arc.paper.api.ArcSidebarSelectionSnapshot
import ru.arc.paper.testing.MockBukkitTestRuntime

class PaperArcSidebarServiceTest : FreeSpec({
    "highest active priority wins and closing it reveals the lower source" {
        MockBukkitTestRuntime.open().use { runtime ->
            val host = runtime.createSimplePlugin("ARC")
            val farms = runtime.createSimplePlugin("ArcFarms")
            val player = runtime.addPlayer("Farmer")
            val service = PaperArcSidebarService(host)
            val base = service.register(host, "base", ArcSidebarPriorities.BASE)
            val activity = service.register(farms, "worksite", ArcSidebarPriorities.ACTIVITY)

            base.show(player, frame("Base", "Online"))
            activity.show(player, frame("Farm", "Harvest"))

            service.active(player.uniqueId) shouldBe
                ArcSidebarSelectionSnapshot("ArcFarms", "worksite", ArcSidebarPriorities.ACTIVITY)
            player.scoreboard.getObjective(DisplaySlot.SIDEBAR)!!.displayName() shouldBe Component.text("Farm")

            activity.close()

            service.active(player.uniqueId) shouldBe
                ArcSidebarSelectionSnapshot("ARC", "base", ArcSidebarPriorities.BASE)
            player.scoreboard.getObjective(DisplaySlot.SIDEBAR)!!.displayName() shouldBe Component.text("Base")
            service.close()
        }
    }

    "dynamic and blank rows stay distinct and stale rows are removed" {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ARC")
            val player = runtime.addPlayer("Rows")
            val service = PaperArcSidebarService(plugin)
            val source = service.register(plugin, "dungeon", ArcSidebarPriorities.DUNGEON)

            source.show(
                player,
                ArcSidebarFrame(
                    Component.text("Dungeon"),
                    listOf(Component.text("Party"), Component.empty(), Component.empty(), Component.text("Mana")),
                ),
            )
            player.scoreboard.entries.size shouldBe 4

            source.show(player, frame("Dungeon", "Mana"))
            player.scoreboard.entries.size shouldBe 1
            service.close()
        }
    }

    "event frame can hide participant name tags on the same physical scoreboard" {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ArcEvents")
            val player = runtime.addPlayer("Viewer")
            val service = PaperArcSidebarService(plugin)
            val source = service.register(plugin, "ttt", ArcSidebarPriorities.EVENT)

            source.show(
                player,
                ArcSidebarFrame(
                    Component.text("TTT"),
                    listOf(Component.text("Role")),
                    setOf("Alpha", "Bravo"),
                ),
            )

            val team = player.scoreboard.getTeam("arc_hidden_names")!!
            team.getOption(Team.Option.NAME_TAG_VISIBILITY) shouldBe Team.OptionStatus.NEVER
            team.entries.sorted() shouldContainExactly listOf("Alpha", "Bravo")
            service.close()
        }
    }

    "registration snapshots expose deterministic priority order" {
        MockBukkitTestRuntime.open().use { runtime ->
            val plugin = runtime.createSimplePlugin("ARC")
            val service = PaperArcSidebarService(plugin)
            service.register(plugin, "base", ArcSidebarPriorities.BASE)
            service.register(plugin, "dungeon", ArcSidebarPriorities.DUNGEON)

            service.registrations().map { it.id } shouldContainExactly listOf("dungeon", "base")
            service.close()
        }
    }
})

private fun frame(title: String, row: String): ArcSidebarFrame =
    ArcSidebarFrame(Component.text(title), listOf(Component.text(row)))
