package ru.arc.core.scheduling

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.EmptyConfig
import ru.arc.core.TickConstants
import java.nio.file.Path

/**
 * Typed accessors for [SchedulingModuleConfig.RESOURCE] — shared by Paper (ARC) and Velocity (ProxyARC).
 */
open class SchedulingModuleConfig(
    private val config: Config,
) {
    open val tickMs: Int
        get() = config.integer("tick-ms", TickConstants.TICK_MS.toInt())

    open val subtickEnabled: Boolean
        get() = config.bool("subtick.enabled", true)

    open val subtickMinDelayMs: Int
        get() = config.integer("subtick.min-delay-ms", 1).coerceAtLeast(0)

    companion object {
        const val RESOURCE = "scheduling.yml"

        @JvmStatic
        fun load(dataPath: Path): SchedulingModuleConfig =
            SchedulingModuleConfig(ConfigManager.ofModule(dataPath, RESOURCE))

        @JvmStatic
        fun bundledResourcePath(): String = ConfigManager.bundledModuleResource(RESOURCE)
    }
}

/** Explicit test values — no YAML file. */
class TestSchedulingModuleConfig(
    override val tickMs: Int = TickConstants.TICK_MS.toInt(),
    override val subtickEnabled: Boolean = true,
    override val subtickMinDelayMs: Int = 1,
) : SchedulingModuleConfig(EmptyConfig)
