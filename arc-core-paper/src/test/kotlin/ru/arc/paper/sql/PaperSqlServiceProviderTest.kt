package ru.arc.paper.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class PaperSqlServiceProviderTest : StringSpec({
    "identical full settings share one pool and leases close independently" {
        MockBukkitTestRuntime.open().use { paper ->
            val runtime = mockRuntime(mockk())
            val created = AtomicInteger()
            val provider = PaperSqlServiceProvider(paper.createSimplePlugin("ArcSqlHost")) { _, _ ->
                created.incrementAndGet()
                runtime
            }
            try {
                val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
                val first = service.acquire("ArcFarms", settings())
                val second = service.acquire("ArcDuels", settings())

                created.get() shouldBe 1
                service.snapshot().single().borrowers shouldBe 2
                service.snapshot().single().owners shouldBe listOf("ArcDuels", "ArcFarms")

                first.close()
                service.snapshot().single().borrowers shouldBe 1
                second.close()
            } finally {
                provider.close()
            }
        }
    }

    "eviction delegates the underlying connection to its owning runtime and retires the wrapper" {
        MockBukkitTestRuntime.open().use { paper ->
            val target = mockk<Connection>(relaxed = true)
            val dataSource = mockk<DataSource>()
            every { dataSource.connection } returns target
            val runtime = mockRuntime(dataSource)
            val provider = PaperSqlServiceProvider(paper.createSimplePlugin("ArcSqlHost")) { _, _ -> runtime }
            try {
                val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
                val lease = service.acquire("ArcDuels", settings())
                val wrapper = lease.dataSource.connection

                lease.evictConnection(wrapper)

                verify(exactly = 1) { runtime.evictConnection(target) }
                verify(exactly = 0) { target.close() }
                verify(exactly = 0) { target.abort(any()) }
                wrapper.isClosed shouldBe true
                wrapper.close()
                wrapper.abort(java.util.concurrent.Executor { command -> command.run() })
                wrapper.equals(wrapper) shouldBe true
                wrapper.hashCode() shouldBe System.identityHashCode(wrapper)
                wrapper.toString().contains("closed=true") shouldBe true
                shouldThrow<SQLException> { wrapper.autoCommit }
                service.snapshot().single().openConnections shouldBe 0
                lease.close()
            } finally {
                provider.close()
            }
        }
    }

    "provider close drains accepted queued completion work and rejects later work" {
        MockBukkitTestRuntime.open().use { paper ->
            val runtimeClosed = CountDownLatch(1)
            val runtime = mockRuntime(mockk())
            every { runtime.close() } answers { runtimeClosed.countDown() }
            val provider = PaperSqlServiceProvider(paper.createSimplePlugin("ArcSqlHost")) { _, _ -> runtime }
            val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
            val lease = service.acquire("ArcFarms", settings())
            val workersStarted = CountDownLatch(2)
            val unblock = CountDownLatch(1)
            val finished = CountDownLatch(3)
            val closeReturned = AtomicBoolean()
            val returnedBeforeDrain = AtomicBoolean()
            val delayedRelease = Thread {
                Thread.sleep(100)
                returnedBeforeDrain.set(closeReturned.get())
                unblock.countDown()
            }.apply { isDaemon = true }
            try {
                repeat(2) {
                    lease.completionExecutor.execute {
                        workersStarted.countDown()
                        check(unblock.await(30, TimeUnit.SECONDS))
                        finished.countDown()
                    }
                }
                workersStarted.await(5, TimeUnit.SECONDS) shouldBe true
                lease.completionExecutor.execute { finished.countDown() }

                delayedRelease.start()
                provider.close()
                closeReturned.set(true)
                delayedRelease.join(5_000)

                returnedBeforeDrain.get() shouldBe false
                finished.await(5, TimeUnit.SECONDS) shouldBe true
                shouldThrow<RejectedExecutionException> { lease.completionExecutor.execute { } }
                runtimeClosed.await(5, TimeUnit.SECONDS) shouldBe true
            } finally {
                unblock.countDown()
                provider.close()
            }
        }
    }

    "provider shutdown timeout leaves an active borrowed connection open for async drain" {
        MockBukkitTestRuntime.open().use { paper ->
            val target = mockk<Connection>(relaxed = true)
            val dataSource = mockk<DataSource>()
            every { dataSource.connection } returns target
            every { target.isClosed } returns false
            val runtimeClosed = CountDownLatch(1)
            val runtime = mockRuntime(dataSource)
            every { runtime.close() } answers { runtimeClosed.countDown() }
            val provider = PaperSqlServiceProvider(
                paper.createSimplePlugin("ArcSqlHost"),
                runtimeFactory = { _, _ -> runtime },
                shutdownTimeoutMs = 100,
            )
            val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
            val lease = service.acquire("ArcFarms", settings())
            val connection = lease.dataSource.connection
            try {
                val startedAt = System.nanoTime()
                provider.close()
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                (elapsedMs < 1_000) shouldBe true
                connection.isClosed shouldBe false
                verify(exactly = 0) { target.close() }
                service.snapshot().single().openConnections shouldBe 1

                connection.close()
                runtimeClosed.await(5, TimeUnit.SECONDS) shouldBe true
                verify(exactly = 1) { target.close() }
            } finally {
                connection.close()
                provider.close()
            }
        }
    }

    "consumer installs without a provider but fails closed when SQL is requested" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin: Plugin = paper.createSimplePlugin("ArcSqlConsumer")
            val binding = PaperSqlServices.installConsumer(plugin)
            try {
                shouldThrow<IllegalStateException> {
                    SqlRuntime.create(
                        SqlConnectionConfig(
                            host = "localhost",
                            database = "arc_test",
                            username = "test",
                            password = "secret",
                            sslMode = SqlSslMode.DISABLED,
                            minimumIdle = 0,
                            maximumPoolSize = 1,
                        ),
                        "sql-consumer-test",
                    )
                }.message shouldBe "Required ArcSqlService is not registered; refusing a private SQL pool"
            } finally {
                binding.close()
            }
        }
    }
})

private fun mockRuntime(dataSource: DataSource): SqlRuntime = mockk(relaxed = true) {
    every { this@mockk.dataSource } returns dataSource
    every { this@mockk.executor } returns mockk(relaxed = true)
}

private fun settings() =
    ArcSqlSettings(
        host = "localhost",
        port = 3306,
        database = "arc_test",
        username = "test",
        password = "secret",
        sslMode = ArcSqlSslMode.DISABLED,
        minimumIdle = 0,
        maximumPoolSize = 2,
        connectionTimeoutMs = 5_000,
        socketTimeoutMs = 5_000,
        validationTimeoutMs = 1_000,
        maxLifetimeMs = 60_000,
        failFast = false,
    )
