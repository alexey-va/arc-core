package ru.arc.sql.onetime

import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlExecutor
import ru.arc.sql.SqlMigration
import ru.arc.sql.SqlMigrationCompatibility
import ru.arc.sql.SqlRuntime
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** One purpose partition inside the shared one-time-use table. */
data class MySqlOneTimeUsePartition(
    val purpose: String,
    val tableName: String = DEFAULT_TABLE,
) {
    init {
        require(purpose.matches(SAFE_PURPOSE)) { "One-time-use purpose must be 1..64 safe lowercase characters" }
        require(tableName.matches(SQL_IDENTIFIER)) { "One-time-use table name must be a safe lowercase SQL identifier" }
    }

    internal val quotedTable: String get() = "`$tableName`"

    companion object {
        const val DEFAULT_TABLE = "arc_one_time_uses"
        private val SAFE_PURPOSE = Regex("[a-z0-9][a-z0-9_.:-]{0,63}")
        // Leaves room for the generated `_claim_uq` / `_status_chk` suffixes
        // under MySQL's 64-character identifier limit.
        private val SQL_IDENTIFIER = Regex("[a-z][a-z0-9_]{0,47}")
    }
}

/**
 * Blocking JDBC transitions for one [MySqlOneTimeUsePartition].
 *
 * This is the canonical SQL owner used both by [MySqlOneTimeUseLedger] and by
 * domain repositories that must update their own row in the same transaction.
 * The caller owns the connection, transaction, executor and any external live
 * lock. Every method uses parameterized values and exact identity comparison.
 */
class MySqlOneTimeUseStore(
    private val partition: MySqlOneTimeUsePartition,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun claim(connection: Connection, request: OneTimeUseClaimRequest): OneTimeUseClaimResult {
        val inserted = try {
            connection.prepareStatement(
                """
                INSERT INTO ${partition.quotedTable}
                    (`purpose`, `use_id`, `fingerprint`, `claimant_id`, `claim_id`, `claim_scope`, `status`, `claimed_at`)
                VALUES (?, ?, ?, ?, ?, ?, 'CLAIMED', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, partition.purpose)
                statement.setBytes(2, request.identity.useId.bytes())
                statement.setBytes(3, request.identity.fingerprint.bytes())
                statement.setBytes(4, request.claimantId.bytes())
                statement.setBytes(5, request.claimId.bytes())
                statement.setString(6, request.scope?.value)
                statement.setTimestamp(7, Timestamp.from(clock.instant()))
                statement.executeUpdate()
            }
            true
        } catch (failure: SQLException) {
            // Connector/J may report an idempotent ON DUPLICATE KEY no-op as one
            // affected row, which is indistinguishable from a fresh insert. A
            // plain insert gives us an exact creation signal; MySQL keeps the
            // transaction usable after duplicate-key error 1062 so the locked
            // row can be classified below.
            if (failure.errorCode != MYSQL_DUPLICATE_KEY || failure.sqlState != SQL_STATE_INTEGRITY_CONSTRAINT) {
                throw failure
            }
            false
        }
        val row = load(connection, request.identity.useId, forUpdate = true)
            ?: return OneTimeUseClaimResult.IdentityConflict
        if (row.fingerprint != request.identity.fingerprint) return OneTimeUseClaimResult.IdentityConflict
        if (row.status == STATUS_COMMITTED) return OneTimeUseClaimResult.AlreadyConsumed
        check(row.status == STATUS_CLAIMED) { "Unknown one-time-use status" }
        if (!row.matches(request)) return OneTimeUseClaimResult.Busy
        return OneTimeUseClaimResult.Acquired(
            OneTimeUseClaim.acquired(request, newlyCreated = inserted),
        )
    }

    fun commit(connection: Connection, claim: OneTimeUseClaim): OneTimeUseCommitResult {
        val updated = connection.prepareStatement(
            """
            UPDATE ${partition.quotedTable}
            SET `status` = 'COMMITTED', `committed_at` = ?
            WHERE `purpose` = ? AND `use_id` = ? AND `fingerprint` = ? AND `claimant_id` = ?
              AND `claim_id` = ? AND `claim_scope` <=> ? AND `status` = 'CLAIMED'
            """.trimIndent(),
        ).use { statement ->
            statement.setTimestamp(1, Timestamp.from(clock.instant()))
            bindClaim(statement, claim, 2)
            statement.executeUpdate()
        }
        if (updated == 1) return OneTimeUseCommitResult.COMMITTED
        val row = load(connection, claim.identity.useId, forUpdate = true)
        return if (row != null && row.matches(claim) && row.status == STATUS_COMMITTED) {
            OneTimeUseCommitResult.ALREADY_COMMITTED
        } else {
            OneTimeUseCommitResult.REJECTED
        }
    }

    fun release(connection: Connection, claim: OneTimeUseClaim): OneTimeUseReleaseResult {
        val deleted = connection.prepareStatement(
            """
            DELETE FROM ${partition.quotedTable}
            WHERE `purpose` = ? AND `use_id` = ? AND `fingerprint` = ? AND `claimant_id` = ?
              AND `claim_id` = ? AND `claim_scope` <=> ? AND `status` = 'CLAIMED'
            """.trimIndent(),
        ).use { statement ->
            bindClaim(statement, claim)
            statement.executeUpdate()
        }
        if (deleted == 1) return OneTimeUseReleaseResult.RELEASED
        return if (load(connection, claim.identity.useId, forUpdate = true) == null) {
            OneTimeUseReleaseResult.ALREADY_RELEASED
        } else {
            OneTimeUseReleaseResult.REJECTED
        }
    }

    fun abandon(connection: Connection, claim: OneTimeUseClaim): OneTimeUseAbandonResult {
        val row = load(connection, claim.identity.useId, forUpdate = true)
        return when {
            row == null || !row.matches(claim) -> OneTimeUseAbandonResult.REJECTED
            row.status == STATUS_COMMITTED -> OneTimeUseAbandonResult.ALREADY_COMMITTED
            row.status == STATUS_CLAIMED -> OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY
            else -> error("Unknown one-time-use status")
        }
    }

    private data class StoredRow(
        val fingerprint: OneTimeUseFingerprint,
        val claimantId: UUID,
        val claimId: UUID,
        val scope: OneTimeUseScope?,
        val status: String,
    ) {
        fun asRequest(useId: UUID): OneTimeUseClaimRequest =
            OneTimeUseClaimRequest(OneTimeUseIdentity(useId, fingerprint), claimId, claimantId, scope)

        fun matches(request: OneTimeUseClaimRequest): Boolean = asRequest(request.identity.useId) == request
        fun matches(claim: OneTimeUseClaim): Boolean = matches(claim.asRequest())
    }

    private fun load(connection: Connection, useId: UUID, forUpdate: Boolean): StoredRow? =
        connection.prepareStatement(
            """
            SELECT `fingerprint`, `claimant_id`, `claim_id`, `claim_scope`, `status`
            FROM ${partition.quotedTable}
            WHERE `purpose` = ? AND `use_id` = ?${if (forUpdate) " FOR UPDATE" else ""}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, partition.purpose)
            statement.setBytes(2, useId.bytes())
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else StoredRow(
                    fingerprint = OneTimeUseFingerprint.fromBytes(rows.getBytes("fingerprint")),
                    claimantId = rows.getBytes("claimant_id").uuid(),
                    claimId = rows.getBytes("claim_id").uuid(),
                    scope = rows.getString("claim_scope")?.let(OneTimeUseScope::parse),
                    status = rows.getString("status"),
                )
            }
        }

    private fun bindClaim(statement: PreparedStatement, claim: OneTimeUseClaim, start: Int = 1) {
        statement.setString(start, partition.purpose)
        statement.setBytes(start + 1, claim.identity.useId.bytes())
        statement.setBytes(start + 2, claim.identity.fingerprint.bytes())
        statement.setBytes(start + 3, claim.claimantId.bytes())
        statement.setBytes(start + 4, claim.claimId.bytes())
        statement.setString(start + 5, claim.scope?.value)
    }

    private companion object {
        const val MYSQL_DUPLICATE_KEY = 1062
        const val SQL_STATE_INTEGRITY_CONSTRAINT = "23000"
        const val STATUS_CLAIMED = "CLAIMED"
        const val STATUS_COMMITTED = "COMMITTED"
    }
}

/**
 * Standard asynchronous ledger with a connection-scoped MySQL advisory lock.
 *
 * A successful claim retains one JDBC session until commit, release or abandon,
 * covering the caller's external value mutation. Completion has a separate
 * executor so claims occupying every pool connection cannot starve finalization.
 * Every returned future completes off platform threads; exceptional completion
 * means the storage outcome is unknown.
 */
class MySqlOneTimeUseLedger private constructor(
    private val runtime: SqlRuntime,
    private val completionExecutor: SqlExecutor,
    private val partition: MySqlOneTimeUsePartition,
    private val store: MySqlOneTimeUseStore,
    private val ownsRuntime: Boolean,
) : OneTimeUseLedger {
    private data class ClaimHandle(
        val claim: OneTimeUseClaim,
        val lockName: String,
        val connection: Connection,
    )

    private val claimHandles = ConcurrentHashMap<UUID, ClaimHandle>()

    override val activeClaims: Int get() = claimHandles.size

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> =
        runtime.executor.submit {
            val connection = runtime.dataSource.connection
            val lockName = lockName(request.identity.useId)
            var retained = false
            try {
                if (!acquireNamedLock(connection, lockName)) return@submit OneTimeUseClaimResult.Busy
                val result = transaction(connection) { store.claim(connection, request) }
                if (result is OneTimeUseClaimResult.Acquired) {
                    val handle = ClaimHandle(result.claim, lockName, connection)
                    check(claimHandles.putIfAbsent(result.claim.claimId, handle) == null) {
                        "One-time-use claim already has a local lock handle"
                    }
                    retained = true
                }
                result
            } finally {
                if (!retained) releaseNamedLock(connection, lockName)
            }
        }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> =
        completionExecutor.submit {
            withClaimHandle(claim, OneTimeUseCommitResult.REJECTED) { connection ->
                transaction(connection) { store.commit(connection, claim) }
            }
        }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> =
        completionExecutor.submit {
            withClaimHandle(claim, OneTimeUseReleaseResult.REJECTED) { connection ->
                transaction(connection) { store.release(connection, claim) }
            }
        }

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
        completionExecutor.submit {
            withClaimHandle(claim, OneTimeUseAbandonResult.REJECTED) { connection ->
                transaction(connection) { store.abandon(connection, claim) }
            }
        }

    override fun close() {
        completionExecutor.close()
        claimHandles.entries.toList().forEach { (claimId, handle) ->
            if (claimHandles.remove(claimId, handle)) releaseNamedLock(handle.connection, handle.lockName)
        }
        if (ownsRuntime) runtime.close()
    }

    private fun <T> withClaimHandle(claim: OneTimeUseClaim, rejected: T, block: (Connection) -> T): T {
        val handle = claimHandles[claim.claimId]?.takeIf { it.claim.sameIdentity(claim) } ?: return rejected
        check(claimHandles.remove(claim.claimId, handle)) { "One-time-use claim handle changed unexpectedly" }
        return try {
            block(handle.connection)
        } finally {
            releaseNamedLock(handle.connection, handle.lockName)
        }
    }

    private fun OneTimeUseClaim.sameIdentity(other: OneTimeUseClaim): Boolean =
        identity == other.identity && claimId == other.claimId && claimantId == other.claimantId && scope == other.scope

    private fun <T> transaction(connection: Connection, block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        var primaryFailure: Throwable? = null
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            primaryFailure = failure
            runCatching { connection.rollback() }.onFailure(failure::addSuppressed)
            throw failure
        } finally {
            runCatching { connection.autoCommit = previousAutoCommit }.onFailure { restoreFailure ->
                primaryFailure?.addSuppressed(restoreFailure) ?: throw restoreFailure
            }
        }
    }

    private fun acquireNamedLock(connection: Connection, lockName: String): Boolean =
        connection.prepareStatement("SELECT GET_LOCK(?, 0)").use { statement ->
            statement.setString(1, lockName)
            statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
        }

    private fun releaseNamedLock(connection: Connection, lockName: String) {
        val released = runCatching {
            connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
                statement.setString(1, lockName)
                statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
            }
        }.getOrDefault(false)
        if (released) runCatching { connection.close() }
        else runCatching { runtime.dataSource.evictConnection(connection) }
    }

    private fun lockName(useId: UUID): String {
        val bytes = "${partition.purpose}:$useId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "arc:otu:${digest.take(28).joinToString("") { "%02x".format(it.toInt() and 0xff) }}"
    }

    companion object {
        fun createTableSql(tableName: String = MySqlOneTimeUsePartition.DEFAULT_TABLE): String {
            val partition = MySqlOneTimeUsePartition("schema", tableName)
            return """
                CREATE TABLE IF NOT EXISTS ${partition.quotedTable} (
                    `purpose` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `use_id` BINARY(16) NOT NULL,
                    `fingerprint` BINARY(32) NOT NULL,
                    `claimant_id` BINARY(16) NOT NULL,
                    `claim_id` BINARY(16) NOT NULL,
                    `claim_scope` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    `claimed_at` TIMESTAMP(3) NOT NULL,
                    `committed_at` TIMESTAMP(3) NULL,
                    PRIMARY KEY (`purpose`, `use_id`),
                    UNIQUE KEY `${tableName}_claim_uq` (`purpose`, `claim_id`),
                    CONSTRAINT `${tableName}_status_chk` CHECK (`status` IN ('CLAIMED', 'COMMITTED'))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """.trimIndent()
        }

        fun createTableMigration(
            version: Int,
            tableName: String = MySqlOneTimeUsePartition.DEFAULT_TABLE,
            description: String = "create shared one-time-use ledger",
        ): SqlMigration = SqlMigration(version, description, listOf(createTableSql(tableName)))

        /**
         * Attaches the standard live-locking ledger to a consumer-owned SQL
         * runtime after that consumer has applied the shared table migration.
         * Closing the ledger never closes [runtime].
         */
        fun attach(
            runtime: SqlRuntime,
            runtimeName: String,
            partition: MySqlOneTimeUsePartition,
            completionThreads: Int = 2,
            clock: Clock = Clock.systemUTC(),
        ): MySqlOneTimeUseLedger {
            require(completionThreads in 1..8) { "One-time-use completion threads must be between 1 and 8" }
            val completion = SqlExecutor(runtime.dataSource, completionThreads, "$runtimeName-otu-complete")
            return MySqlOneTimeUseLedger(
                runtime = runtime,
                completionExecutor = completion,
                partition = partition,
                store = MySqlOneTimeUseStore(partition, clock),
                ownsRuntime = false,
            )
        }

        /** Opens and owns one pool after the complete consumer migration succeeds. */
        fun open(
            connectionConfig: SqlConnectionConfig,
            runtimeName: String,
            migrationNamespace: String,
            migrations: List<SqlMigration>,
            partition: MySqlOneTimeUsePartition,
            compatibility: SqlMigrationCompatibility = SqlMigrationCompatibility.NONE,
            completionThreads: Int = 2,
            clock: Clock = Clock.systemUTC(),
        ): MySqlOneTimeUseLedger {
            require(completionThreads in 1..8) { "One-time-use completion threads must be between 1 and 8" }
            val runtime = SqlRuntime.create(connectionConfig, runtimeName)
            val completion = SqlExecutor(runtime.dataSource, completionThreads, "$runtimeName-complete")
            return runCatching {
                MySqlMigrator(runtime.dataSource, migrationNamespace).migrate(migrations, compatibility)
                MySqlOneTimeUseLedger(
                    runtime,
                    completion,
                    partition,
                    MySqlOneTimeUseStore(partition, clock),
                    ownsRuntime = true,
                )
            }.getOrElse { failure ->
                completion.close()
                runtime.close()
                throw failure
            }
        }
    }
}

private fun UUID.bytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()

private fun ByteArray.uuid(): UUID {
    require(size == 16) { "Invalid UUID byte length" }
    val buffer = ByteBuffer.wrap(this)
    return UUID(buffer.long, buffer.long)
}
