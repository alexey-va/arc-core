package ru.arc.paper.sql

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sql.MySqlMigrator
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlRuntime
import ru.arc.sql.SqlSslMode
import ru.arc.sql.onetime.MySqlOneTimeUseLedger
import ru.arc.sql.onetime.MySqlOneTimeUsePartition
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.nio.ByteBuffer
import java.util.concurrent.CompletionException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class PaperSqlServiceIntegrationTest : StringSpec({
    "consumer lease shares the provider pool and commits with normal workers saturated" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_paper_shared_sql_test")).use { mysql ->
            MockBukkitTestRuntime.open().use { paper ->
                val endpoint = mysql.endpoint
                val config = SqlConnectionConfig(
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = SqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                )
                val providerPlugin = paper.createSimplePlugin("ArcSqlHost")
                val consumerPlugin = paper.createSimplePlugin("ArcSqlConsumer")
                val provider = PaperSqlServiceProvider(providerPlugin)
                val binding = PaperSqlServices.installConsumer(consumerPlugin)
                val settings = config.toArcSqlSettings()
                val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
                val probeLease = service.acquire("integration-probe", settings)
                var runtime: SqlRuntime? = null
                var ledger: MySqlOneTimeUseLedger? = null
                val workersStarted = CountDownLatch(2)
                val unblockWorkers = CountDownLatch(1)
                val workersFinished = CountDownLatch(2)
                val completionWorkersStarted = CountDownLatch(2)
                val unblockCompletionWorkers = CountDownLatch(1)
                val completionWorkersFinished = CountDownLatch(2)
                try {
                    val consumerRuntime = SqlRuntime.create(config, "paper-shared-consumer")
                    runtime = consumerRuntime
                    service.snapshot().single().borrowers shouldBe 2
                    MySqlMigrator(consumerRuntime.dataSource, "paper_shared_sql_ledger").migrate(
                        listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                    )
                    val partition = MySqlOneTimeUsePartition("paper.shared.sql.integration")
                    val attachedLedger = MySqlOneTimeUseLedger.attach(
                        runtime = consumerRuntime,
                        runtimeName = "paper-shared-ledger",
                        partition = partition,
                    )
                    ledger = attachedLedger
                    val claim = attachedLedger.claim(request()).get(5, TimeUnit.SECONDS)
                        .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim

                    repeat(2) {
                        consumerRuntime.executor.execute {
                            workersStarted.countDown()
                            check(unblockWorkers.await(5, TimeUnit.SECONDS))
                            workersFinished.countDown()
                        }
                    }
                    workersStarted.await(5, TimeUnit.SECONDS) shouldBe true
                    repeat(2) {
                        probeLease.completionExecutor.execute {
                            completionWorkersStarted.countDown()
                            check(unblockCompletionWorkers.await(5, TimeUnit.SECONDS))
                            completionWorkersFinished.countDown()
                        }
                    }
                    completionWorkersStarted.await(5, TimeUnit.SECONDS) shouldBe true

                    val commit = attachedLedger.commit(claim)
                    attachedLedger.close()
                    val rejectedClaim = shouldThrow<CompletionException> {
                        attachedLedger.claim(request()).join()
                    }
                    rejectedClaim.cause.shouldBeInstanceOf<RejectedExecutionException>()

                    unblockCompletionWorkers.countDown()
                    commit.get(5, TimeUnit.SECONDS) shouldBe OneTimeUseCommitResult.COMMITTED
                    attachedLedger.activeClaims shouldBe 0
                    service.snapshot().single().openConnections shouldBe 0
                } finally {
                    unblockWorkers.countDown()
                    unblockCompletionWorkers.countDown()
                    workersFinished.await(5, TimeUnit.SECONDS)
                    completionWorkersFinished.await(5, TimeUnit.SECONDS)
                    ledger?.close()
                    runtime?.close()
                    probeLease.close()
                    binding.close()
                    provider.close()
                }
            }
        }
    }

    "canceled accepted claim drains and releases its connection after provider shutdown" {
        MySqlTestService.start(MySqlTestSettings(database = "arc_paper_canceled_sql_test")).use { mysql ->
            MockBukkitTestRuntime.open().use { paper ->
                val endpoint = mysql.endpoint
                val config = SqlConnectionConfig(
                    host = endpoint.host,
                    port = endpoint.port,
                    database = endpoint.database,
                    username = endpoint.username,
                    password = endpoint.password,
                    sslMode = SqlSslMode.DISABLED,
                    minimumIdle = 0,
                    maximumPoolSize = 2,
                )
                val partition = MySqlOneTimeUsePartition("paper.canceled.sql.integration")
                val providerPlugin = paper.createSimplePlugin("ArcCanceledSqlHost")
                val consumerPlugin = paper.createSimplePlugin("ArcCanceledSqlConsumer")
                val provider = PaperSqlServiceProvider(providerPlugin)
                val service = paper.server.servicesManager.load(ArcSqlService::class.java)!!
                val binding = PaperSqlServices.installConsumer(consumerPlugin)
                var runtime: SqlRuntime? = null
                var ledger: MySqlOneTimeUseLedger? = null
                val workersStarted = CountDownLatch(2)
                val unblockWorkers = CountDownLatch(1)
                val workersFinished = CountDownLatch(2)
                try {
                    val consumerRuntime = SqlRuntime.create(config, "paper-canceled-consumer")
                    runtime = consumerRuntime
                    MySqlMigrator(consumerRuntime.dataSource, "paper_canceled_sql_ledger").migrate(
                        listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
                    )
                    val attachedLedger = MySqlOneTimeUseLedger.attach(
                        runtime = consumerRuntime,
                        runtimeName = "paper-canceled-ledger",
                        partition = partition,
                    )
                    ledger = attachedLedger
                    val claimRequest = request()
                    repeat(2) {
                        consumerRuntime.executor.execute {
                            workersStarted.countDown()
                            check(unblockWorkers.await(5, TimeUnit.SECONDS))
                            workersFinished.countDown()
                        }
                    }
                    workersStarted.await(5, TimeUnit.SECONDS) shouldBe true

                    val canceledClaim = attachedLedger.claim(claimRequest)
                    canceledClaim.cancel(false) shouldBe true
                    attachedLedger.close()
                    consumerRuntime.close()
                    binding.close()
                    provider.close()
                    unblockWorkers.countDown()
                    workersFinished.await(5, TimeUnit.SECONDS) shouldBe true

                    val verificationRuntime = SqlRuntime.createOwned(config, "paper-canceled-sql-verification")
                    try {
                        val status = awaitClaimStatus(verificationRuntime, partition, claimRequest.claimId)
                        status shouldBe "CLAIMED"
                    } finally {
                        verificationRuntime.close()
                    }

                    awaitServiceRetirement(service)
                    service.snapshot() shouldBe emptyList()
                } finally {
                    unblockWorkers.countDown()
                    workersFinished.await(5, TimeUnit.SECONDS)
                    ledger?.close()
                    runtime?.close()
                    binding.close()
                    provider.close()
                }
            }
        }
    }
})

private fun request(): OneTimeUseClaimRequest =
    OneTimeUseClaimRequest(
        identity = OneTimeUseIdentity(
            useId = UUID.randomUUID(),
            fingerprint = OneTimeUseFingerprint.sha256(UUID.randomUUID().toString().toByteArray()),
        ),
        claimId = UUID.randomUUID(),
        claimantId = UUID.randomUUID(),
    )

private fun awaitServiceRetirement(service: ArcSqlService) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (service.snapshot().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
}

private fun awaitClaimStatus(
    runtime: SqlRuntime,
    partition: MySqlOneTimeUsePartition,
    claimId: UUID,
): String? {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    runtime.dataSource.connection.use { connection ->
        while (System.nanoTime() < deadline) {
            val status = connection.prepareStatement(
                "SELECT `status` FROM `arc_one_time_uses` WHERE `purpose` = ? AND `claim_id` = ?",
            ).use { statement ->
                statement.setString(1, partition.purpose)
                statement.setBytes(2, claimId.toSqlBytes())
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (status != null) return status
            Thread.sleep(10)
        }
    }
    return null
}

private fun UUID.toSqlBytes(): ByteArray = ByteBuffer.allocate(16)
    .putLong(mostSignificantBits)
    .putLong(leastSignificantBits)
    .array()

private fun SqlConnectionConfig.toArcSqlSettings() =
    ArcSqlSettings(
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        sslMode = ArcSqlSslMode.valueOf(sslMode.name),
        minimumIdle = minimumIdle,
        maximumPoolSize = maximumPoolSize,
        connectionTimeoutMs = connectionTimeoutMs,
        socketTimeoutMs = socketTimeoutMs,
        validationTimeoutMs = validationTimeoutMs,
        maxLifetimeMs = maxLifetimeMs,
        failFast = failFast,
    )
