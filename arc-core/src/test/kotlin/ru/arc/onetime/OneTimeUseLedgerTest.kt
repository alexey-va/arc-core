package ru.arc.onetime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CompletionException

class OneTimeUseLedgerTest : FreeSpec({
    "fingerprints validate exact bytes and unambiguous field boundaries" {
        val bytes = ByteArray(32) { it.toByte() }
        OneTimeUseFingerprint.fromBytes(bytes).bytes().toList() shouldBe bytes.toList()
        OneTimeUseFingerprint.sha256Fields("ab", "c") shouldBe OneTimeUseFingerprint.sha256Fields("ab", "c")
        (OneTimeUseFingerprint.sha256Fields("ab", "c") == OneTimeUseFingerprint.sha256Fields("a", "bc")) shouldBe false

        shouldThrow<IllegalArgumentException> { OneTimeUseFingerprint.fromBytes(ByteArray(31)) }
        shouldThrow<IllegalArgumentException> { OneTimeUseFingerprint.parse("A".repeat(64)) }
        shouldThrow<IllegalArgumentException> { OneTimeUseFingerprint.sha256Fields("x".repeat(4_097)) }
    }

    "claim retains the complete recovery identity" {
        val request = OneTimeUseClaimRequest(
            identity = OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256("payload".toByteArray())),
            claimId = UUID.randomUUID(),
            claimantId = UUID.randomUUID(),
            scope = OneTimeUseScope.parse("survival"),
        )

        val claim = OneTimeUseClaim.acquired(request, newlyCreated = true)
        claim.asRequest() shouldBe request
        claim.newlyCreated shouldBe true
    }

    "scope is bounded and fail-closed ledger exposes storage failure" {
        shouldThrow<IllegalArgumentException> { OneTimeUseScope.parse("unsafe scope") }
        shouldThrow<IllegalArgumentException> { OneTimeUseScope.parse("x".repeat(65)) }

        val request = OneTimeUseClaimRequest(
            OneTimeUseIdentity(UUID.randomUUID(), OneTimeUseFingerprint.sha256(ByteArray(0))),
            UUID.randomUUID(),
            UUID.randomUUID(),
        )
        shouldThrow<CompletionException> { UnavailableOneTimeUseLedger.claim(request).join() }
    }
})
