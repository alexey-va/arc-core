package ru.arc.testing

/** Deterministic named failure points for crash/retry tests. */
class FailureInjector {
    private data class Rule(
        var remaining: Int,
        val failure: () -> Throwable,
    )

    private val monitor = Any()
    private val rules = mutableMapOf<String, Rule>()

    fun failNext(
        point: String,
        times: Int = 1,
        failure: () -> Throwable = { IllegalStateException("Injected failure at $point") },
    ) = synchronized(monitor) {
        validatePoint(point)
        require(times in 1..1_000_000) { "Failure injection count must be between 1 and 1000000" }
        rules[point] = Rule(times, failure)
    }

    /** Throws only while the configured count for [point] remains positive. */
    fun check(point: String) {
        val failure = synchronized(monitor) {
            validatePoint(point)
            val rule = rules[point] ?: return
            rule.remaining--
            if (rule.remaining == 0) rules.remove(point)
            rule.failure
        }
        throw failure()
    }

    fun remaining(point: String): Int = synchronized(monitor) {
        validatePoint(point)
        rules[point]?.remaining ?: 0
    }

    fun clear() = synchronized(monitor) { rules.clear() }

    private fun validatePoint(point: String) {
        require(point.matches(POINT)) { "Failure point must be a stable token" }
    }

    private companion object {
        val POINT = Regex("[a-z][a-z0-9_.-]{0,63}")
    }
}
