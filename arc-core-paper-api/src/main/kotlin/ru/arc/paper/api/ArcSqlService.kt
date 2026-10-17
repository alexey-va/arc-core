package ru.arc.paper.api

import java.util.concurrent.Executor
import java.sql.Connection
import javax.sql.DataSource

/** Immutable MySQL settings used to identify an exactly compatible shared pool. */
data class ArcSqlSettings(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val sslMode: ArcSqlSslMode,
    val minimumIdle: Int,
    val maximumPoolSize: Int,
    val connectionTimeoutMs: Long,
    val socketTimeoutMs: Long,
    val validationTimeoutMs: Long,
    val maxLifetimeMs: Long,
    val failFast: Boolean,
) {
    init {
        require(host.matches(SAFE_HOST)) { "SQL host contains unsupported characters" }
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

    override fun toString(): String =
        "ArcSqlSettings(host=$host, port=$port, database=$database, username=$username, " +
            "password=<redacted>, sslMode=$sslMode, minimumIdle=$minimumIdle, " +
            "maximumPoolSize=$maximumPoolSize, connectionTimeoutMs=$connectionTimeoutMs, " +
            "socketTimeoutMs=$socketTimeoutMs, validationTimeoutMs=$validationTimeoutMs, " +
            "maxLifetimeMs=$maxLifetimeMs, failFast=$failFast)"

    private companion object {
        val SAFE_HOST = Regex("[A-Za-z0-9._:\\[\\]-]{1,253}")
        val SAFE_DATABASE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

enum class ArcSqlSslMode {
    DISABLED,
    REQUIRED,
    VERIFY_CA,
    VERIFY_IDENTITY,
}

/** A consumer-scoped view; closing it releases only this consumer's lease. */
interface ArcSqlLease : AutoCloseable {
    val dataSource: DataSource
    val executor: Executor
    /** Reserved lane for durable completion work that must progress under pool saturation. */
    val completionExecutor: Executor

    /**
     * Before provider shutdown, retain this existing lease for an asynchronous
     * terminal store drain. No new pool is acquired. The consumer MUST close
     * the lease after its drain completes; native gameplay admission is already
     * stopped by the consumer. Legacy providers retain their old close behavior.
     */
    fun retainDuringShutdown() = Unit

    /** Aborts a connection issued by this lease so the provider pool cannot reuse an uncertain session. */
    fun evictConnection(connection: Connection)

    override fun close()
}

/** Host-owned SQL service registered through the platform service manager. */
interface ArcSqlService {
    /** Cold acquisition may connect/migrate/create workers; invoke on an I/O worker, never a tick thread. */
    fun acquire(owner: String, settings: ArcSqlSettings): ArcSqlLease

    /** Secret-free snapshot of currently borrowed pool groups. */
    fun snapshot(): List<ArcSqlGroupSnapshot>
}

data class ArcSqlGroupSnapshot(
    val groupId: Long,
    val host: String,
    val port: Int,
    val database: String,
    val minimumIdle: Int,
    val maximumPoolSize: Int,
    val borrowers: Int,
    val inFlightTasks: Int,
    val inFlightCompletionTasks: Int,
    val openConnections: Int,
    val owners: List<String>,
    val unattributedBorrowers: Int,
)
