package ru.arc.paper.scene

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntityInfo
import ru.arc.paper.api.ArcSceneEntityInfoService
import ru.arc.paper.api.ArcSceneEntityInfoSource
import java.util.Collections

/** Host-owned Main-only public information registry; callbacks never survive source/host close. */
class PaperSceneEntityInfoServiceProvider(private val host: Plugin) : ArcSceneEntityInfoService, AutoCloseable, Listener {
    private data class Entry(val plugin: Plugin, val key: String, val source: ArcSceneEntityInfoSource)
    private val sources = linkedMapOf<Long, Entry>()
    private var sequence = 0L
    private var closed = false

    init {
        checkThread()
        val manager = host.server.servicesManager
        check(manager.load(ArcSceneEntityInfoService::class.java) == null) { "An ArcSceneEntityInfoService provider is already registered" }
        try {
            manager.register(ArcSceneEntityInfoService::class.java, this, host, ServicePriority.Normal)
            host.server.pluginManager.registerEvents(this, host)
        } catch (failure: Throwable) {
            manager.unregister(ArcSceneEntityInfoService::class.java, this)
            HandlerList.unregisterAll(this)
            throw failure
        }
    }

    override fun registerSource(plugin: Plugin, key: String, source: ArcSceneEntityInfoSource): AutoCloseable {
        checkOpen()
        require(plugin.isEnabled) { "Scene info source plugin must be enabled" }
        require(key.length in 1..80 && key.all { it.isLetterOrDigit() || it in "_.:-" }) { "Scene info source key must be a stable token of 1..80 characters" }
        require(sources.values.none { it.plugin === plugin && it.key == key }) { "Scene info source key is already registered for this plugin" }
        check(sources.size < 8) { "Scene info source registry is full" }
        val id = ++sequence
        sources[id] = Entry(plugin, key, source)
        // A retained lease holds only the registry and numeric ID, never the consumer callback.
        return AutoCloseable { checkThread(); sources.remove(id); Unit }
    }

    override fun information(bounds: ArcSceneBounds, entityIds: Set<String>, limit: Int): List<ArcSceneEntityInfo> {
        checkOpen()
        require(limit in 1..64) { "Scene info query limit must be 1..64" }
        require(entityIds.size <= 64 && entityIds.all { it.length in 1..128 }) { "Scene info queries require at most 64 IDs of 1..128 characters" }
        if (entityIds.isEmpty()) return emptyList()
        val requested = Collections.unmodifiableSet(LinkedHashSet(entityIds))
        val result = ArrayList<ArcSceneEntityInfo>(limit)
        val seen = hashSetOf<String>()
        for ((id, entry) in sources.toMap()) {
            if (sources[id] !== entry) continue
            if (!entry.plugin.isEnabled) { sources.remove(id); continue }
            val remaining = limit - result.size
            val values = entry.source.snapshot(bounds, requested, remaining)
            if (closed) break
            if (sources[id] !== entry || !entry.plugin.isEnabled) continue
            check(values.size <= remaining) { "Scene info source exceeded its requested limit" }
            for (value in values) if (value.entityId in requested && seen.add(value.entityId)) result.add(value)
            if (result.size == limit) break
        }
        return Collections.unmodifiableList(result)
    }

    @EventHandler fun onPluginDisable(event: PluginDisableEvent) {
        checkThread()
        sources.values.removeIf { it.plugin === event.plugin }
    }

    override fun close() {
        checkThread()
        if (closed) return
        closed = true
        sources.clear()
        host.server.servicesManager.unregister(ArcSceneEntityInfoService::class.java, this)
        HandlerList.unregisterAll(this)
    }

    private fun checkOpen() { checkThread(); check(!closed) { "Scene entity info provider is closed" } }
    private fun checkThread() { check(Bukkit.isPrimaryThread()) { "Scene entity information requires Paper's primary thread" } }
}
