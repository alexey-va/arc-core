package ru.arc.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import javax.sql.DataSource

class SqlMigrationTest : StringSpec({
    "migration checksum is deterministic after outer whitespace normalization" {
        SqlMigration(1, "create", listOf("  CREATE TABLE demo (id INT)  ")).checksum shouldBe
            SqlMigration(1, "create", listOf("CREATE TABLE demo (id INT)")).checksum
    }

    "duplicate versions are rejected before opening a connection" {
        val migrator = MySqlMigrator(mockk<DataSource>(), "duels")
        val failure =
            shouldThrow<IllegalArgumentException> {
                migrator.validatePlan(
                    listOf(
                        SqlMigration(1, "first", listOf("SELECT 1")),
                        SqlMigration(1, "duplicate", listOf("SELECT 2")),
                    ),
                )
            }

        failure.message shouldBe "Duplicate SQL migration versions: [1]"
    }

    "unsafe migration namespace is rejected" {
        shouldThrow<IllegalArgumentException> {
            MySqlMigrator(mockk<DataSource>(), "duels-history;drop")
        }
    }

    "legacy concatenated checksums are explicit and version bounded" {
        val migration = SqlMigration(4, "two tables", listOf(" CREATE TABLE one (id INT) ", "CREATE TABLE two (id INT)"))
        val compatibility = SqlMigrationCompatibility.legacyConcatenated(migration)

        compatibility.accepts(4, "CREATE TABLE one (id INT)\nCREATE TABLE two (id INT)".sha256ForTest()) shouldBe true
        compatibility.accepts(3, "CREATE TABLE one (id INT)\nCREATE TABLE two (id INT)".sha256ForTest()) shouldBe false
    }

    "compatibility rejects malformed checksums and versions absent from the plan" {
        shouldThrow<IllegalArgumentException> {
            SqlMigrationCompatibility(mapOf(1 to setOf("not-a-checksum")))
        }

        val compatibility = SqlMigrationCompatibility(mapOf(2 to setOf("0".repeat(64))))
        shouldThrow<IllegalArgumentException> {
            compatibility.validatePlan(listOf(SqlMigration(1, "first", listOf("SELECT 1"))))
        }
    }
})

private fun String.sha256ForTest(): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
