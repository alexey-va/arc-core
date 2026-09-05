package ru.arc.paper.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId
import java.util.UUID
import java.util.logging.Level

/** One listener and one current cloud chest per viewer; owned by PaperMenuRuntime. */
internal class PaperCloudStorageService(
    private val plugin: Plugin,
    private val catalogs: MenuCatalogRepository,
    private val scheduler: TaskScheduler,
) : Listener, AutoCloseable {
    private val sessions = mutableMapOf<UUID, PaperCloudStorageSession>()
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun open(player: Player, menu: MenuId, region: MenuRegionId, storage: PaperCloudStorage, content: PaperCloudStorageContent): PaperCloudStorageSession {
        check(Bukkit.isPrimaryThread())
        check(!closed) { "Cloud storage service is closed" }
        val session = PaperCloudStorageSession(player, catalogs.current().require(menu), region, storage, content, scheduler)
        sessions.remove(player.uniqueId)?.close()
        player.openInventory(session.inventory)
        if (player.openInventory.topInventory === session.inventory) sessions[player.uniqueId] = session else session.closed()
        return session
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PaperCloudStorageSession ?: return
        if (!holder.isOpen || holder.player.uniqueId != event.whoClicked.uniqueId) {
            event.isCancelled = true
            return
        }
        val session = sessions[event.whoClicked.uniqueId]
        if (session !== holder) { event.isCancelled = true; return }
        try {
            session.click(event)
        } catch (failure: Exception) {
            event.isCancelled = true
            // Do not retry an unknown backing commit. Keep the inventory frozen.
            session.closed()
            plugin.logger.log(Level.SEVERE, "Cloud storage click failed; session disabled", failure)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) {
        val holder = event.view.topInventory.holder as? PaperCloudStorageSession ?: return
        if (!holder.isOpen || holder.player.uniqueId != event.whoClicked.uniqueId) {
            event.isCancelled = true
            return
        }
        val session = sessions[event.whoClicked.uniqueId]
        if (session !== holder || event.rawSlots.any { it < holder.inventory.size }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (event.view.topInventory === session.inventory) sessions.remove(event.player.uniqueId)?.closed()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId)?.closed() }

    fun closeSessions() {
        check(Bukkit.isPrimaryThread())
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach { it.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeSessions()
        HandlerList.unregisterAll(this)
    }
}
