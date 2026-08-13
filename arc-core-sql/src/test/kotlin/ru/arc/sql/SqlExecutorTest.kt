package ru.arc.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.util.concurrent.ExecutionException
import javax.sql.DataSource

class SqlExecutorTest : StringSpec({
    "submit runs blocking work on the named SQL executor" {
        val dataSource = mockk<DataSource>()

        SqlExecutor(dataSource, 1, "sql-test").use { executor ->
            executor.submit { Thread.currentThread().name }.get() shouldBe "sql-test-1"
        }
    }

    "read marks the pooled connection read-only and closes it" {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection

        SqlExecutor(dataSource, 1, "sql-test").use { executor ->
            executor.read { "value" }.get() shouldBe "value"
        }

        verify(exactly = 1) { connection.isReadOnly = true }
        verify(exactly = 1) { connection.close() }
    }

    "successful transaction commits and restores auto-commit" {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.autoCommit } returns true

        SqlExecutor(dataSource, 1, "sql-test").use { executor ->
            executor.transaction { "committed" }.get() shouldBe "committed"
        }

        verify(exactly = 1) { connection.autoCommit = false }
        verify(exactly = 1) { connection.commit() }
        verify(exactly = 0) { connection.rollback() }
        verify(exactly = 1) { connection.autoCommit = true }
        verify(exactly = 1) { connection.close() }
    }

    "failed transaction rolls back and preserves the original failure" {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.autoCommit } returns true
        val expected = IllegalStateException("write failed")

        val failure =
            SqlExecutor(dataSource, 1, "sql-test").use { executor ->
                shouldThrow<ExecutionException> {
                    executor.transaction<Unit> { throw expected }.get()
                }
            }

        failure.cause shouldBe expected
        verify(exactly = 1) { connection.rollback() }
        verify(exactly = 0) { connection.commit() }
        verify(exactly = 1) { connection.autoCommit = true }
        verify(exactly = 1) { connection.close() }
    }

    "failed state restore is suppressed under the original transaction failure" {
        val connection = mockk<Connection>(relaxed = true)
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } returns connection
        every { connection.autoCommit } returns true
        val restoreFailure = IllegalStateException("restore failed")
        every { connection.autoCommit = true } throws restoreFailure
        val expected = IllegalStateException("write failed")

        val failure =
            SqlExecutor(dataSource, 1, "sql-test").use { executor ->
                shouldThrow<ExecutionException> {
                    executor.transaction<Unit> { throw expected }.get()
                }
            }

        failure.cause shouldBe expected
        expected.suppressed.toList() shouldBe listOf(restoreFailure)
        verify(exactly = 1) { connection.rollback() }
        verify(exactly = 1) { connection.close() }
    }
})
