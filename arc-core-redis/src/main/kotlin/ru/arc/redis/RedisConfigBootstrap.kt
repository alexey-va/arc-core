package ru.arc.redis

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ensures [RedisModuleConfig.RESOURCE] exists on disk (bundled default + optional legacy migration).
 */
object RedisConfigBootstrap {
    /**
     * @param legacyReader Return snapshot when migrating from plugin-specific YAML; null = bundled defaults only.
     */
    @JvmStatic
    fun ensure(
        dataRoot: Path,
        legacyReader: (() -> LegacyRedisSnapshot?)? = null,
    ) {
        val relative = ConfigManager.moduleYamlRelative(dataRoot, RedisModuleConfig.RESOURCE)
        val path = dataRoot.resolve(relative)
        if (Files.exists(path)) return

        Config.copyDefaultConfig(relative, dataRoot, replace = false)

        val legacy = legacyReader?.invoke() ?: readLegacyPaper(dataRoot) ?: readLegacyProxy(dataRoot)
        if (legacy == null) return

        val cfg = ConfigManager.ofModule(dataRoot, RedisModuleConfig.RESOURCE)
        legacy.applyTo(cfg)
        cfg.save()
    }

    private fun readLegacyPaper(dataRoot: Path): LegacyRedisSnapshot? {
        val misc = dataRoot.resolve("misc.yml")
        if (!Files.exists(misc)) return null
        val cfg = Config(dataRoot, "misc.yml")
        val host = cfg.stringOrNull("redis.ip") ?: cfg.stringOrNull("redis.host") ?: return null
        return LegacyRedisSnapshot(
            enabled = if (cfg.stringOrNull("redis.enabled") != null) cfg.bool("redis.enabled", true) else null,
            host = host,
            port = cfg.integer("redis.port", RedisModuleConfig.DEFAULT_PORT),
            username = cfg.string("redis.username", RedisModuleConfig.DEFAULT_USERNAME),
            password = cfg.string("redis.password", RedisModuleConfig.DEFAULT_PASSWORD),
            serverName = cfg.stringOrNull("redis.server-name"),
            mainServer = if (cfg.stringOrNull("redis.main-server") != null) cfg.bool("redis.main-server", false) else null,
        )
    }

    private fun readLegacyProxy(dataRoot: Path): LegacyRedisSnapshot? {
        val main = dataRoot.resolve("config.yml")
        if (!Files.exists(main)) return null
        val cfg = Config(dataRoot, "config.yml")
        val host = cfg.stringOrNull("redis.host") ?: return null
        return LegacyRedisSnapshot(
            host = host,
            port = cfg.integer("redis.port", RedisModuleConfig.DEFAULT_PORT),
            username = cfg.string("redis.username", RedisModuleConfig.DEFAULT_USERNAME),
            password = cfg.string("redis.password", RedisModuleConfig.DEFAULT_PASSWORD),
            serverName = cfg.stringOrNull("server-name"),
        )
    }
}

/** Values copied from legacy plugin YAML into [RedisModuleConfig.RESOURCE]. */
data class LegacyRedisSnapshot(
    val enabled: Boolean? = null,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val password: String? = null,
    val serverName: String? = null,
    val mainServer: Boolean? = null,
) {
    fun applyTo(config: Config) {
        enabled?.let { config.setBoolean("enabled", it) }
        host?.let { config.setString("host", it) }
        port?.let { config.setInt("port", it) }
        username?.let { config.setString("username", it) }
        password?.let { config.setString("password", it) }
        serverName?.let { config.setString("server-name", it) }
        mainServer?.let { config.setBoolean("main-server", it) }
    }
}
