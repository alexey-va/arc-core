package ru.arc.network

/**
 * Validation policy for a Minecraft username observed at a network boundary.
 *
 * Java usernames use the vanilla ASCII body. Bedrock usernames may carry one
 * explicitly configured Floodgate prefix; prefixes are never guessed from the
 * incoming value.
 */
data class NetworkPlayerNamePolicy(
    val bedrockPrefixes: Set<String> = setOf("."),
    val maxBodyLength: Int = 16,
) {
    init {
        require(maxBodyLength in 1..32) { "Player-name body limit must be between 1 and 32" }
        require(bedrockPrefixes.size <= 8) { "At most eight Bedrock username prefixes may be configured" }
        bedrockPrefixes.forEach { prefix ->
            require(prefix.length in 1..4) { "A Bedrock username prefix must contain 1 to 4 characters" }
            require(prefix.all { it.code in 33..126 && !it.isLetterOrDigit() && it != '_' }) {
                "A Bedrock username prefix must contain visible ASCII punctuation only"
            }
        }
    }

    fun accepts(value: String): Boolean {
        if (value.length in 1..maxBodyLength && value.all(::isUsernameBodyCharacter)) return true
        return bedrockPrefixes.any { prefix ->
            value.startsWith(prefix) &&
                value.length in (prefix.length + 1)..(prefix.length + maxBodyLength) &&
                value.substring(prefix.length).all(::isUsernameBodyCharacter)
        }
    }

    private fun isUsernameBodyCharacter(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '_'
}

/** A player name normalized and validated at a cross-server boundary. */
@JvmInline
value class NetworkPlayerName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmField
        val DEFAULT_POLICY = NetworkPlayerNamePolicy()

        @JvmStatic
        fun of(value: String, policy: NetworkPlayerNamePolicy = DEFAULT_POLICY): NetworkPlayerName {
            require(policy.accepts(value)) { "Unsafe network player name" }
            return NetworkPlayerName(value)
        }

        @JvmStatic
        fun parseOrNull(value: String?, policy: NetworkPlayerNamePolicy = DEFAULT_POLICY): NetworkPlayerName? =
            value?.takeIf(policy::accepts)?.let(::NetworkPlayerName)
    }
}

/**
 * Policy for a proxy/backend server identifier.
 *
 * The default matches RusCrafting's lowercase backend ids. A plugin that must
 * consume a wider external namespace has to opt into every extra character
 * class explicitly instead of weakening the network-wide default.
 */
data class BackendServerIdPolicy(
    val maxLength: Int = 32,
    val allowUppercase: Boolean = false,
    val allowDot: Boolean = false,
) {
    init {
        require(maxLength in 1..64) { "Backend server-id limit must be between 1 and 64" }
    }

    fun accepts(value: String): Boolean =
        value.length in 1..maxLength && value.all { character ->
            character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-' ||
                (allowUppercase && character in 'A'..'Z') ||
                (allowDot && character == '.')
        }
}

/** A validated proxy/backend server id suitable for routing and wire payloads. */
@JvmInline
value class BackendServerId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        @JvmField
        val DEFAULT_POLICY = BackendServerIdPolicy()

        @JvmStatic
        fun of(value: String, policy: BackendServerIdPolicy = DEFAULT_POLICY): BackendServerId {
            require(policy.accepts(value)) { "Unsafe backend server id" }
            return BackendServerId(value)
        }

        @JvmStatic
        fun parseOrNull(value: String?, policy: BackendServerIdPolicy = DEFAULT_POLICY): BackendServerId? =
            value?.takeIf(policy::accepts)?.let(::BackendServerId)
    }
}
