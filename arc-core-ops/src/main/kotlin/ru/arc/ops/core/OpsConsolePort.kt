package ru.arc.ops.core

data class OpsResult(
    val ok: Boolean,
    val message: String = "",
    val output: String = "",
) {
    companion object {
        fun success(output: String = ""): OpsResult = OpsResult(ok = true, output = output)

        fun failure(message: String): OpsResult = OpsResult(ok = false, message = message)
    }
}

/** Executes a server console command on the platform main/sync thread. */
fun interface OpsConsolePort {
    fun execute(command: String): OpsResult
}
