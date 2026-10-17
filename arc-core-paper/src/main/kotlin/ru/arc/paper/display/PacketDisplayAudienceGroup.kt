package ru.arc.paper.display

import org.bukkit.entity.Player

/**
 * An additional visibility gate shared by a scene's handles, owned by one [PaperPacketDisplays].
 * The predicate runs on the server thread, at most once per eligible viewer per owner refresh.
 * Explicit privacy, world, range and received-chunk checks still apply before this gate.
 * Closing releases the predicate and permanently hides attached handles until they are removed
 * or assigned a different group. It never changes their explicit show/hide audience.
 */
class PacketDisplayAudienceGroup internal constructor(
    internal val owner: PaperPacketDisplays,
    private var predicate: ((Player) -> Boolean)?,
) : AutoCloseable {
    val isClosed: Boolean get() = predicate == null || owner.closed

    internal fun allows(player: Player): Boolean = if (owner.closed) false else predicate?.invoke(player) ?: false

    override fun close() {
        owner.checkThread()
        if (predicate != null) owner.visibilityChanged()
        predicate = null
        owner.remove(this)
    }
}
