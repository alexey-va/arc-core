package ru.arc.paper.scene

import org.bukkit.Bukkit
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.arc.paper.api.ArcSceneEquipment

/** Short native appearance capture; returned values retain no item, metadata or PacketEvents handle. */
object PaperSceneSnapshots {
    /** Main-only. Air is absent; only the six player equipment slots are supported. */
    @JvmStatic
    fun equipment(slot: EquipmentSlot, item: ItemStack): ArcSceneEquipment? {
        check(Bukkit.isPrimaryThread()) { "Scene equipment capture requires Paper's primary thread" }
        require(slot in setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) { "Unsupported scene equipment slot" }
        if (item.type.isAir || item.amount <= 0) return null
        val meta = item.itemMeta
        @Suppress("DEPRECATION")
        val model = meta?.takeIf { it.hasCustomModelData() }?.customModelData
        val tint = (meta as? LeatherArmorMeta)?.color?.asRGB()
        return ArcSceneEquipment(slot.name, item.type.key.toString(), item.amount.coerceAtMost(99), model, tint)
    }
}
