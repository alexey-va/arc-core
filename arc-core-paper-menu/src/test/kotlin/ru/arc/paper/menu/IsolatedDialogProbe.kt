package ru.arc.paper.menu

import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.connection.PlayerGameConnection
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/** Loaded with each plugin's copy of core. Its public bridge uses only platform/JDK types. */
class IsolatedDialogProbe(private val plugin: Plugin, private val player: Player, output: Consumer<String>) {
    private lateinit var registration: PaperDialogSessionRegistration
    private val runtime = PaperDialogRuntime(plugin) { _, screen, registered ->
        registration = registered
        output.accept(screen.id)
    }

    fun open(id: String, action: Runnable?, reopen: Runnable?, dismiss: Runnable?, closeOnEscape: Boolean) {
        runtime.open(player, PaperDialogScreen(Component.text(id), id = id,
            buttons = listOf(PaperDialogButton(PaperDialogActionId.of("next"), Component.text("Next")) { action?.run() }),
            exitButton = PaperDialogButton(PaperDialogActionId.of("back"), Component.text("Back")) {
                error("Legacy semantic parent must not run")
            }), reopen?.let { { it.run() } }, { dismiss?.run() }, closeOnEscape)
    }

    fun beginFlow() = runtime.beginFlow(player)
    fun closePlayer() = runtime.close(player)
    fun shutdown() = runtime.close()
    fun quit() = runtime.onQuit(mockk { every { this@mockk.player } returns this@IsolatedDialogProbe.player })
    fun key(id: String): String = registration.key(PaperDialogActionId.of(id))
    fun click(id: String) = clickKey(key(id))
    fun clickKey(key: String) {
        val connection = mockk<PlayerGameConnection> { every { this@mockk.player } returns this@IsolatedDialogProbe.player }
        runtime.onCustomClick(mockk {
            every { commonConnection } returns connection
            every { identifier } returns Key.key(key)
            every { dialogResponseView } returns null
        })
    }
}
