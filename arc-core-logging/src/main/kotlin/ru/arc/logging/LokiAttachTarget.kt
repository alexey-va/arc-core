package ru.arc.logging

/** Where the Loki appender is attached in Log4j2. */
enum class LokiAttachTarget {
    /** Paper: dedicated logger prefix (default `ru.arc`), non-additive. */
    LOGGER_PREFIX,

    /** Velocity / root: attach to Log4j root logger. */
    ROOT,
}
