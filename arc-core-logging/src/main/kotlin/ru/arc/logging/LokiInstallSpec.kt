package ru.arc.logging

import java.nio.file.Path

data class LokiInstallSpec(
    val dataFolder: Path,
    val target: LokiAttachTarget = LokiAttachTarget.LOGGER_PREFIX,
    val loggerPrefix: String = LokiLogging.DEFAULT_LOGGER_PREFIX,
    val appenderName: String = "ArcLokiAppender",
)
