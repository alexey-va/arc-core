package ru.arc.paper.scene

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.ScoreComponent
import net.kyori.adventure.text.SelectorComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.util.Transformation
import ru.arc.paper.api.ArcSceneEquipment
import ru.arc.paper.api.ArcSceneDisplayPose
import ru.arc.paper.api.ArcSceneItem
import ru.arc.paper.api.ArcSceneQuaternion
import ru.arc.paper.api.ArcSceneTextContent
import ru.arc.paper.api.ArcSceneTextRun
import ru.arc.paper.api.ArcSceneVector3

/** Short native appearance capture; returned values retain no item, metadata or PacketEvents handle. */
object PaperSceneSnapshots {
    /** Main-only rendering allowlist; no arbitrary item metadata or inventory content is retained. */
    @JvmStatic
    fun item(item: ItemStack): ArcSceneItem? {
        check(Bukkit.isPrimaryThread()) { "Scene item capture requires Paper's primary thread" }
        if (item.type.isAir || item.amount <= 0) return null
        val meta = item.itemMeta
        @Suppress("DEPRECATION")
        val model = meta?.takeIf { it.hasCustomModelData() }?.customModelData
        return ArcSceneItem(item.type.key.toString(), item.amount.coerceAtMost(99), model,
            (meta as? LeatherArmorMeta)?.color?.asRGB(), meta?.itemModel?.toString())
    }

    /** Main-only native position/SRT capture; all mutable inputs are detached into scalar DTOs. */
    @JvmStatic
    fun pose(location: Location, transform: Transformation): ArcSceneDisplayPose {
        check(Bukkit.isPrimaryThread()) { "Scene display pose capture requires Paper's primary thread" }
        return ArcSceneDisplayPose(location.x, location.y, location.z, location.yaw, location.pitch,
            transform.translation.let { ArcSceneVector3(it.x, it.y, it.z) },
            transform.scale.let { ArcSceneVector3(it.x, it.y, it.z) },
            transform.leftRotation.let { ArcSceneQuaternion(it.x, it.y, it.z, it.w) },
            transform.rightRotation.let { ArcSceneQuaternion(it.x, it.y, it.z, it.w) })
    }

    /** Main-only. Air is absent; only the six player equipment slots are supported. */
    @JvmStatic
    fun equipment(slot: EquipmentSlot, item: ItemStack): ArcSceneEquipment? {
        check(Bukkit.isPrimaryThread()) { "Scene equipment capture requires Paper's primary thread" }
        require(slot in setOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) { "Unsupported scene equipment slot" }
        if (item.type.isAir || item.amount <= 0) return null
        val captured = item(item) ?: return null
        return ArcSceneEquipment(slot.name, captured.material, captured.amount, captured.customModelData, captured.tint)
    }

    private data class Format(
        val color: Int = 0xffffff, val font: String = "minecraft:default",
        val bold: Boolean = false, val italic: Boolean = false,
        val underlined: Boolean = false, val strikethrough: Boolean = false,
        val obfuscated: Boolean = false,
    ) {
        fun inherit(component: Component): Format {
            val style = component.style()
            fun decoration(type: TextDecoration, inherited: Boolean) = when (style.decoration(type)) {
                TextDecoration.State.TRUE -> true
                TextDecoration.State.FALSE -> false
                TextDecoration.State.NOT_SET -> inherited
            }
            return Format(style.color()?.value() ?: color, style.font()?.asString()?.takeIf { it.length <= 128 } ?: font,
                decoration(TextDecoration.BOLD, bold), decoration(TextDecoration.ITALIC, italic),
                decoration(TextDecoration.UNDERLINED, underlined), decoration(TextDecoration.STRIKETHROUGH, strikethrough),
                decoration(TextDecoration.OBFUSCATED, obfuscated))
        }
        fun run(text: String) = ArcSceneTextRun(text, color, font, bold, italic, underlined, strikethrough, obfuscated)
    }
    private data class Node(val component: Component, val inherited: Format)

    /**
     * Literal, safe text capture shared by public scene owners and native observers. At most
     * 256 codepoints, 512 component nodes and 64 inherited style runs. Over-fragmentation keeps
     * the plain fallback. Click/hover events, translation arguments and external data are ignored.
     */
    @JvmStatic
    fun textContent(component: Component): ArcSceneTextContent {
        val nodes = ArrayDeque<Node>().also { it.addLast(Node(component, Format())) }
        val text = StringBuilder()
        val runs = ArrayList<ArcSceneTextRun>()
        var characters = 0
        var visited = 0
        var previous: Format? = null
        var completeRuns = true
        while (nodes.isNotEmpty() && visited++ < 512 && characters < 256) {
            val (current, inherited) = nodes.removeLast()
            val format = inherited.inherit(current)
            val local = when (current) {
                is TextComponent -> current.content()
                is TranslatableComponent -> current.fallback() ?: current.key()
                is KeybindComponent -> current.keybind()
                is ScoreComponent -> current.value() ?: current.name()
                is SelectorComponent -> current.pattern()
                else -> ""
            }
            val points = local.codePoints().limit((256 - characters).toLong()).toArray()
            if (points.isNotEmpty()) {
                val part = String(points, 0, points.size)
                text.append(part); characters += points.size
                if (completeRuns) {
                    if (previous == format) runs[runs.lastIndex] = format.run(runs.last().text + part)
                    else if (runs.size < 64) { runs += format.run(part); previous = format }
                    else { completeRuns = false; runs.clear() }
                }
            }
            val children = current.children()
            val allowed = minOf(children.size, 512 - visited - nodes.size)
            for (index in allowed - 1 downTo 0) nodes.addLast(Node(children[index], format))
        }
        return ArcSceneTextContent(text.toString(), if (completeRuns) runs else null)
    }
}
