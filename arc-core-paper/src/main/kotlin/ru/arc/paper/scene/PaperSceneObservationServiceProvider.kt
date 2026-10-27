package ru.arc.paper.scene

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneChunkListener
import ru.arc.paper.api.ArcSceneEntity
import ru.arc.paper.api.ArcSceneEntitySource
import ru.arc.paper.api.ArcSceneLocation
import ru.arc.paper.api.ArcSceneLocationSource
import ru.arc.paper.api.ArcSceneObservationService
import java.util.Collections
import java.util.UUID
import java.util.logging.Level

/** Main-thread-only bounded registry. No native state or consumer callback survives host close. */
class PaperSceneObservationServiceProvider(private val host: Plugin) : ArcSceneObservationService, AutoCloseable, Listener {
    private data class Entry<T>(val plugin: Plugin, val key: String, val source: T)
    private val entitySources = linkedMapOf<Long, Entry<ArcSceneEntitySource>>()
    private val locationSources = linkedMapOf<Long, Entry<ArcSceneLocationSource>>()
    private val chunkListeners = linkedMapOf<Long, Entry<ArcSceneChunkListener>>()
    private var sequence = 0L
    private var closed = false

    init {
        checkThread()
        val manager = host.server.servicesManager
        check(manager.load(ArcSceneObservationService::class.java) == null) { "An ArcSceneObservationService provider is already registered" }
        try {
            manager.register(ArcSceneObservationService::class.java, this, host, ServicePriority.Normal)
            host.server.pluginManager.registerEvents(this, host)
        } catch (failure: Throwable) {
            manager.unregister(ArcSceneObservationService::class.java, this)
            HandlerList.unregisterAll(this)
            throw failure
        }
    }

    override fun registerEntitySource(plugin: Plugin, key: String, source: ArcSceneEntitySource): AutoCloseable =
        register(entitySources, 32, plugin, key, source)

    override fun registerLocationSource(plugin: Plugin, key: String, source: ArcSceneLocationSource): AutoCloseable =
        register(locationSources, 8, plugin, key, source)

    override fun registerChunkListener(plugin: Plugin, key: String, listener: ArcSceneChunkListener): AutoCloseable =
        register(chunkListeners, 8, plugin, key, listener)

    override fun chunkChanged(worldId: UUID, chunkX: Int, chunkZ: Int) {
        checkOpen()
        for ((id, entry) in chunkListeners.toMap()) {
            if (closed || chunkListeners[id] !== entry) continue
            if (!entry.plugin.isEnabled) { chunkListeners.remove(id); continue }
            try { entry.source.changed(worldId, chunkX, chunkZ) }
            catch (failure: Throwable) {
                chunkListeners.remove(id)
                host.logger.log(Level.WARNING, "Public scene chunk listener failed; removed source=${entry.key} plugin=${entry.plugin.name}", failure)
            }
        }
    }

    private fun <T> register(entries: MutableMap<Long, Entry<T>>, maximum: Int, plugin: Plugin, key: String, source: T): AutoCloseable {
        checkOpen()
        require(plugin.isEnabled) { "Scene source plugin must be enabled" }
        require(key.length in 1..80 && key.all { it.isLetterOrDigit() || it in "_.:-" }) { "Scene source key must be a stable token of 1..80 characters" }
        require(entries.values.none { it.plugin === plugin && it.key == key }) { "Scene source key is already registered for this plugin" }
        check(entries.size < maximum) { "Scene source registry is full" }
        val id = ++sequence
        entries[id] = Entry(plugin, key, source)
        // The lease retains only the registry and numeric identity, never the consumer callback.
        return AutoCloseable { checkThread(); entries.remove(id); Unit }
    }

    override fun entities(bounds: ArcSceneBounds, limit: Int): List<ArcSceneEntity> {
        checkOpen()
        require(limit in 1..512) { "Scene query limit must be 1..512" }
        val result = ArrayList<ArcSceneEntity>(limit)
        val ids = hashSetOf<String>()
        for ((id, entry) in entitySources.toMap()) {
            if (entitySources[id] !== entry) continue
            if (!entry.plugin.isEnabled) { entitySources.remove(id); continue }
            val remaining = limit - result.size
            val values = entry.source.snapshot(bounds, remaining)
            if (closed) break
            if (entitySources[id] !== entry || !entry.plugin.isEnabled) continue
            check(values.size <= remaining) { "Scene entity source exceeded its requested limit" }
            for (value in values) {
                if (bounds.contains(value.worldId, value.x, value.y, value.z) && ids.add(value.id)) result.add(value)
            }
            if (result.size == limit) break
        }
        return Collections.unmodifiableList(result)
    }

    override fun locations(limit: Int): List<ArcSceneLocation> {
        checkOpen()
        require(limit in 1..512) { "Scene query limit must be 1..512" }
        val result = ArrayList<ArcSceneLocation>(limit)
        val ids = hashSetOf<String>()
        for ((id, entry) in locationSources.toMap()) {
            if (locationSources[id] !== entry) continue
            if (!entry.plugin.isEnabled) { locationSources.remove(id); continue }
            val values = entry.source.locations(limit - result.size)
            if (closed) break
            if (locationSources[id] !== entry || !entry.plugin.isEnabled) continue
            check(values.size <= limit - result.size) { "Scene location source exceeded its requested limit" }
            for (value in values) if (ids.add(value.id)) result.add(value)
            if (result.size == limit) break
        }
        return Collections.unmodifiableList(result)
    }

    @EventHandler fun onPluginDisable(event: PluginDisableEvent) {
        checkThread()
        entitySources.values.removeIf { it.plugin === event.plugin }
        locationSources.values.removeIf { it.plugin === event.plugin }
        chunkListeners.values.removeIf { it.plugin === event.plugin }
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        entitySources.clear()
        locationSources.clear()
        chunkListeners.clear()
        host.server.servicesManager.unregister(ArcSceneObservationService::class.java, this)
        HandlerList.unregisterAll(this)
    }

    private fun checkOpen() { checkThread(); check(!closed) { "Scene observation provider is closed" } }
    private fun checkThread() { check(Bukkit.isPrimaryThread()) { "Scene observations require Paper's primary thread" } }
}
