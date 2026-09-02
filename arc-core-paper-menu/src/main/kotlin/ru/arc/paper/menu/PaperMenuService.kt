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
import org.bukkit.plugin.Plugin
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuId
import java.util.UUID

class PaperMenuService(
    private val plugin: Plugin,
    private val catalogs: MenuCatalogRepository,
    private val scheduler: TaskScheduler,
) : Listener, AutoCloseable {
    private val sessions = linkedMapOf<UUID, PaperMenuSession>()
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun open(player: Player, menu: MenuId, content: () -> PaperMenuContent): PaperMenuSession {
        requirePrimaryThread()
        check(!closed) { "Paper menu service is closed" }
        val catalog = catalogs.current()
        val session = PaperMenuSession(
            plugin = plugin,
            catalogs = catalogs,
            menuId = menu,
            player = player,
            generation = catalog.generation,
            layout = catalog.require(menu),
            contentProvider = content,
            scheduler = scheduler,
            onClosed = ::remove,
        )
        try {
            session.prepare()
        } catch (failure: RuntimeException) {
            session.discardUnopened()
            throw failure
        }
        sessions.remove(player.uniqueId)?.close()
        sessions[player.uniqueId] = session
        session.show()
        return session
    }

    fun session(playerId: UUID): PaperMenuSession? = sessions[playerId]?.takeIf(PaperMenuSession::isOpen)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory !== session.inventory) return
        event.isCancelled = true
        session.handleClick(event)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) {
        val session = sessions[event.whoClicked.uniqueId] ?: return
        if (event.view.topInventory !== session.inventory) return
        if (event.rawSlots.any { it in 0 until session.inventory.size }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onClose(event: InventoryCloseEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (event.view.topInventory === session.inventory) session.close()
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        val active = sessions.values.toList()
        sessions.clear()
        active.forEach(PaperMenuSession::close)
    }

    private fun remove(session: PaperMenuSession) {
        sessions.remove(session.player.uniqueId, session)
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Paper menu services must be used on the primary server thread" }
    }
}
