package ru.arc.paper.api

import org.bukkit.inventory.ItemStack

private fun String.hasControlCharacters(): Boolean =
    any { it.code <= 0x1f || it.code in 0x7f..0x9f }

/**
 * Optional ARC-owned bridge for preparing exact physical reward outcomes.
 *
 * Calls are synchronous and must run on the Paper main thread. [prepare] is
 * the freeze boundary: it may read provider state and randomize one outcome,
 * but it must not mutate a player or mint a one-time bearer identity.
 * [materialize] returns detached stacks. A stale or unavailable reference is
 * rejected with `null` and must not be remapped to another catalogue entry.
 * A [ArcItemMaterializationReference.FreshVoucher] is a provider-bound
 * recipe, not a historical provider archive; a consumer that must deliver
 * through provider removal or definition changes must persist that definition
 * in its own durable journal and provide the corresponding redemption path.
 */
interface ArcItemMaterializer {
    fun capability(): ArcItemMaterializerCapabilitySnapshot

    fun prepare(request: ArcItemMaterializationRequest): ArcItemMaterializationReference?

    fun materialize(reference: ArcItemMaterializationReference): List<ItemStack>?
}

/** A stable lookup into the ARC reward catalogue. */
data class ArcItemMaterializationRequest(
    val categoryId: String,
    val entryId: String,
) {
    init {
        require(isValidIdentifier(categoryId)) {
            "Reward category id is invalid"
        }
        require(isValidIdentifier(entryId)) {
            "Reward entry id is invalid"
        }
    }

    companion object {
        private const val MAX_ID_LENGTH = 256

        private fun isValidIdentifier(value: String): Boolean =
            value.isNotBlank() &&
                value.length <= MAX_ID_LENGTH &&
                !value.hasControlCharacters()
    }
}

/**
 * Current capability, suitable for recording with an immutable season.
 * The fingerprint describes the loaded catalogue definition; it is a snapshot
 * marker and does not archive provider definitions. References carry their own
 * provider fingerprint for sources that must be recreated later.
 */
data class ArcItemMaterializerCapabilitySnapshot(
    val available: Boolean,
    val catalogFingerprint: String?,
) {
    init {
        if (available) {
            require(!catalogFingerprint.isNullOrBlank()) {
                "Available materializer requires a catalog fingerprint"
            }
        }
    }
}

/** A frozen reference produced by [ArcItemMaterializer.prepare]. */
sealed interface ArcItemMaterializationReference {
    val request: ArcItemMaterializationRequest
    val providerFingerprint: String

    /**
     * Exact item templates frozen at preparation time. Materializers must
     * return clones; this reference also clones inputs at construction and
     * returns fresh detached copies on every [templates] access.
     */
    class FrozenItems(
        override val request: ArcItemMaterializationRequest,
        override val providerFingerprint: String,
        templates: List<ItemStack>,
    ) : ArcItemMaterializationReference {
        init {
            require(providerFingerprint.isNotBlank()) { "Frozen item fingerprint is blank" }
            require(templates.isNotEmpty()) { "Frozen item reference is empty" }
            require(templates.size <= MAX_STACKS) { "Frozen item reference is too large" }
            require(templates.none { it.type.isAir }) { "Frozen item reference contains air" }
        }

        private val frozenTemplates = templates.map(ItemStack::clone)

        /** Return detached copies so callers cannot mutate the frozen snapshot. */
        val templates: List<ItemStack>
            get() = frozenTemplates.map(ItemStack::clone)
    }

    /**
     * A provider-backed reward whose redeemable identity is intentionally
     * minted by [ArcItemMaterializer.materialize] for every delivery.
     */
    data class FreshVoucher(
        override val request: ArcItemMaterializationRequest,
        override val providerFingerprint: String,
        val sourceKey: String,
    ) : ArcItemMaterializationReference {
        init {
            require(providerFingerprint.isNotBlank()) { "Voucher provider fingerprint is blank" }
            require(
                sourceKey.isNotBlank() &&
                    sourceKey.length <= MAX_SOURCE_KEY_LENGTH &&
                    !sourceKey.hasControlCharacters(),
            ) {
                "Voucher source key is invalid"
            }
        }
    }

    companion object {
        private const val MAX_STACKS = 64
        private const val MAX_SOURCE_KEY_LENGTH = 256
    }
}
