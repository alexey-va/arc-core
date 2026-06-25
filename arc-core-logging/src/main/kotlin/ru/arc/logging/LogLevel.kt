package ru.arc.logging

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    internal fun toLog4j(): org.apache.logging.log4j.Level =
        when (this) {
            DEBUG -> org.apache.logging.log4j.Level.DEBUG
            INFO -> org.apache.logging.log4j.Level.INFO
            WARN -> org.apache.logging.log4j.Level.WARN
            ERROR -> org.apache.logging.log4j.Level.ERROR
        }
}
