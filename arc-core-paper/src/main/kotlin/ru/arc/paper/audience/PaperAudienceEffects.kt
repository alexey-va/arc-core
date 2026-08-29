package ru.arc.paper.audience

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player

/**
 * Narrow owner for transient Paper UI effects.
 *
 * Gameplay decides what to render and when; this port only delivers the exact Adventure payload
 * to one player. Keeping delivery behind one stable seam lets platform tests observe APIs that a
 * specific MockBukkit release does not retain without weakening the production default.
 */
interface PaperAudienceEffects {
    fun sendMessage(player: Player, message: Component)

    fun sendActionBar(player: Player, message: Component)

    fun showTitle(player: Player, title: Title)

    fun showBossBar(player: Player, bossBar: BossBar)

    fun hideBossBar(player: Player, bossBar: BossBar)
}

/** Production delivery bound directly to Paper's Adventure audience API. */
object NativePaperAudienceEffects : PaperAudienceEffects {
    override fun sendMessage(player: Player, message: Component) = player.sendMessage(message)

    override fun sendActionBar(player: Player, message: Component) = player.sendActionBar(message)

    override fun showTitle(player: Player, title: Title) = player.showTitle(title)

    override fun showBossBar(player: Player, bossBar: BossBar) = player.showBossBar(bossBar)

    override fun hideBossBar(player: Player, bossBar: BossBar) = player.hideBossBar(bossBar)
}
