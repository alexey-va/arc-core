package ru.arc.config

import java.nio.file.Paths

/**
 * Empty config that does nothing on save/reload.
 * All reads return defaults; nothing is written to disk.
 */
object EmptyConfig : Config(Paths.get(System.getProperty("java.io.tmpdir")), "empty.yml") {
    override fun reload() { // no-op
    }

    override fun save() { // no-op
    }
}
