package ru.arc.core

import ru.arc.util.TextUtils

private const val SEP =
    "<dark_gray>┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄</dark_gray>"

/**
 * MiniMessage-formatted module lifecycle console output (Paper consoleLog, Velocity logger, etc.).
 */
class PrettyModuleLifecycleReporter(
    private val consoleLog: (String) -> Unit,
    private val logError: (String, Throwable) -> Unit,
) : ModuleLifecycleReporter {
    override fun onInitStart(moduleCount: Int) {
        consoleLog(SEP)
        consoleLog("  <bold><white>Initializing <aqua>$moduleCount</aqua> modules</white></bold>")
        consoleLog(SEP)
    }

    override fun onInitModuleSuccess(name: String, nameWidth: Int, ms: Long) {
        val padded = TextUtils.escapeMM(name.padEnd(nameWidth))
        val label = "<aqua>$padded</aqua>"
        when {
            ms >= 200 -> consoleLog("  <yellow>✔  $label  ${ms}ms  ⚠</yellow>")
            ms >= 50 -> consoleLog("  <green>✔</green>  $label  <yellow>${ms}ms</yellow>")
            else -> consoleLog("  <green>✔</green>  $label  <dark_gray>${ms}ms</dark_gray>")
        }
    }

    override fun onInitModuleFailure(name: String, nameWidth: Int, ms: Long, error: Exception) {
        val padded = TextUtils.escapeMM(name.padEnd(nameWidth))
        val label = "<aqua>$padded</aqua>"
        val msg = TextUtils.escapeMM(error.message ?: error::class.simpleName ?: "error")
        consoleLog("  <red>✗  $label  $msg</red>")
        logError("Module '$name' failed to initialize", error)
    }

    override fun onInitComplete(ok: Int, failed: Int, totalMs: Long) {
        consoleLog(SEP)
        if (failed == 0) {
            consoleLog("  <bold><green>✔  All $ok modules ready</green></bold>  <dark_gray>(${totalMs}ms total)</dark_gray>")
        } else {
            consoleLog("  <bold><green>✔  $ok ok</green>  <red>$failed failed</red></bold>  <dark_gray>(${totalMs}ms)</dark_gray>")
        }
        consoleLog(SEP)
    }

    override fun onReloadStart(moduleCount: Int) {
        consoleLog("<aqua>↻  Reloading $moduleCount modules...</aqua>")
    }

    override fun onReloadSuccess(name: String) {
        consoleLog("  <green>↻  ${TextUtils.escapeMM(name)}</green>")
    }

    override fun onReloadFailure(name: String, error: Exception) {
        logError("  ✗  $name", error)
    }

    override fun onReloadComplete() {
        consoleLog("<green>↻  Reload complete</green>")
    }

    override fun onShutdownStart(moduleCount: Int) {
        consoleLog("<dark_gray>  Shutting down $moduleCount modules...</dark_gray>")
    }

    override fun onShutdownSuccess(name: String) {
        consoleLog("  <dark_gray>✕  ${TextUtils.escapeMM(name)}</dark_gray>")
    }

    override fun onShutdownFailure(name: String, error: Exception) {
        logError("Failed to shutdown module '$name'", error)
    }

    override fun onShutdownComplete() {
        consoleLog("<dark_gray>  Shutdown complete</dark_gray>")
    }
}
