package ru.arc.onetime

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Immutable SHA-256 evidence bound to one redeemable identity.
 *
 * Callers must fingerprint every signed or authoritative field that changes
 * what the bearer capability can do. A matching use id with different evidence
 * is an identity conflict and must fail closed.
 */
@JvmInline
value class OneTimeUseFingerprint private constructor(val sha256: String) {
    init {
        require(SHA256.matches(sha256)) { "One-time-use fingerprint must be lowercase SHA-256" }
    }

    fun bytes(): ByteArray = sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private val SHA256 = Regex("[a-f0-9]{64}")

        fun parse(sha256: String): OneTimeUseFingerprint = OneTimeUseFingerprint(sha256)

        fun fromBytes(bytes: ByteArray): OneTimeUseFingerprint {
            require(bytes.size == 32) { "One-time-use fingerprint must contain 32 bytes" }
            return OneTimeUseFingerprint(bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }

        /** Hashes one opaque payload without interpreting its encoding. */
        fun sha256(payload: ByteArray): OneTimeUseFingerprint = fromBytes(MessageDigest.getInstance("SHA-256").digest(payload))

        /**
         * Hashes bounded UTF-8 fields with length prefixes, so field boundaries
         * cannot collide through concatenation.
         */
        fun sha256Fields(vararg fields: String): OneTimeUseFingerprint {
            require(fields.size in 1..32) { "One-time-use fingerprint requires between 1 and 32 fields" }
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { field ->
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                require(bytes.size <= 4_096) { "One-time-use fingerprint field exceeds 4096 UTF-8 bytes" }
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return fromBytes(digest.digest())
        }
    }
}

/** Bounded routing/ownership scope stored with a claim, such as a backend id. */
@JvmInline
value class OneTimeUseScope private constructor(val value: String) {
    init {
        require(value.matches(SAFE_SCOPE)) { "One-time-use scope must be 1..64 safe ASCII characters" }
    }

    companion object {
        private val SAFE_SCOPE = Regex("[A-Za-z0-9_.-]{1,64}")

        fun parse(value: String): OneTimeUseScope = OneTimeUseScope(value)
    }
}

data class OneTimeUseIdentity(
    val useId: UUID,
    val fingerprint: OneTimeUseFingerprint,
)

/**
 * Idempotent request for exclusive authority to apply one irreversible effect.
 *
 * [claimId] is the caller's durable operation/idempotency id and must be reused
 * unchanged during recovery. [claimantId] identifies the authenticated player
 * or owner of recovery; it is not an issued-to restriction and does not
 * prevent bearer transfer before the first claim. Once a claim exists, every
 * field in this request must match exactly or the ledger fails closed.
 */
data class OneTimeUseClaimRequest(
    val identity: OneTimeUseIdentity,
    val claimId: UUID,
    val claimantId: UUID,
    val scope: OneTimeUseScope? = null,
)

data class OneTimeUseClaim(
    val identity: OneTimeUseIdentity,
    val claimId: UUID,
    val claimantId: UUID,
    val scope: OneTimeUseScope? = null,
    val newlyCreated: Boolean,
) {
    fun asRequest(): OneTimeUseClaimRequest = OneTimeUseClaimRequest(identity, claimId, claimantId, scope)

    companion object {
        fun acquired(request: OneTimeUseClaimRequest, newlyCreated: Boolean): OneTimeUseClaim =
            OneTimeUseClaim(request.identity, request.claimId, request.claimantId, request.scope, newlyCreated)
    }
}

sealed interface OneTimeUseClaimResult {
    data class Acquired(val claim: OneTimeUseClaim) : OneTimeUseClaimResult
    data object AlreadyConsumed : OneTimeUseClaimResult
    data object Busy : OneTimeUseClaimResult
    data object Missing : OneTimeUseClaimResult
    data object IdentityConflict : OneTimeUseClaimResult
}

enum class OneTimeUseCommitResult {
    COMMITTED,
    ALREADY_COMMITTED,
    REJECTED,
}

enum class OneTimeUseReleaseResult {
    RELEASED,
    ALREADY_RELEASED,
    REJECTED,
}

enum class OneTimeUseAbandonResult {
    RETAINED_FOR_RECOVERY,
    ALREADY_COMMITTED,
    REJECTED,
}

/**
 * Network-safe one-time effect protocol.
 *
 * All methods may perform blocking storage work and therefore complete away
 * from Paper/Velocity threads. `claim` durably reserves one identity before the
 * caller mutates value. `commit` is safe only after that mutation is known to
 * have succeeded. `release` is for a proven pre-mutation failure. `abandon`
 * drops only the live execution lock after an unknown mutation outcome while
 * retaining durable recovery ownership.
 *
 * Exceptional completion means the storage outcome is unknown. Never consume
 * an item, retry under another claimant, or release recovery state merely from
 * an exception.
 */
interface OneTimeUseLedger : AutoCloseable {
    val available: Boolean get() = true
    /** Non-blocking count of live claims currently holding execution locks. */
    val activeClaims: Int get() = 0

    fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult>

    fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult>

    fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult>

    fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult>

    override fun close() = Unit
}

/** Fail-closed placeholder used while durable storage is disabled or unavailable. */
object UnavailableOneTimeUseLedger : OneTimeUseLedger {
    override val available: Boolean = false

    private fun <T> unavailable(): CompletableFuture<T> =
        CompletableFuture.failedFuture(IllegalStateException("one-time-use ledger is unavailable"))

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> = unavailable()
    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> = unavailable()
    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> = unavailable()
    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> = unavailable()
}
