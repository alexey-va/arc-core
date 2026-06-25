package ru.arc.ai.config

import java.nio.file.Files
import java.nio.file.Path

/** Read plain-text LLM prompts from the plugin data folder (no YAML escaping). */
object PromptFiles {
    fun readText(
        dataRoot: Path,
        relativePath: String,
    ): String? {
        val path = dataRoot.resolve(relativePath)
        if (!Files.isRegularFile(path)) return null
        return Files.readString(path).trim().takeIf { it.isNotEmpty() }
    }
}
