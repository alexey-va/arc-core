package ru.arc.player

import java.util.UUID

/**
 * Platform-agnostic player lookup — online status, names, permissions.
 */
interface PlayerLookup {
    fun findUuid(name: String): UUID?
    fun findName(uuid: UUID): String?
    fun isOnline(uuid: UUID): Boolean
    fun onlineNames(): Collection<String>
    fun hasPermission(uuid: UUID, permission: String): Boolean
}
