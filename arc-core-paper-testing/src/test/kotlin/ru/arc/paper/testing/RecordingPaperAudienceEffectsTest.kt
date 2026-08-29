package ru.arc.paper.testing

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title

class RecordingPaperAudienceEffectsTest : FreeSpec({
    "records every transient effect while supported calls still reach PlayerMock" {
        MockBukkitTestRuntime.open().use { runtime ->
            val player = runtime.addPlayer("Viewer")
            val effects = RecordingPaperAudienceEffects()
            val message = Component.text("Giveaway opened")
            val actionBar = Component.text("Countdown")
            val title = Title.title(Component.text("5"), Component.text("seconds"))
            val bossBar = BossBar.bossBar(
                Component.text("Giveaway"),
                0.5f,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS,
            )

            effects.sendMessage(player, message)
            effects.sendActionBar(player, actionBar)
            effects.showTitle(player, title)
            effects.showBossBar(player, bossBar)
            bossBar.name(Component.text("Drawing"))
            bossBar.progress(0.1f)
            bossBar.color(BossBar.Color.RED)
            bossBar.addFlag(BossBar.Flag.DARKEN_SCREEN)
            effects.hideBossBar(player, bossBar)

            effects.observations() shouldBe listOf(
                PaperAudienceEffectObservation.Message(player.uniqueId, message),
                PaperAudienceEffectObservation.ActionBar(player.uniqueId, actionBar),
                PaperAudienceEffectObservation.TitleShown(player.uniqueId, title),
                PaperAudienceEffectObservation.BossBarShown(
                    player.uniqueId,
                    PaperBossBarSnapshot(
                        Component.text("Giveaway"),
                        0.5f,
                        BossBar.Color.YELLOW,
                        BossBar.Overlay.PROGRESS,
                        emptySet(),
                    ),
                ),
                PaperAudienceEffectObservation.BossBarHidden(
                    player.uniqueId,
                    PaperBossBarSnapshot(
                        Component.text("Drawing"),
                        0.1f,
                        BossBar.Color.RED,
                        BossBar.Overlay.PROGRESS,
                        setOf(BossBar.Flag.DARKEN_SCREEN),
                    ),
                ),
            )
            player.nextComponentMessage() shouldBe message
            player.nextActionBar() shouldBe actionBar
            runtime.adventureTitles(player) shouldBe listOf(title)
            player.bossBars shouldBe emptySet()
        }
    }
})
