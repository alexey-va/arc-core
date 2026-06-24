package ru.arc.config

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object ConfigManager {
    private val configMap = ConcurrentHashMap<String, Config>()

    @JvmStatic
    fun get(name: String): Config? = configMap[name]

    private fun create(folder: Path, fileName: String, configName: String): Config {
        val config = Config(folder, fileName)
        configMap[configName] = config
        return config
    }

    @JvmStatic
    fun of(folder: Path, filename: String): Config {
        val name = "$folder/$filename"
        return getOrCreate(folder, filename, name)
    }

    private fun getOrCreate(folder: Path, fileName: String, configName: String): Config {
        var config = configMap[configName]
        if (config == null) {
            config = create(folder, fileName, configName)
            configMap[configName] = config
        }
        return config
    }

    @JvmStatic
    fun reloadAll() {
        configMap.forEach { (_, config) -> config.load() }
    }
}
