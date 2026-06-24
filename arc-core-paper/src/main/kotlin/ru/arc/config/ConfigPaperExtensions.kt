package ru.arc.config

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import ru.arc.util.Logging.warn

fun Config.materialOrNull(path: String): Material? {
    val name = stringOrNull(path) ?: return null
    return runCatching { Material.valueOf(name.uppercase()) }.getOrNull()
}

fun Config.material(path: String, default: Material): Material = materialOrNull(path) ?: default

fun Config.particleOrNull(path: String): Particle? {
    val name = stringOrNull(path) ?: return null
    return runCatching { Particle.valueOf(name.uppercase()) }.getOrNull()
}

fun Config.particle(path: String, default: Particle): Particle = particleOrNull(path) ?: default

fun Config.soundOrNull(path: String): Sound? {
    val name = stringOrNull(path) ?: return null
    val key =
        NamespacedKey.fromString(name.lowercase())
            ?: NamespacedKey.minecraft(name.lowercase().replace("_", "."))
    return Registry.SOUNDS.get(key)
}

fun Config.sound(path: String, default: Sound): Sound = soundOrNull(path) ?: default

fun Config.materialSet(path: String, default: Set<Material> = emptySet()): Set<Material> {
    val list = stringListOrNull(path) ?: return default
    return list
        .mapNotNull { name ->
            runCatching { Material.valueOf(name.uppercase()) }
                .onFailure { warn("Could not parse material: {}", name) }
                .getOrNull()
        }.toSet()
        .ifEmpty { default }
}

fun Config.materials(path: String, default: Set<Material> = emptySet()): Set<Material> =
    materialSet(path, default)
