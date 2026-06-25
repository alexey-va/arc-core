package ru.arc.logging

/**
 * Platform-specific console and side-channel hooks.
 *
 * [styledLine] uses MiniMessage tags where supported (Paper); [plainLine] is tag-free text.
 */
interface LoggingPlatform {
    val brandTag: String

    fun writeConsole(
        level: LogLevel,
        styledLine: String,
        plainLine: String,
        throwable: Throwable? = null,
    )

    fun onWarn(plainMessage: String) {}

    fun onError(
        plainMessage: String,
        throwable: Throwable?,
    ) {}
}
