package ru.arc.paper.menu

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.TaskScheduler
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuId
import ru.arc.menu.MenuRegionId

/**
 * Owns the active validated configuration and viewer lifecycle for one plugin.
 * Replacements are primary-thread only and close old viewers after swapping the
 * complete generation, so topology and item templates cannot drift apart.
 */
class PaperMenuRuntime(
    plugin: Plugin,
    scheduler: TaskScheduler,
    initial: PaperMenuConfiguration,
) : AutoCloseable {
    private val catalogs = MenuCatalogRepository(initial.catalog)
    private val service = PaperMenuService(plugin, catalogs, scheduler)
    private val cloudStorage = PaperCloudStorageService(plugin, catalogs, scheduler)
    private var configuration = initial
    private var closed = false

    fun current(): PaperMenuConfiguration = configuration

    fun open(
        player: Player,
        menu: MenuId,
        content: () -> PaperMenuContent,
    ): PaperMenuSession = service.open(player, menu, content)

    fun session(player: Player): PaperMenuSession? = service.session(player.uniqueId)

    fun openStorage(
        player: Player,
        menu: MenuId,
        region: MenuRegionId,
        storage: PaperCloudStorage,
        content: PaperCloudStorageContent,
    ): PaperCloudStorageSession = cloudStorage.open(player, menu, region, storage, content)

    fun replace(candidate: PaperMenuConfiguration) {
        requirePrimaryThread()
        check(!closed) { "Paper menu runtime is closed" }
        val replaced = catalogs.replace(candidate.catalog)
        configuration = candidate.copy(catalog = replaced.current)
        service.closeSessions()
        cloudStorage.closeSessions()
    }

    override fun close() {
        requirePrimaryThread()
        if (closed) return
        closed = true
        service.close()
        cloudStorage.close()
    }

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "Paper menu runtime must be used on the primary server thread" }
    }
}
