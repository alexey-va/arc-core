package ru.arc.core

/** Wall-clock abstraction for testable time-dependent logic. */
interface TimeProvider {
    fun currentTimeMillis(): Long

    fun nanoTime(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun nanoTime(): Long = System.nanoTime()
}

class TestTimeProvider(
    private var millis: Long = 0L,
) : TimeProvider {
    override fun currentTimeMillis(): Long = millis

    override fun nanoTime(): Long = millis * 1_000_000L

    fun advance(millis: Long) {
        this.millis += millis
    }
}

object Time {
    @Volatile
    var provider: TimeProvider = SystemTimeProvider()

    fun now(): Long = provider.currentTimeMillis()

    inline fun <T> withProvider(testProvider: TimeProvider, block: () -> T): T {
        val previous = provider
        provider = testProvider
        return try {
            block()
        } finally {
            provider = previous
        }
    }

    fun reset() {
        provider = SystemTimeProvider()
    }
}
