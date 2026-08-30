package ru.arc.paper.nameplate

import org.bukkit.GameMode
import org.bukkit.entity.Player

/** Decides whether one viewer may receive a target player's nameplate entity. */
fun interface PaperNameplateVisibilityPolicy {
    fun canView(
        viewer: Player,
        target: Player,
    ): Boolean
}

/**
 * Privacy-first production policy.
 *
 * A plate is hidden from its owner, across worlds, beyond the configured
 * distance, for vanished/invisible/spectator targets, and whenever Paper's
 * block line-of-sight check fails. Callers may inject a stricter policy, but a
 * permissive policy must be an explicit product decision.
 */
class NativePaperNameplateVisibilityPolicy(
    private val options: PaperNameplateOptions = PaperNameplateOptions(),
) : PaperNameplateVisibilityPolicy {
    private val maxDistanceSquared = options.maxDistance * options.maxDistance

    override fun canView(
        viewer: Player,
        target: Player,
    ): Boolean {
        if (viewer.uniqueId == target.uniqueId) return false
        if (!viewer.isOnline || !target.isOnline || target.isDead) return false
        if (viewer.world.uid != target.world.uid) return false
        if (!viewer.canSee(target)) return false
        if (options.hideInvisibleTargets && target.isInvisible) return false
        if (options.hideSpectatorTargets && target.gameMode == GameMode.SPECTATOR) return false
        if (viewer.location.distanceSquared(target.location) > maxDistanceSquared) return false
        return !options.requireLineOfSight || viewer.hasLineOfSight(target)
    }
}
