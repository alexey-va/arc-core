package ru.arc.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

object ConfigManager {
    /** Subdirectory of the plugin data folder where feature YAML files live (matches `/modules/` in the JAR). */
    const val MODULE_YAML_DIR = "modules"

    private var version = 0
    private val configMap = ConcurrentHashMap<String, Config>()

    @JvmStatic
    fun get(name: String): Config? = configMap[name]

    @JvmStatic
    fun of(
        folder: Path,
        filename: String,
    ): Config {
        val name = "$folder/$filename"
        return configMap.getOrPut(name) { create(folder, filename, name) }
    }

    /**
     * Path relative to [dataRoot] for a feature YAML file: `modules/name.yml` when that file exists
     * or when neither exists (new install), otherwise legacy `name.yml` at the data root.
     */
    @JvmStatic
    fun moduleYamlRelative(
        dataRoot: Path,
        fileName: String,
    ): String {
        require(fileName.endsWith(".yml") || fileName.endsWith(".yaml")) {
            "Expected *.yml / *.yaml file name, got: $fileName"
        }
        val modular = dataRoot.resolve(MODULE_YAML_DIR).resolve(fileName)
        val legacy = dataRoot.resolve(fileName)
        return try {
            when {
                Files.exists(modular) -> "$MODULE_YAML_DIR/$fileName"
                Files.exists(legacy) -> fileName
                else -> "$MODULE_YAML_DIR/$fileName"
            }
        } catch (_: Exception) {
            "$MODULE_YAML_DIR/$fileName"
        }
    }

    @JvmStatic
    fun moduleYamlPath(
        dataRoot: Path,
        fileName: String,
    ): Path = dataRoot.resolve(moduleYamlRelative(dataRoot, fileName))

    /** Classpath resource path for [org.bukkit.plugin.java.JavaPlugin.saveResource]. */
    @JvmStatic
    fun bundledModuleResource(fileName: String): String = "$MODULE_YAML_DIR/$fileName"

    /** Cached [Config] for a file resolved via [moduleYamlRelative]. */
    @JvmStatic
    fun ofModule(
        dataRoot: Path,
        fileName: String,
    ): Config = of(dataRoot, moduleYamlRelative(dataRoot, fileName))

    @JvmStatic
    fun create(
        folder: Path,
        fileName: String,
        configName: String,
    ): Config = Config(folder, fileName).also { configMap[configName] = it }

    @JvmStatic
    fun reloadAll() {
        version++
        configMap.values.forEach { it.load() }
    }

    @JvmStatic
    fun getVersion(): Int = version

    /** Clears all cached configs. Used in tests to ensure clean state. */
    @JvmStatic
    fun clear() {
        configMap.clear()
        version = 0
    }

    /** Returns an empty config that returns defaults for all reads. Used in tests. */
    @JvmStatic
    fun empty(): Config = EmptyConfig
}
