package ru.arc.sql.onetime

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.sql.SqlConnectionConfig
import ru.arc.sql.SqlSslMode
import ru.arc.testing.containers.MySqlTestService
import ru.arc.testing.containers.MySqlTestSettings
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MySqlOneTimeUseLedgerIntegrationTest : FreeSpec({
    lateinit var mysql: MySqlTestService
    lateinit var config: SqlConnectionConfig
    val partition = MySqlOneTimeUsePartition("arc.test.uses")

    beforeSpec {
        mysql = MySqlTestService.start(MySqlTestSettings(database = "arc_one_time_test"))
        config = SqlConnectionConfig(
            host = mysql.endpoint.host,
            port = mysql.endpoint.port,
            database = mysql.endpoint.database,
            username = mysql.endpoint.username,
            password = mysql.endpoint.password,
            sslMode = SqlSslMode.DISABLED,
            minimumIdle = 0,
            maximumPoolSize = 2,
        )
    }

    afterSpec { mysql.close() }

    fun open(): MySqlOneTimeUseLedger = MySqlOneTimeUseLedger.open(
        connectionConfig = config,
        runtimeName = "one-time-test",
        migrationNamespace = "arc_one_time_test",
        migrations = listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
        partition = partition,
    )

    fun request(
        useId: UUID = UUID.randomUUID(),
        fingerprint: OneTimeUseFingerprint = OneTimeUseFingerprint.sha256(UUID.randomUUID().toString().toByteArray()),
        claimantId: UUID = UUID.randomUUID(),
        claimId: UUID = UUID.randomUUID(),
    ) = OneTimeUseClaimRequest(OneTimeUseIdentity(useId, fingerprint), claimId, claimantId)

    "two nodes grant one exclusive claim and committed identity never reopens" {
        val first = open()
        val second = open()
        try {
            val base = request()
            val gate = CountDownLatch(1)
            val attempts = listOf(first, second).map { ledger ->
                CompletableFuture.supplyAsync {
                    gate.await()
                    ledger to ledger.claim(base.copy(claimId = UUID.randomUUID(), claimantId = UUID.randomUUID())).join()
                }
            }
            gate.countDown()
            val results = attempts.map { it.join() }
            results.map { it.second }
                .filterIsInstance<OneTimeUseClaimResult.Acquired>() shouldHaveSize 1
            results.map { it.second }
                .filterIsInstance<OneTimeUseClaimResult.Busy>() shouldHaveSize 1

            val (owner, acquired) = results.single { it.second is OneTimeUseClaimResult.Acquired }
            owner.commit(acquired.shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim).join() shouldBe
                OneTimeUseCommitResult.COMMITTED
            first.claim(base).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
            second.claim(base).join() shouldBe OneTimeUseClaimResult.AlreadyConsumed
        } finally {
            first.close()
            second.close()
        }
    }

    "release, recovery, abandon and identity conflict remain explicit" {
        open().use { ledger ->
            val initial = request()
            val first = ledger.claim(initial).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            first.newlyCreated shouldBe true
            ledger.activeClaims shouldBe 1
            ledger.release(first).join() shouldBe OneTimeUseReleaseResult.RELEASED
            ledger.activeClaims shouldBe 0

            val transferred = initial.copy(claimId = UUID.randomUUID(), claimantId = UUID.randomUUID())
            val transferredClaim = ledger.claim(transferred).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            ledger.abandon(transferredClaim).join() shouldBe OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY
            ledger.activeClaims shouldBe 0

            ledger.claim(transferred.copy(claimantId = UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.Busy
            ledger.claim(transferred.copy(claimId = UUID.randomUUID())).join() shouldBe OneTimeUseClaimResult.Busy
            val recovered = ledger.claim(transferred).join()
                .shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            recovered.newlyCreated shouldBe false
            recovered.claimId shouldBe transferredClaim.claimId
            ledger.commit(recovered).join() shouldBe OneTimeUseCommitResult.COMMITTED

            ledger.claim(
                initial.copy(
                    identity = initial.identity.copy(
                        fingerprint = OneTimeUseFingerprint.sha256("different".toByteArray()),
                    ),
                    claimId = UUID.randomUUID(),
                ),
            ).join() shouldBe OneTimeUseClaimResult.IdentityConflict
        }
    }

    "completion queue releases capacity while every pool connection is retained" {
        open().use { ledger ->
            val first = ledger.claim(request()).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            val second = ledger.claim(request()).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            val waiting = ledger.claim(request())

            ledger.commit(first).get(2, TimeUnit.SECONDS) shouldBe OneTimeUseCommitResult.COMMITTED
            val third = waiting.get(2, TimeUnit.SECONDS).shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            ledger.release(second).join() shouldBe OneTimeUseReleaseResult.RELEASED
            ledger.release(third).join() shouldBe OneTimeUseReleaseResult.RELEASED
        }
    }

    "forged completion handle is rejected without consuming the live claim" {
        open().use { ledger ->
            val claim = ledger.claim(request()).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
            val forged = OneTimeUseClaim(
                identity = claim.identity,
                claimId = claim.claimId,
                claimantId = UUID.randomUUID(),
                scope = claim.scope,
                newlyCreated = claim.newlyCreated,
            )
            ledger.commit(forged).join() shouldBe OneTimeUseCommitResult.REJECTED
            ledger.commit(claim).join() shouldBe OneTimeUseCommitResult.COMMITTED
        }
    }

    "the same identity is independent across purpose partitions" {
        val identity = request()
        val other = MySqlOneTimeUseLedger.open(
            connectionConfig = config,
            runtimeName = "one-time-other-test",
            migrationNamespace = "arc_one_time_test",
            migrations = listOf(MySqlOneTimeUseLedger.createTableMigration(1)),
            partition = MySqlOneTimeUsePartition("arc.test.other"),
        )
        open().use { first ->
            other.use { second ->
                val firstClaim = first.claim(identity).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
                val secondClaim = second.claim(identity).join().shouldBeInstanceOf<OneTimeUseClaimResult.Acquired>().claim
                first.commit(firstClaim).join() shouldBe OneTimeUseCommitResult.COMMITTED
                second.release(secondClaim).join() shouldBe OneTimeUseReleaseResult.RELEASED
            }
        }
    }
})
