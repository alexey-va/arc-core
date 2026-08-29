package ru.arc.paper.chunk

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class PaperChunkKey(
    val worldId: UUID,
    val x: Int,
    val z: Int,
)

enum class PaperChunkTicketAddResult {
    ADDED,
    ALREADY_PRESENT,
    WORLD_UNAVAILABLE,
}

/** Exact Paper calls used by [PaperChunkTicketRegistry]. */
interface PaperChunkTicketBackend {
    fun add(key: PaperChunkKey): PaperChunkTicketAddResult

    fun remove(key: PaperChunkKey): Boolean
}

sealed interface PaperChunkTicketAcquireResult {
    data class Acquired(val lease: PaperChunkTicketLease) : PaperChunkTicketAcquireResult

    data object WorldUnavailable : PaperChunkTicketAcquireResult

    data object RegistryClosed : PaperChunkTicketAcquireResult

    data class Failed(val failure: Throwable) : PaperChunkTicketAcquireResult
}

/**
 * Reference-counts one Paper plugin ticket per chunk and returns idempotent leases.
 *
 * Paper permits only one ticket for each plugin/chunk pair. A ticket that
 * existed before this registry is borrowed and never removed by it. All
 * registry operations belong on the owning Paper thread; [PaperChunkTicketLease.close]
 * may be repeated but must be marshalled back to that thread by the caller.
 * Create one registry per plugin lifecycle so another owner cannot remove a
 * borrowed ticket while a lease is still active.
 */
class PaperChunkTicketRegistry(
    private val backend: PaperChunkTicketBackend,
) : AutoCloseable {
    private data class TicketState(
        val owned: Boolean,
        var leaseCount: Int,
        var releaseUncertain: Boolean = false,
    )

    private val tickets = linkedMapOf<PaperChunkKey, TicketState>()
    private var closed = false

    constructor(plugin: Plugin) : this(NativePaperChunkTicketBackend(plugin))

    val activeChunkCount: Int
        get() = tickets.size

    val activeLeaseCount: Int
        get() = tickets.values.sumOf(TicketState::leaseCount)

    fun acquire(chunk: Chunk): PaperChunkTicketAcquireResult =
        acquire(PaperChunkKey(chunk.world.uid, chunk.x, chunk.z))

    fun acquire(key: PaperChunkKey): PaperChunkTicketAcquireResult {
        if (closed) return PaperChunkTicketAcquireResult.RegistryClosed
        tickets[key]?.let { current ->
            if (current.releaseUncertain) {
                return PaperChunkTicketAcquireResult.Failed(
                    IllegalStateException("Chunk ticket removal outcome is unknown for $key"),
                )
            }
            current.leaseCount++
            return PaperChunkTicketAcquireResult.Acquired(lease(key))
        }

        val added = runCatching { backend.add(key) }
            .getOrElse { return PaperChunkTicketAcquireResult.Failed(it) }
        if (added == PaperChunkTicketAddResult.WORLD_UNAVAILABLE) {
            return PaperChunkTicketAcquireResult.WorldUnavailable
        }
        tickets[key] = TicketState(
            owned = added == PaperChunkTicketAddResult.ADDED,
            leaseCount = 1,
        )
        return PaperChunkTicketAcquireResult.Acquired(lease(key))
    }

    override fun close() {
        if (closed && tickets.isEmpty()) return
        closed = true
        val failures = mutableListOf<Throwable>()
        val iterator = tickets.iterator()
        while (iterator.hasNext()) {
            val (key, state) = iterator.next()
            if (!state.owned) {
                iterator.remove()
                continue
            }
            runCatching {
                check(backend.remove(key)) { "Paper chunk ticket removal was not confirmed for $key" }
            }.onSuccess { iterator.remove() }
                .onFailure { failure ->
                    state.releaseUncertain = true
                    failures += failure
                }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "Could not release ${failures.size} owned Paper chunk ticket(s)",
                failures.first(),
            ).also { combined ->
                failures.drop(1).forEach(combined::addSuppressed)
            }
        }
    }

    private fun lease(key: PaperChunkKey): PaperChunkTicketLease =
        PaperChunkTicketLease(key) { release(key) }

    private fun release(key: PaperChunkKey) {
        val current = tickets[key] ?: return
        if (current.leaseCount <= 0) return
        current.leaseCount--
        if (current.leaseCount > 0) return
        if (!current.owned) {
            tickets.remove(key)
            return
        }
        try {
            check(backend.remove(key)) { "Paper chunk ticket removal was not confirmed for $key" }
            tickets.remove(key)
        } catch (failure: Throwable) {
            current.releaseUncertain = true
            throw failure
        }
    }
}

class PaperChunkTicketLease internal constructor(
    val key: PaperChunkKey,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Native backend bound to one plugin's Paper chunk tickets. */
class NativePaperChunkTicketBackend(
    private val plugin: Plugin,
) : PaperChunkTicketBackend {
    override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
        val world = Bukkit.getWorld(key.worldId) ?: return PaperChunkTicketAddResult.WORLD_UNAVAILABLE
        return if (world.addPluginChunkTicket(key.x, key.z, plugin)) {
            PaperChunkTicketAddResult.ADDED
        } else {
            PaperChunkTicketAddResult.ALREADY_PRESENT
        }
    }

    override fun remove(key: PaperChunkKey): Boolean {
        val world = Bukkit.getWorld(key.worldId) ?: return false
        return world.removePluginChunkTicket(key.x, key.z, plugin)
    }
}
