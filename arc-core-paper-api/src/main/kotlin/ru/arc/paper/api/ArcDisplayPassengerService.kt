package ru.arc.paper.api

/**
 * Host-owned passenger accounting shared across shaded display consumers. The connection is a
 * weakly held channel identity, never a Player. Each display queue opens one owner and closes it
 * only after its admitted links have been removed, or after that connection has been lost.
 * The service stores no packets, callbacks, Bukkit entities or pending animation history.
 */
interface ArcDisplayPassengerService {
    fun open(connection: Any): ArcDisplayPassengerOwner
}

/**
 * Thread-safe detached state; writes and their acknowledgement run on the connection event loop.
 * Before writing, publish the union of previous and proposed IDs with [setMounted]. On rejection
 * restore the previous IDs; on success replace them with the proposed IDs. A partial failure keeps
 * the union until cleanup. [duringOwnPassengerWrites] fences every owner's native packet observer.
 * [nativePassengers] returns real passengers plus OTHER owners' admitted IDs, excluding this owner.
 * Ordinary owner retirement must call [close], never [closeConnection]. No method sends packets.
 */
interface ArcDisplayPassengerOwner : AutoCloseable {
    fun seedNative(vehicleId: Int, passengers: List<Int>)
    fun nativePassengers(vehicleId: Int, fallback: List<Int>): List<Int>
    fun observeNative(vehicleId: Int, passengers: IntArray): IntArray
    fun setMounted(vehicleId: Int, passengers: List<Int>)
    fun <T> duringOwnPassengerWrites(vehicleIds: Set<Int>, action: () -> T): T
    fun isOwnPassengerWrite(vehicleId: Int): Boolean
    fun forgetVehicles(vehicleIds: IntArray)
    fun isDisconnected(): Boolean
    fun isDead(vehicleId: Int): Boolean
    fun invalidatedVehicles(): Set<Int>
    fun acknowledgeInvalidations(vehicleIds: Set<Int>)
    fun retainVehicles(vehicleIds: Set<Int>)
    /** Actual channel loss invalidates every owner on that connection, not just this queue. */
    fun closeConnection()
}
