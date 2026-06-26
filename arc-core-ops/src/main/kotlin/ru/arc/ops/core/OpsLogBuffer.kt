package ru.arc.ops.core

import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class OpsLogEntry(
    val timestamp: Long,
    val level: String,
    val message: String,
) {
    fun toMap(): Map<String, Any> =
        mapOf(
            "timestamp" to timestamp,
            "iso" to Instant.ofEpochMilli(timestamp).toString(),
            "level" to level,
            "message" to message,
        )
}

/** Ring buffer for GET /ops/errors. */
object OpsLogBuffer {
    private val lock = ReentrantReadWriteLock()
    private var capacity = 200
    private val entries = ArrayDeque<OpsLogEntry>()

    fun resize(newCapacity: Int) {
        lock.write {
            capacity = newCapacity.coerceIn(50, 2000)
            while (entries.size > capacity) {
                entries.removeFirst()
            }
        }
    }

    internal fun clear() {
        lock.write { entries.clear() }
    }

    fun append(level: String, message: String) {
        lock.write {
            entries.addLast(OpsLogEntry(System.currentTimeMillis(), level, message))
            while (entries.size > capacity) {
                entries.removeFirst()
            }
        }
    }

    fun recent(limit: Int): List<OpsLogEntry> =
        lock.read {
            entries.takeLast(limit.coerceIn(1, capacity))
        }
}
