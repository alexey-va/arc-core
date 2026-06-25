package ru.arc.core

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

/** Plugin data folder as [Path] (e.g. `plugins/ARC`). */
val JavaPlugin.dataPath: Path
    get() = dataFolder.toPath()
