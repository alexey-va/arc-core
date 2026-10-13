package ru.arc.sql

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection

class SqlRuntimeTest : StringSpec({
    "owned runtime eviction delegates to Hikari's pool entry eviction" {
        val pool = mockk<HikariDataSource>(relaxed = true)
        val connection = mockk<Connection>()
        val runtime = SqlRuntime.ownPool(pool, executorThreads = 1, runtimeName = "sql-runtime-test")

        runtime.evictConnection(connection)

        verify(exactly = 1) { pool.evictConnection(connection) }
        verify(exactly = 0) { connection.abort(any()) }
        runtime.close()
        verify(exactly = 1) { pool.close() }
    }
})
