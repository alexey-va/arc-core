package ru.arc.sql

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class SqlMigration(
    val version: Int,
    val description: String,
    val statements: List<String>,
) {
    init {
        require(version > 0) { "SQL migration version must be positive" }
        require(description.isNotBlank()) { "SQL migration description must not be blank" }
        require(description.length <= 255) { "SQL migration description must not exceed 255 characters" }
        require(statements.isNotEmpty()) { "SQL migration must contain statements" }
        require(statements.none(String::isBlank)) { "SQL migration statements must not be blank" }
    }

    val checksum: String by lazy {
        val canonical = statements.joinToString("\n-- statement --\n") { it.trim() }
        canonical.sha256()
    }
}

data class SqlMigrationReport(
    val namespace: String,
    val appliedVersions: List<Int>,
    val existingVersions: List<Int>,
)

/**
 * Explicit compatibility policy for checksums written by an older migrator.
 *
 * New rows always use [SqlMigration.checksum]. A legacy checksum is accepted
 * only for the named version and never weakens the ordered migration plan.
 */
class SqlMigrationCompatibility(
    acceptedChecksumsByVersion: Map<Int, Set<String>>,
) {
    private val acceptedChecksumsByVersion =
        acceptedChecksumsByVersion.mapValues { (_, checksums) -> checksums.toSet() }.toMap()

    init {
        require(this.acceptedChecksumsByVersion.size <= MAX_VERSIONS) {
            "SQL migration compatibility must not exceed $MAX_VERSIONS versions"
        }
        this.acceptedChecksumsByVersion.forEach { (version, checksums) ->
            require(version > 0) { "SQL migration compatibility version must be positive" }
            require(checksums.isNotEmpty()) { "SQL migration compatibility checksums must not be empty" }
            require(checksums.size <= MAX_CHECKSUMS_PER_VERSION) {
                "SQL migration compatibility must not exceed $MAX_CHECKSUMS_PER_VERSION checksums per version"
            }
            require(checksums.all(SHA256::matches)) {
                "SQL migration compatibility checksums must be lowercase SHA-256"
            }
        }
    }

    internal fun accepts(version: Int, checksum: String): Boolean =
        checksum in acceptedChecksumsByVersion[version].orEmpty()

    internal fun validatePlan(migrations: List<SqlMigration>) {
        val plannedVersions = migrations.mapTo(hashSetOf(), SqlMigration::version)
        val unknownVersions = acceptedChecksumsByVersion.keys - plannedVersions
        require(unknownVersions.isEmpty()) {
            "SQL migration compatibility references unknown versions: ${unknownVersions.sorted()}"
        }
    }

    companion object {
        val NONE = SqlMigrationCompatibility(emptyMap())

        /** Reproduces the historical checksum of trimmed statements joined without a marker. */
        fun legacyConcatenated(
            vararg migrations: SqlMigration,
            separator: String = "\n",
        ): SqlMigrationCompatibility {
            require(separator.length <= 64) { "Legacy SQL migration checksum separator is too long" }
            val checksums = migrations.associate { migration ->
                val canonical = migration.statements.joinToString(separator) { it.trim() }
                migration.version to setOf(canonical.sha256())
            }
            require(checksums.size == migrations.size) { "Duplicate legacy SQL migration versions" }
            return SqlMigrationCompatibility(checksums)
        }

        private const val MAX_VERSIONS = 256
        private const val MAX_CHECKSUMS_PER_VERSION = 8
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
