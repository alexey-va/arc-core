package ru.arc.ops.core

import ru.arc.config.Config
import ru.arc.config.EmptyConfig

/**
 * Ops HTTP settings loaded from `modules/ops-http.yml` on each server.
 */
open class OpsHttpConfig(private val config: Config) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val token: String
        get() = config.string("token", "")

    open val bindHost: String
        get() = config.string("bind-host", "127.0.0.1")

    open val bindPort: Int
        get() = config.integer("bind-port", 25823)

    open val consoleEnabled: Boolean
        get() = config.bool("console-enabled", false)

    open val reloadPathsEnabled: Boolean
        get() = config.bool("reload-paths-enabled", true)

    open val errorBufferSize: Int
        get() = config.integer("error-buffer-size", 200).coerceIn(50, 2000)

    open val threadPoolSize: Int
        get() = config.integer("thread-pool-size", 8).coerceIn(2, 32)
}

/** Explicit test overrides without YAML. */
class TestOpsHttpConfig(
    override val enabled: Boolean = true,
    override val token: String = "test-token",
    override val bindHost: String = "127.0.0.1",
    override val bindPort: Int = 0,
    override val consoleEnabled: Boolean = true,
    override val reloadPathsEnabled: Boolean = true,
    override val errorBufferSize: Int = 100,
    override val threadPoolSize: Int = 4,
) : OpsHttpConfig(EmptyConfig)
