package ru.arc.redis

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ensures [RedisModuleConfig.RESOURCE] exists on disk from the bundled default
 * plus an optional explicitly supplied settings snapshot.
 *
 * This owner deliberately has no implicit readers for retired plugin layouts.
 * A consumer that still owns another current configuration source must name
 * and supply that source at its composition boundary.
 */
object RedisConfigBootstrap {
    /**
     * @param settingsReader Return current settings to seed the new module file;
     * null means bundled defaults only.
     */
    @JvmStatic
    fun ensure(
        dataRoot: Path,
        settingsReader: (() -> RedisConnectionSettingsSnapshot?)? = null,
    ) {
        val relative = ConfigManager.moduleYamlRelative(dataRoot, RedisModuleConfig.RESOURCE)
        val path = dataRoot.resolve(relative)
        if (Files.exists(path)) return

        Config.copyDefaultConfig(relative, dataRoot, replace = false)

        val settings = settingsReader?.invoke() ?: return

        val cfg = ConfigManager.ofModule(dataRoot, RedisModuleConfig.RESOURCE)
        settings.applyTo(cfg)
        cfg.save()
    }
}

/** Explicit current values used to seed [RedisModuleConfig.RESOURCE]. */
data class RedisConnectionSettingsSnapshot(
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
