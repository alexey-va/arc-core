package ru.arc.ops.core

class OpsCapabilityRegistry {
    private val capabilities = linkedSetOf<String>()

    fun register(capability: String) {
        if (capability.isNotBlank()) {
            capabilities.add(capability)
        }
    }

    fun all(): List<String> = capabilities.sorted()
}
