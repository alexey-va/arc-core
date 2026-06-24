package ru.arc.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import ru.arc.util.TextUtils
import ru.arc.util.TextUtils.mm
import java.io.FileInputStream
import java.io.FileWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Suppress("UNCHECKED_CAST")
class Config internal constructor(
    val folder: Path,
    private val filePath: String,
) {
    var map: MutableMap<String, Any?> = hashMapOf()

    init {
        copyDefaultConfig(filePath, folder, false)
        load()
    }

    fun integer(path: String, def: Int): Int {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        return (o as Number).toInt()
    }

    fun bool(path: String, def: Boolean): Boolean {
        val o = getValueForKeyPath(path)
        val def0 = def
        if (o == null) {
            injectDeepKey(path, def0)
            return def0
        }
        return o as Boolean
    }

    fun real(path: String, def: Double): Double {
        val o = getValueForKeyPath(path)
        val def0 = def
        if (o == null) {
            injectDeepKey(path, def)
            return def0
        }
        return (o as Number).toDouble()
    }

    fun string(path: String, def: String): String {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        return try {
            o as String
        } catch (_: Exception) {
            o.toString()
        }
    }

    fun component(path: String, vararg replacement: String): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, path)
            return mm(path, true)
        }
        var s = if (o is String) o else o.toString()

        var i = 0
        while (i < replacement.size) {
            if (i + 1 >= replacement.size) break
            s = s.replace(replacement[i], replacement[i + 1])
            i += 2
        }

        return mm(s, true)
    }

    fun componentDef(path: String, def: String, tagResolver: TagResolver): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return TextUtils.strip(mm(def, tagResolver))!!
        }
        val s = if (o is String) o else o.toString()
        return TextUtils.strip(mm(s, tagResolver))!!
    }

    fun componentDef(path: String, def: String, vararg replacers: String): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return mm(def, true, *replacers)
        }
        val s = if (o is String) o else o.toString()
        return mm(s, true, *replacers)
    }

    fun component(path: String, tagResolver: TagResolver): Component {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, path)
            return TextUtils.strip(mm(path))!!
        }
        val s = if (o is String) o else o.toString()
        return TextUtils.strip(mm(s, tagResolver))!!
    }

    fun componentList(path: String, vararg replacement: String): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, listOf(path))
            return listOf(TextUtils.strip(mm(path))!!)
        }

        val result = mutableListOf<Component>()
        if (o is String) {
            result.add(mm(applyReplacements(o, replacement), true))
        } else if (o is List<*>) {
            for (obj in o) {
                if (obj is String) {
                    result.add(mm(applyReplacements(obj, replacement), true))
                }
            }
        }
        return result
    }

    fun componentListDef(path: String, def: List<String>, vararg replacement: String): List<Component> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return componentList(path, *replacement)
        }

        val result = mutableListOf<Component>()
        if (o is String) {
            result.add(mm(applyReplacements(o, replacement), true))
        } else if (o is List<*>) {
            for (obj in o) {
                if (obj is String) {
                    result.add(mm(applyReplacements(obj, replacement), true))
                }
            }
        }
        return result
    }

    private fun applyReplacements(text: String, replacement: Array<out String>): String {
        var result = text
        var i = 0
        while (i < replacement.size) {
            if (i + 1 >= replacement.size) break
            result = result.replace(replacement[i], replacement[i + 1])
            i += 2
        }
        return result
    }

    fun stringList(path: String): List<String> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, ArrayList<String>())
            return ArrayList()
        }
        if (o is String) {
            return listOf(o)
        }
        return o as List<String>
    }

    fun stringList(path: String, def: List<String>): List<String> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        if (o is String) {
            return listOf(o)
        }
        return o as List<String>
    }

    fun <T> map(path: String): Map<String, T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, LinkedHashMap<String, T>())
            return LinkedHashMap()
        }
        val rawMap = o as MutableMap<Any?, Any?>
        for (key in rawMap.keys.toList()) {
            if (key is String) continue
            val stringKey = key.toString()
            val value = rawMap[key]
            rawMap.remove(key)
            rawMap[stringKey] = value
            injectDeepKey(path, o)
        }
        return o as Map<String, T>
    }

    fun <T> map(path: String, def: Map<String, T>): Map<String, T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        val rawMap = o as MutableMap<Any?, Any?>
        for (key in rawMap.keys.toList()) {
            if (key is String) continue
            val stringKey = key.toString()
            val value = rawMap[key]
            rawMap.remove(key)
            rawMap[stringKey] = value
            injectDeepKey(path, o)
        }
        return o as Map<String, T>
    }

    fun <T> list(path: String): List<T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, ArrayList<Any>())
            return ArrayList()
        }
        return o as List<T>
    }

    fun <T> list(path: String, def: List<T>): List<T> {
        val o = getValueForKeyPath(path)
        if (o == null) {
            injectDeepKey(path, def)
            return def
        }
        return o as List<T>
    }

    fun keys(path: String): List<String> {
        val o = map<String>(path, emptyMap())
        return ArrayList(o.keys)
    }

    private fun getValueForKeyPath(keyPath: String): Any? {
        val keyParts = keyPath.split("\\.".toRegex())
        var currentLevel = map
        for (i in keyParts.indices) {
            val keyPart = keyParts[i]
            if (currentLevel.containsKey(keyPart)) {
                if (i == keyParts.size - 1) return currentLevel[keyPart]
                val next = currentLevel[keyPart]
                if (next is Map<*, *>) {
                    currentLevel = next as MutableMap<String, Any?>
                }
            } else {
                println("Key not found: $keyPath")
                return null
            }
        }
        return currentLevel[keyParts[keyParts.size - 1]]
    }

    fun injectDeepKey(keyPath: String, value: Any?) {
        try {
            println("Injecting key: $keyPath with value: $value")
            load()
            val keyParts = keyPath.split("\\.".toRegex())
            var currentLevel = map
            for (i in 0 until keyParts.size - 1) {
                currentLevel.putIfAbsent(keyParts[i], LinkedHashMap<String, Any?>())
                currentLevel = currentLevel[keyParts[i]] as MutableMap<String, Any?>
            }
            currentLevel[keyParts[keyParts.size - 1]] = value
            save()
        } catch (_: Exception) {
            log.error("Could not inject key: {}", keyPath)
        }
    }

    fun load() {
        val yaml = Yaml()
        val configFile = folder.resolve(filePath).toFile()
        map = yaml.load(FileInputStream(configFile)) ?: hashMapOf()
        println("Loaded config: $filePath")
    }

    fun save() {
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        val yaml = Yaml(options)
        val file = folder.resolve(filePath).toFile().path
        try {
            FileWriter(file).use { writer ->
                yaml.dump(map, writer)
            }
        } catch (_: Exception) {
            log.error("Could not save config: {}", file)
        }
    }

    fun string(s: String): String = string(s, s)

    fun addToList(path: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        val existing = list<Any?>(path) as MutableList<Any?>
        existing.add(value)
        injectDeepKey(path, existing)
    }

    fun exists(s: String): Boolean = getValueForKeyPath(s) != null

    fun longValue(s: String, def: Long): Long {
        val o = getValueForKeyPath(s)
        val def0 = def
        if (o == null) {
            injectDeepKey(s, def0)
            return def0
        }
        return (o as Number).toLong()
    }

    companion object {
        private val log = LoggerFactory.getLogger(Config::class.java)

        @JvmStatic
        fun copyDefaultConfig(resource: String, folder: Path, replace: Boolean) {
            try {
                Config::class.java.classLoader.getResourceAsStream(resource).use { stream ->
                    val path = folder.resolve(resource)
                    if (Files.exists(path)) {
                        if (!replace) {
                            return
                        }
                    }
                    Files.createDirectories(path.parent)
                    if (stream == null) {
                        Files.createFile(path)
                    } else {
                        Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            } catch (_: Exception) {
                log.error("Could not copy default config: {}", resource)
            }
        }
    }
}
