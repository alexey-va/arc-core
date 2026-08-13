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
        MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

data class SqlMigrationReport(
    val namespace: String,
    val appliedVersions: List<Int>,
    val existingVersions: List<Int>,
)
