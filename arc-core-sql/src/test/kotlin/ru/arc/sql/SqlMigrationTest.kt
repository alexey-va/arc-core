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
})
