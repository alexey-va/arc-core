package ru.arc.sql.onetime

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.sql.SqlRuntime
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class MySqlOneTimeUseLedgerDrainTest : FreeSpec({
    "closeAsync waits for accepted claims and attached cleanup without closing the borrowed runtime" {
        val pending = ArrayDeque<Runnable>()
        val dataSource = mockk<DataSource>()
        every { dataSource.connection } throws SQLException("delayed connection failure")
        var released = false
        val runtime = SqlRuntime.borrow(dataSource, Executor(pending::addLast), Executor(Runnable::run),
            AutoCloseable { released = true }, {})
        val ledger = MySqlOneTimeUseLedger.attach(runtime, "drain-test", MySqlOneTimeUsePartition("drain-test"))
        try {
            val request = OneTimeUseClaimRequest(
                OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256Fields("claim")),
                UUID.randomUUID(), UUID.randomUUID(),
            )
            val accepted = ledger.claim(request)
            val drain = ledger.closeAsync()
            accepted.isDone shouldBe false
            drain.isDone shouldBe false
            ledger.claim(request).isCompletedExceptionally shouldBe true
            pending.removeFirst().run()
            accepted.isCompletedExceptionally shouldBe true
            drain.get(5, TimeUnit.SECONDS)
            released shouldBe false
        } finally {
            ledger.closeAsync().get(5, TimeUnit.SECONDS)
            runtime.close()
        }
        released shouldBe true
    }
})
