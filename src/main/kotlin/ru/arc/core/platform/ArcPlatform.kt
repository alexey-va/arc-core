package ru.arc.core.platform

import net.kyori.adventure.text.Component
import java.util.UUID

/** Minimal platform surface for cross-server / messaging features. */
interface ArcPlatform {
    fun sendMessageToAll(component: Component)

    fun onlinePlayerNames(): Collection<String> = emptyList()

    fun getPlayerName(uuid: UUID): String? = null
}
