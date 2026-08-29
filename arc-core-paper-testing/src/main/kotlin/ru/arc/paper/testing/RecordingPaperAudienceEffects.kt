package ru.arc.paper.testing

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import ru.arc.paper.audience.NativePaperAudienceEffects
import ru.arc.paper.audience.PaperAudienceEffects
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records transient Paper UI effects while still delegating to the native API by default.
 *
 * The recorder is intentionally test-only and observes the delivery port independently from a
 * particular Player mock implementation. Supported native calls still reach MockBukkit so tests
 * can assert both the requested effect and its platform-facing result.
 */
class RecordingPaperAudienceEffects(
    private val delegate: PaperAudienceEffects = NativePaperAudienceEffects,
) : PaperAudienceEffects {
    private val recorded = CopyOnWriteArrayList<PaperAudienceEffectObservation>()

    fun observations(): List<PaperAudienceEffectObservation> = recorded.toList()

    fun clear() = recorded.clear()

    override fun sendMessage(player: Player, message: Component) {
        recorded += PaperAudienceEffectObservation.Message(player.uniqueId, message)
        delegate.sendMessage(player, message)
    }

    override fun sendActionBar(player: Player, message: Component) {
        recorded += PaperAudienceEffectObservation.ActionBar(player.uniqueId, message)
        delegate.sendActionBar(player, message)
    }

    override fun showTitle(player: Player, title: Title) {
        recorded += PaperAudienceEffectObservation.TitleShown(player.uniqueId, title)
        delegate.showTitle(player, title)
    }

    override fun showBossBar(player: Player, bossBar: BossBar) {
        recorded += PaperAudienceEffectObservation.BossBarShown(player.uniqueId, PaperBossBarSnapshot.capture(bossBar))
        delegate.showBossBar(player, bossBar)
    }

    override fun hideBossBar(player: Player, bossBar: BossBar) {
        recorded += PaperAudienceEffectObservation.BossBarHidden(player.uniqueId, PaperBossBarSnapshot.capture(bossBar))
        delegate.hideBossBar(player, bossBar)
    }
}

/** Immutable call-time view; later mutation of the live bossbar cannot rewrite test history. */
data class PaperBossBarSnapshot(
    val name: Component,
    val progress: Float,
    val color: BossBar.Color,
    val overlay: BossBar.Overlay,
    val flags: Set<BossBar.Flag>,
) {
    companion object {
        fun capture(bossBar: BossBar) = PaperBossBarSnapshot(
            name = bossBar.name(),
            progress = bossBar.progress(),
            color = bossBar.color(),
            overlay = bossBar.overlay(),
            flags = bossBar.flags().toSet(),
        )
    }
}

sealed interface PaperAudienceEffectObservation {
    val playerId: UUID

    data class Message(override val playerId: UUID, val message: Component) : PaperAudienceEffectObservation

    data class ActionBar(override val playerId: UUID, val message: Component) : PaperAudienceEffectObservation

    data class TitleShown(override val playerId: UUID, val title: Title) : PaperAudienceEffectObservation

    data class BossBarShown(override val playerId: UUID, val bossBar: PaperBossBarSnapshot) : PaperAudienceEffectObservation

    data class BossBarHidden(override val playerId: UUID, val bossBar: PaperBossBarSnapshot) : PaperAudienceEffectObservation
}
