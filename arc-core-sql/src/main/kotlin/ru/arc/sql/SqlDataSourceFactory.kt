package ru.arc.sql

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object SqlDataSourceFactory {
    fun create(
        config: SqlConnectionConfig,
        poolName: String,
    ): HikariDataSource {
        require(poolName.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "Unsafe SQL pool name" }
        val hikari = HikariConfig()
        hikari.poolName = poolName
        hikari.jdbcUrl = config.jdbcUrl()
        hikari.driverClassName = "com.mysql.cj.jdbc.Driver"
        hikari.username = config.username
        hikari.password = config.password
        hikari.minimumIdle = config.minimumIdle
        hikari.maximumPoolSize = config.maximumPoolSize
        hikari.connectionTimeout = config.connectionTimeoutMs
        hikari.validationTimeout = config.validationTimeoutMs
        hikari.maxLifetime = config.maxLifetimeMs
        hikari.initializationFailTimeout = if (config.failFast) config.connectionTimeoutMs else -1
        hikari.isAutoCommit = true
        hikari.addDataSourceProperty("cachePrepStmts", "true")
        hikari.addDataSourceProperty("prepStmtCacheSize", "250")
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        hikari.addDataSourceProperty("useServerPrepStmts", "true")
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true")
        return HikariDataSource(hikari)
    }
}
