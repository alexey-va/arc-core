package ru.arc.metrics.core

import ru.arc.config.Config

/**
 * Shared Prometheus endpoint and sampling policy.
 *
 * Platform plugins own loading/reloading the YAML so this module stays free of
 * ARC and ProxyARC singleton state.
 */
open class MetricsConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val bindHost: String
        get() = config.string("bind-host", "127.0.0.1")

    open val bindPort: Int
        get() = config.integer("bind-port", 9950).coerceIn(0, 65_535)

    /** JVM/OS and cheap platform snapshot cadence. */
    open val sampleIntervalSeconds: Int
        get() = config.integer("sample-interval-seconds", 5).coerceIn(2, 60)

    /** Disk and world-level snapshot cadence. */
    open val heavySampleIntervalSeconds: Int
        get() = config.integer("heavy-sample-interval-seconds", 60).coerceIn(15, 600)

    open val includeJvm: Boolean
        get() = config.bool("include-jvm", true)

    open val includeSystem: Boolean
        get() = config.bool("include-system", true)

    open val includeDisk: Boolean
        get() = config.bool("include-disk", true)

    open val includePlatformHeavy: Boolean
        get() = config.bool("include-platform-heavy", true)
}

data class MetricsIdentity(
    val application: String,
    val platform: String,
    val serverName: String,
    val version: String,
)
