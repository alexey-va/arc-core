package ru.arc.redis

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

/**
 * Typed accessors for [RedisModuleConfig.RESOURCE] — shared by Paper (ARC) and Velocity (ProxyARC).
 *
 * Bundled defaults live in arc-core-redis; override connection and [serverName] per instance.
 */
open class RedisModuleConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", true)

    open val host: String
        get() = config.string("host", DEFAULT_HOST)

    open val port: Int
        get() = config.integer("port", DEFAULT_PORT)

    open val username: String
        get() = config.string("username", DEFAULT_USERNAME)

    open val password: String
        get() = config.string("password", DEFAULT_PASSWORD)

    /** Wire identity for pub/sub (see [ServerIdentity]). */
    open val serverName: String
        get() = config.string("server-name", DEFAULT_SERVER_NAME).takeIf { it.isNotBlank() }
            ?: DEFAULT_SERVER_NAME

    /** Paper-only: main hub instance flag (announce rotation, backpacks, etc.). */
    open val mainServer: Boolean
        get() = config.bool("main-server", false)

    fun connection(): RedisConnection =
        RedisConnection(
            host = host,
            port = port,
            username = username,
            password = password,
        )

    companion object {
        const val RESOURCE = "redis.yml"
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 6379
        const val DEFAULT_USERNAME = "default"
        const val DEFAULT_PASSWORD = ""
        const val DEFAULT_SERVER_NAME = "arc"

        @JvmStatic
        fun load(dataPath: Path): RedisModuleConfig =
            RedisModuleConfig(ConfigManager.ofModule(dataPath, RESOURCE))

        @JvmStatic
        fun bundledResourcePath(): String = ConfigManager.bundledModuleResource(RESOURCE)
    }
}

/** Explicit test values — no YAML file. */
class TestRedisModuleConfig(
    override val enabled: Boolean = true,
    override val host: String = RedisModuleConfig.DEFAULT_HOST,
    override val port: Int = RedisModuleConfig.DEFAULT_PORT,
    override val username: String = RedisModuleConfig.DEFAULT_USERNAME,
    override val password: String = RedisModuleConfig.DEFAULT_PASSWORD,
    override val serverName: String = RedisModuleConfig.DEFAULT_SERVER_NAME,
    override val mainServer: Boolean = false,
) : RedisModuleConfig(EmptyConfig)
