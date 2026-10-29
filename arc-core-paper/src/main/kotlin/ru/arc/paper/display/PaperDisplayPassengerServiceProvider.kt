package ru.arc.paper.display

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcDisplayPassengerOwner
import ru.arc.paper.api.ArcDisplayPassengerService
import java.util.WeakHashMap

/** One host service, so consumer classloaders cannot create competing native passenger baselines. */
class PaperDisplayPassengerServiceProvider(private val host: Plugin) : ArcDisplayPassengerService, AutoCloseable {
    private val state = SharedDisplayPassengers()

    init {
        check(Bukkit.isPrimaryThread()) { "Passenger service installation requires the server thread" }
        val manager = host.server.servicesManager
        check(manager.load(ArcDisplayPassengerService::class.java) == null) { "A display passenger service is already registered" }
        manager.register(ArcDisplayPassengerService::class.java, this, host, ServicePriority.Normal)
    }

    override fun open(connection: Any): ArcDisplayPassengerOwner = state.open(connection)

    override fun close() {
        check(Bukkit.isPrimaryThread()) { "Passenger service shutdown requires the server thread" }
        state.close()
        host.server.servicesManager.unregister(ArcDisplayPassengerService::class.java, this)
    }
}

/** State grows only with live owner/vehicle relations; retired owners and unused vehicles are pruned. */
internal class SharedDisplayPassengers : ArcDisplayPassengerService, AutoCloseable {
    private val connections = WeakHashMap<Any, Connection>()
    private var closed = false

    @Synchronized override fun open(connection: Any): ArcDisplayPassengerOwner {
        check(!closed) { "Display passenger service is closed" }
        val state = connections.getOrPut(connection, ::Connection)
        return state.open()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    private class Vehicle {
        var native = emptyList<Int>()
        var observed = false
        var dead = false
    }

    private class Connection {
        val owners = linkedSetOf<Owner>()
        val vehicles = mutableMapOf<Int, Vehicle>()
        val ownWrites = ThreadLocal<Set<Int>?>()
        @Volatile var disconnected = false

        @Synchronized fun open(): Owner {
            check(!disconnected) { "Display passenger connection is closed" }
            check(owners.size < 256) { "Display passenger connection owner limit exceeded" }
            return Owner(this).also(owners::add)
        }

        fun fakeIds(vehicleId: Int): List<Int> = owners.flatMap { it.mounted[vehicleId].orEmpty() }.distinct()
        fun realIds(vehicleId: Int, input: List<Int>): List<Int> = input.filterNot(fakeIds(vehicleId).toHashSet()::contains).distinct()
        fun prune() { vehicles.keys.removeIf { id -> owners.none { id in it.tracked } } }

        @Synchronized fun disconnect() {
            disconnected = true
            vehicles.clear()
            owners.forEach { it.tracked.clear(); it.mounted.clear(); it.invalidated.clear() }
            owners.clear()
        }
    }

    private class Owner(private val connection: Connection) : ArcDisplayPassengerOwner {
        val tracked = mutableSetOf<Int>()
        val mounted = mutableMapOf<Int, List<Int>>()
        val invalidated = mutableSetOf<Int>()
        private var closed = false

        private fun track(vehicleId: Int): Vehicle {
            check(!closed && !connection.disconnected) { "Display passenger owner is closed" }
            check(vehicleId in tracked || tracked.size < 4096) { "Display passenger owner vehicle limit exceeded" }
            tracked += vehicleId
            return connection.vehicles.getOrPut(vehicleId, ::Vehicle)
        }

        override fun seedNative(vehicleId: Int, passengers: List<Int>) = synchronized(connection) {
            if (closed || connection.disconnected) return@synchronized
            val vehicle = track(vehicleId)
            vehicle.dead = false
            if (!vehicle.observed) vehicle.native = connection.realIds(vehicleId, passengers)
        }

        override fun nativePassengers(vehicleId: Int, fallback: List<Int>): List<Int> = synchronized(connection) {
            if (closed || connection.disconnected) return@synchronized emptyList()
            val previous = connection.vehicles[vehicleId]
            val vehicle = track(vehicleId)
            if (vehicle.dead) return@synchronized emptyList()
            if (previous == null) vehicle.native = connection.realIds(vehicleId, fallback)
            (vehicle.native + connection.owners.filterNot { it === this }.flatMap { it.mounted[vehicleId].orEmpty() }).distinct()
        }

        override fun observeNative(vehicleId: Int, passengers: IntArray): IntArray = synchronized(connection) {
            if (closed || connection.disconnected || isOwnPassengerWrite(vehicleId)) return@synchronized passengers
            val vehicle = connection.vehicles[vehicleId] ?: return@synchronized passengers
            vehicle.dead = false
            vehicle.observed = true
            vehicle.native = connection.realIds(vehicleId, passengers.toList())
            (vehicle.native + connection.fakeIds(vehicleId)).distinct().toIntArray()
        }

        override fun setMounted(vehicleId: Int, passengers: List<Int>) = synchronized(connection) {
            if (closed || connection.disconnected) return@synchronized
            require(passengers.size <= 4096) { "Display passenger vehicle limit exceeded" }
            track(vehicleId).dead = false
            invalidated.remove(vehicleId)
            if (passengers.isEmpty()) mounted.remove(vehicleId) else mounted[vehicleId] = passengers.distinct()
            // Keep native observation while admission is deferred; retainVehicles releases it after reconciliation.
            Unit
        }

        override fun <T> duringOwnPassengerWrites(vehicleIds: Set<Int>, action: () -> T): T {
            val previous = connection.ownWrites.get()
            connection.ownWrites.set(previous.orEmpty() + vehicleIds)
            return try { action() } finally {
                if (previous == null) connection.ownWrites.remove() else connection.ownWrites.set(previous)
            }
        }

        override fun isOwnPassengerWrite(vehicleId: Int): Boolean = vehicleId in connection.ownWrites.get().orEmpty()

        override fun forgetVehicles(vehicleIds: IntArray) = synchronized(connection) {
            vehicleIds.forEach { id ->
                connection.vehicles[id]?.let { it.dead = true; it.native = emptyList(); it.observed = false }
                connection.owners.forEach { owner ->
                    if (owner.mounted.remove(id)?.isNotEmpty() == true) owner.invalidated += id
                }
            }
        }

        override fun isDisconnected(): Boolean = connection.disconnected
        override fun isDead(vehicleId: Int): Boolean = synchronized(connection) { connection.vehicles[vehicleId]?.dead == true }
        override fun invalidatedVehicles(): Set<Int> = synchronized(connection) { invalidated.toSet() }
        override fun acknowledgeInvalidations(vehicleIds: Set<Int>) = synchronized(connection) { invalidated.removeAll(vehicleIds); Unit }

        override fun retainVehicles(vehicleIds: Set<Int>) = synchronized(connection) {
            tracked.retainAll(vehicleIds + mounted.keys)
            invalidated.retainAll(tracked)
            connection.prune()
        }

        override fun closeConnection() = connection.disconnect()

        override fun close() = synchronized(connection) {
            if (closed) return@synchronized
            check(connection.disconnected || mounted.isEmpty()) { "Display passenger owner still has admitted links" }
            closed = true
            tracked.clear(); invalidated.clear(); mounted.clear()
            connection.owners.remove(this)
            connection.prune()
        }
    }
}
