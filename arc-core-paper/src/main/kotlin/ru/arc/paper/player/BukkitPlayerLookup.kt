package ru.arc.paper.player

import org.bukkit.Bukkit
import ru.arc.player.PlayerLookup
import java.util.UUID

class BukkitPlayerLookup : PlayerLookup {
    override fun findUuid(name: String): UUID? =
        Bukkit.getPlayerExact(name)?.uniqueId ?: Bukkit.getOfflinePlayer(name).uniqueId.takeIf {
            Bukkit.getOfflinePlayer(it).hasPlayedBefore()
        }

    override fun findName(uuid: UUID): String? =
        Bukkit.getPlayer(uuid)?.name ?: Bukkit.getOfflinePlayer(uuid).name

    override fun isOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid) != null

    override fun onlineNames(): Collection<String> =
        Bukkit.getOnlinePlayers().map { it.name }

    override fun hasPermission(uuid: UUID, permission: String): Boolean =
        Bukkit.getPlayer(uuid)?.hasPermission(permission) == true
}
