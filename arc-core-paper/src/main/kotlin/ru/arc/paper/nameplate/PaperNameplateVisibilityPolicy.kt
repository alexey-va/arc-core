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

/**
 * Adds an approximate camera-cone check to an existing visibility policy.
 *
 * [minimumAlignment] is the normalized direction dot product. `-1.0` disables
 * this additional check, `0.0` keeps the forward hemisphere and `0.5` keeps
 * targets within roughly 60 degrees of the camera center. Paper cannot know a
 * client's exact FOV or aspect ratio, so consumers should expose this value as
 * live configuration rather than treating it as exact screen-edge geometry.
 */
class ViewAlignedPaperNameplateVisibilityPolicy(
    private val delegate: PaperNameplateVisibilityPolicy,
    val minimumAlignment: Double,
) : PaperNameplateVisibilityPolicy {
    init {
        require(minimumAlignment in -1.0..1.0 && minimumAlignment.isFinite()) {
            "Nameplate minimum view alignment must be finite and between -1.0 and 1.0"
        }
    }

    override fun canView(
        viewer: Player,
        target: Player,
    ): Boolean {
        if (!delegate.canView(viewer, target)) return false
        if (minimumAlignment <= -1.0) return true
        val viewerEye = viewer.eyeLocation
        val directionToTarget = target.eyeLocation.toVector().subtract(viewerEye.toVector())
        if (directionToTarget.lengthSquared() < MINIMUM_DIRECTION_LENGTH_SQUARED) return true
        val alignment = viewerEye.direction.dot(directionToTarget.normalize())
        return alignment >= minimumAlignment
    }

    private companion object {
        const val MINIMUM_DIRECTION_LENGTH_SQUARED = 1.0e-8
    }
}
