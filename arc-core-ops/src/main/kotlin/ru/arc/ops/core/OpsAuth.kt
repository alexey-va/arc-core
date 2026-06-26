package ru.arc.ops.core

import java.security.MessageDigest

object OpsAuth {
    fun extractToken(headers: Map<String, String>): String? {
        val auth = headers["Authorization"] ?: headers["authorization"]
        if (auth != null) {
            val prefix = "Bearer "
            if (auth.startsWith(prefix, ignoreCase = true)) {
                return auth.substring(prefix.length).trim()
            }
        }
        return headers["X-ARC-Ops-Token"] ?: headers["x-arc-ops-token"]
    }

    fun isAuthorized(headers: Map<String, String>, expected: String): Boolean {
        if (expected.isBlank()) return false
        val provided = extractToken(headers) ?: return false
        return constantTimeEquals(provided, expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val da = digest.digest(a.toByteArray())
        val db = digest.digest(b.toByteArray())
        if (da.size != db.size) return false
        var result = 0
        for (i in da.indices) {
            result = result or (da[i].toInt() xor db[i].toInt())
        }
        return result == 0
    }
}
