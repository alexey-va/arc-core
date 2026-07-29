package ru.arc.core

/**
 * Bounded, immutable lifecycle state for one registered plugin module.
 *
 * Module names come from the finite ModuleRegistry set, so they are safe to
 * expose as metric labels.
 */
data class ModuleRuntimeStatus(
    val name: String,
    val ready: Boolean = false,
    val initDurationMs: Long? = null,
    val reloadDurationMs: Long? = null,
    val shutdownDurationMs: Long? = null,
    val failures: Long = 0,
)
