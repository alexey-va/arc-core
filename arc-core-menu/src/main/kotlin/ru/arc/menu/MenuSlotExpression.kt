package ru.arc.menu

object MenuSlotExpression {
    fun parse(values: List<String>): Result<List<MenuSlot>> = runCatching {
        require(values.isNotEmpty()) { "Menu slot expression must not be empty" }
        val indexes = buildList {
            values.forEach { value ->
                value.split(',').forEach { rawToken ->
                    val token = rawToken.trim()
                    require(token.isNotEmpty()) { "Menu slot expression contains an empty token" }
                    val rangeParts = token.split('-')
                    when (rangeParts.size) {
                        1 -> add(parseIndex(rangeParts.single()))
                        2 -> {
                            val start = parseIndex(rangeParts[0])
                            val end = parseIndex(rangeParts[1])
                            require(start <= end) { "Menu slot range must be ascending: $token" }
                            addAll(start..end)
                        }
                        else -> error("Malformed menu slot token: $token")
                    }
                }
            }
        }
        require(indexes.distinct().size == indexes.size) { "Menu slot expression contains duplicate slots" }
        indexes.map(MenuSlot::of)
    }

    private fun parseIndex(value: String): Int {
        require(value.matches(Regex("[0-9]+"))) { "Menu slot must be a non-negative integer: '$value'" }
        return value.toIntOrNull() ?: error("Menu slot is too large: '$value'")
    }
}
