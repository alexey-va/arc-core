package ru.arc.config

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound

fun ConfigSection.materialOrNull(subPath: String) = config.materialOrNull(path(subPath))

fun ConfigSection.material(subPath: String, default: Material) = config.material(path(subPath), default)

fun ConfigSection.particle(subPath: String, default: Particle) = config.particle(path(subPath), default)

fun ConfigSection.soundOrNull(subPath: String) = config.soundOrNull(path(subPath))

fun ConfigSection.sound(subPath: String, default: Sound) = config.sound(path(subPath), default)

fun ConfigSection.materialSet(subPath: String, default: Set<Material> = emptySet()) =
    config.materialSet(path(subPath), default)

fun ConfigSection.materials(subPath: String, default: Set<Material> = emptySet()) =
    config.materials(path(subPath), default)
