package ru.arc.sql

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * Ordered, checksum-verified MySQL migrations protected by a database advisory lock.
 *
 * MySQL implicitly commits most DDL. Migration statements therefore must be
 * idempotent so a node can safely retry after a partial server or network failure.
 */
class MySqlMigrator(
    private val dataSource: DataSource,
    namespace: String,
    private val lockTimeoutSeconds: Int = 30,
) {
    private val safeNamespace = namespace.also {
        require(it.matches(Regex("[a-z0-9_]{1,40}"))) { "Unsafe SQL migration namespace" }
    }
    private val historyTable = "${safeNamespace}_schema_history"
    private val lockName = "arc:$safeNamespace:migrations"

    init {
        require(lockTimeoutSeconds in 1..300) { "Migration lock timeout must be between 1 and 300 seconds" }
    }

    fun migrate(migrations: List<SqlMigration>): SqlMigrationReport {
        return migrate(migrations, SqlMigrationCompatibility.NONE)
    }

    /**
     * Migrates while accepting only explicitly source-verified historical checksums.
     * Compatibility is validated before opening a database connection.
     */
    fun migrate(
        migrations: List<SqlMigration>,
        compatibility: SqlMigrationCompatibility,
    ): SqlMigrationReport {
        validatePlan(migrations)
        compatibility.validatePlan(migrations)
        dataSource.connection.use { connection ->
            acquireLock(connection)
            try {
                createHistoryTable(connection)
                val existing = loadHistory(connection)
                verifyChecksums(migrations, existing, compatibility)
                val applied = mutableListOf<Int>()
                for (migration in migrations.sortedBy(SqlMigration::version)) {
                    if (existing.containsKey(migration.version)) continue
                    applyMigration(connection, migration)
                    applied += migration.version
                }
                return SqlMigrationReport(
                    namespace = safeNamespace,
                    appliedVersions = applied,
                    existingVersions = existing.keys.sorted(),
                )
            } finally {
                releaseLock(connection)
            }
        }
    }

    internal fun validatePlan(migrations: List<SqlMigration>) {
        require(migrations.isNotEmpty()) { "At least one SQL migration is required" }
        val duplicates = migrations.groupingBy(SqlMigration::version).eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate SQL migration versions: ${duplicates.sorted()}" }
    }

    private fun acquireLock(connection: Connection) {
        connection.prepareStatement("SELECT GET_LOCK(?, ?)").use { statement ->
            statement.setString(1, lockName)
            statement.setInt(2, lockTimeoutSeconds)
            statement.executeQuery().use { result ->
                check(result.next() && result.getInt(1) == 1) {
                    "Could not acquire SQL migration lock for $safeNamespace"
                }
            }
        }
    }

    private fun releaseLock(connection: Connection) {
        runCatching {
            connection.prepareStatement("SELECT RELEASE_LOCK(?)").use { statement ->
                statement.setString(1, lockName)
                statement.executeQuery().close()
            }
        }
    }

    private fun createHistoryTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `$historyTable` (
                    `version` INT NOT NULL,
                    `description` VARCHAR(255) NOT NULL,
                    `checksum` CHAR(64) NOT NULL,
                    `applied_at` TIMESTAMP(3) NOT NULL,
                    PRIMARY KEY (`version`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """.trimIndent(),
            )
        }
    }

    private fun loadHistory(connection: Connection): Map<Int, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT `version`, `checksum` FROM `$historyTable`").use { result ->
                buildMap {
                    while (result.next()) put(result.getInt("version"), result.getString("checksum"))
                }
            }
        }

    private fun verifyChecksums(
        migrations: List<SqlMigration>,
        existing: Map<Int, String>,
        compatibility: SqlMigrationCompatibility,
    ) {
        for (migration in migrations) {
            val appliedChecksum = existing[migration.version] ?: continue
            check(appliedChecksum == migration.checksum || compatibility.accepts(migration.version, appliedChecksum)) {
                "Applied SQL migration ${migration.version} checksum does not match source"
            }
        }
    }

    private fun applyMigration(
        connection: Connection,
        migration: SqlMigration,
    ) {
        for (sql in migration.statements) {
            connection.createStatement().use { statement -> statement.execute(sql) }
        }
        connection.prepareStatement(
            "INSERT INTO `$historyTable` (`version`, `description`, `checksum`, `applied_at`) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setInt(1, migration.version)
            statement.setString(2, migration.description)
            statement.setString(3, migration.checksum)
            statement.setTimestamp(4, Timestamp.from(Instant.now()))
            statement.executeUpdate()
        }
    }
}
