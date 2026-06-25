package ru.arc.logging

import ru.arc.config.Config

/** Supplies `logging.yml` and reload version (typically [ru.arc.config.ConfigManager.getVersion]). */
fun interface LoggingConfigSource {
    fun config(): Config

    fun configVersion(): Int = 0
}
