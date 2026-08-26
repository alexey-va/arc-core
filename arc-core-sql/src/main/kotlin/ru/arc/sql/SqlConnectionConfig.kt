package ru.arc.sql

/** Secret-aware immutable MySQL connection and pool settings. */
data class SqlConnectionConfig(
    val host: String,
    val port: Int = 3306,
    val database: String,
    val username: String,
    val password: String,
    val sslMode: SqlSslMode = SqlSslMode.VERIFY_IDENTITY,
    val minimumIdle: Int = 1,
    val maximumPoolSize: Int = 8,
    val connectionTimeoutMs: Long = 10_000,
    val socketTimeoutMs: Long = 30_000,
    val validationTimeoutMs: Long = 5_000,
    val maxLifetimeMs: Long = 1_700_000,
    val failFast: Boolean = false,
) {
    init {
        require(host.matches(SAFE_HOST)) {
            "SQL host contains unsupported characters"
        }
        require(port in 1..65_535) { "SQL port must be between 1 and 65535" }
        require(database.matches(SAFE_DATABASE)) {
            "SQL database must contain only letters, digits, underscore or hyphen"
        }
        require(username.isNotBlank()) { "SQL username must not be blank" }
        require(minimumIdle >= 0) { "SQL minimumIdle must not be negative" }
        require(maximumPoolSize in 1..64) { "SQL maximumPoolSize must be between 1 and 64" }
        require(minimumIdle <= maximumPoolSize) { "SQL minimumIdle must not exceed maximumPoolSize" }
        require(connectionTimeoutMs >= 250) { "SQL connectionTimeoutMs must be at least 250" }
        require(socketTimeoutMs >= 1_000) { "SQL socketTimeoutMs must be at least 1000" }
        require(validationTimeoutMs in 250..connectionTimeoutMs) {
            "SQL validationTimeoutMs must be between 250 and connectionTimeoutMs"
        }
        require(maxLifetimeMs == 0L || maxLifetimeMs >= 30_000) {
            "SQL maxLifetimeMs must be zero or at least 30000"
        }
    }

    fun jdbcUrl(): String =
        buildString {
            append("jdbc:mysql://")
            append(host)
            append(':')
            append(port)
            append('/')
            append(database)
            append("?useUnicode=true&characterEncoding=utf8")
            append("&connectionTimeZone=UTC")
            append("&forceConnectionTimeZoneToSession=true")
            append("&sslMode=")
            append(sslMode.name)
            if (sslMode == SqlSslMode.DISABLED) {
                // Modern MySQL users default to caching_sha2_password. An explicitly
                // unencrypted transport needs RSA password exchange to authenticate.
                append("&allowPublicKeyRetrieval=true")
            }
            append("&connectTimeout=")
            append(connectionTimeoutMs)
            append("&socketTimeout=")
            append(socketTimeoutMs)
        }

    /** Never include the password in diagnostics or generated URLs. */
    override fun toString(): String =
        "SqlConnectionConfig(host=$host, port=$port, database=$database, username=$username, " +
        "password=<redacted>, sslMode=$sslMode, " +
            "minimumIdle=$minimumIdle, maximumPoolSize=$maximumPoolSize, " +
            "connectionTimeoutMs=$connectionTimeoutMs, socketTimeoutMs=$socketTimeoutMs, " +
            "validationTimeoutMs=$validationTimeoutMs, " +
            "maxLifetimeMs=$maxLifetimeMs, failFast=$failFast)"

    private companion object {
        val SAFE_HOST = Regex("[A-Za-z0-9._:\\[\\]-]{1,253}")
        val SAFE_DATABASE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

enum class SqlSslMode {
    DISABLED,
    REQUIRED,
    VERIFY_CA,
    VERIFY_IDENTITY,
}
