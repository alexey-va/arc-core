package ru.arc.sql

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import java.nio.file.Path

/** Shared typed configuration for an optional MySQL runtime. */
open class SqlModuleConfig(
    private val config: Config,
    private val prefix: String = "mysql",
) {
    open val enabled: Boolean get() = config.boolean("$prefix.enabled", false)
    open val host: String get() = config.string("$prefix.host", "127.0.0.1")
    open val port: Int get() = config.integer("$prefix.port", 3306)
    open val database: String get() = config.string("$prefix.database", "minecraft")
    open val username: String get() = config.string("$prefix.username", "minecraft")
    open val password: String get() = config.string("$prefix.password", "")
    open val sslMode: SqlSslMode get() = config.enum("$prefix.ssl-mode", SqlSslMode.VERIFY_IDENTITY)
    open val minimumIdle: Int get() = config.integer("$prefix.pool.minimum-idle", 1)
    open val maximumPoolSize: Int get() = config.integer("$prefix.pool.maximum-size", 8)
    open val connectionTimeoutMs: Long get() = config.long("$prefix.pool.connection-timeout-ms", 10_000)
    open val socketTimeoutMs: Long get() = config.long("$prefix.pool.socket-timeout-ms", 30_000)
    open val validationTimeoutMs: Long get() = config.long("$prefix.pool.validation-timeout-ms", 5_000)
    open val maxLifetimeMs: Long get() = config.long("$prefix.pool.max-lifetime-ms", 1_700_000)
    open val failFast: Boolean get() = config.boolean("$prefix.fail-fast", false)

    fun connection(): SqlConnectionConfig =
        SqlConnectionConfig(
            host = host,
            port = port,
            database = database,
            username = username,
            password = password,
            sslMode = sslMode,
            minimumIdle = minimumIdle,
            maximumPoolSize = maximumPoolSize,
            connectionTimeoutMs = connectionTimeoutMs,
            socketTimeoutMs = socketTimeoutMs,
            validationTimeoutMs = validationTimeoutMs,
            maxLifetimeMs = maxLifetimeMs,
            failFast = failFast,
        )

    companion object {
        fun load(
            dataPath: Path,
            fileName: String = "config.yml",
            prefix: String = "mysql",
        ): SqlModuleConfig = SqlModuleConfig(ConfigManager.of(dataPath, fileName), prefix)
    }
}

class TestSqlModuleConfig(
    override val enabled: Boolean = false,
    override val host: String = "127.0.0.1",
    override val port: Int = 3306,
    override val database: String = "test",
    override val username: String = "test",
    override val password: String = "",
    override val sslMode: SqlSslMode = SqlSslMode.DISABLED,
    override val minimumIdle: Int = 0,
    override val maximumPoolSize: Int = 2,
    override val connectionTimeoutMs: Long = 1_000,
    override val socketTimeoutMs: Long = 2_000,
    override val validationTimeoutMs: Long = 500,
    override val maxLifetimeMs: Long = 60_000,
    override val failFast: Boolean = false,
) : SqlModuleConfig(EmptyConfig)
