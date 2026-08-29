package ru.arc.paper.testing

import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.MethodDelegation
import net.bytebuddy.implementation.bind.annotation.Argument
import net.bytebuddy.implementation.bind.annotation.This
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.isDeclaredBy
import net.bytebuddy.matcher.ElementMatchers.returns
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.entity.EntityMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.inventory.ItemFactoryMock
import org.mockbukkit.mockbukkit.inventory.ItemStackMock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.UnaryOperator

/** Test-JVM compatibility for Paper operations that MockBukkit 4.116.3 declares but aborts. */
internal object MockBukkitCompatibility {
    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        verifyPatchTarget(PlayerMock::class.java, "saveData", Void.TYPE)
        verifyPatchTarget(ItemStackMock::class.java, "effectiveName", Component::class.java)
        verifyPatchTarget(
            ItemFactoryMock::class.java,
            "asHoverEvent",
            HoverEvent::class.java,
            ItemStack::class.java,
            UnaryOperator::class.java,
        )
        verifyPatchTarget(BlockMock::class.java, "isPassable", Boolean::class.javaPrimitiveType!!)
        verifyPatchTarget(
            EntityMock::class.java,
            "teleportAsync",
            CompletableFuture::class.java,
            Location::class.java,
            PlayerTeleportEvent.TeleportCause::class.java,
            java.lang.reflect.Array.newInstance(TeleportFlag::class.java, 0).javaClass,
        )
        val instrumentation = ByteBuddyAgent.install()
        AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(named<TypeDescription>(PlayerMock::class.java.name))
            .transform(patch(PlayerMock::class.java, "saveData", Void.TYPE))
            .type(named<TypeDescription>(ItemStackMock::class.java.name))
            .transform(patch(ItemStackMock::class.java, "effectiveName", Component::class.java))
            .type(named<TypeDescription>(ItemFactoryMock::class.java.name))
            .transform(
                patch(
                    ItemFactoryMock::class.java,
                    "asHoverEvent",
                    HoverEvent::class.java,
                    ItemStack::class.java,
                    UnaryOperator::class.java,
                ),
            )
            .type(named<TypeDescription>(BlockMock::class.java.name))
            .transform(patch(BlockMock::class.java, "isPassable", Boolean::class.javaPrimitiveType!!))
            .type(named<TypeDescription>(EntityMock::class.java.name))
            .transform(
                patch(
                    EntityMock::class.java,
                    "teleportAsync",
                    CompletableFuture::class.java,
                    Location::class.java,
                    PlayerTeleportEvent.TeleportCause::class.java,
                    java.lang.reflect.Array.newInstance(TeleportFlag::class.java, 0).javaClass,
                ),
            )
            .installOn(instrumentation)
        installed = true
    }

    fun resetObservations() = MockBukkitObservations.reset()

    private fun patch(
        owner: Class<*>,
        methodName: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ): AgentBuilder.Transformer = AgentBuilder.Transformer { builder, _, _, _, _ ->
        builder.method(exactMethodMatcher(owner, methodName, returnType, *parameterTypes))
            .intercept(MethodDelegation.to(MockBukkitPatchBodies::class.java))
    }

    internal fun exactMethodMatcher(
        owner: Class<*>,
        methodName: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ) = named<MethodDescription>(methodName)
        .and(isDeclaredBy<MethodDescription>(owner))
        .and(returns<MethodDescription>(returnType))
        .and(takesArguments<MethodDescription>(*parameterTypes))

    private fun verifyPatchTarget(
        owner: Class<*>,
        methodName: String,
        returnType: Class<*>,
        vararg parameterTypes: Class<*>,
    ) {
        val candidates = owner.declaredMethods.filter {
            it.name == methodName && it.parameterCount == parameterTypes.size
        }
        check(candidates.size == 1) {
            "MockBukkit ${owner.name} must declare exactly one $methodName/${parameterTypes.size} method; " +
                "found ${candidates.size}. Update the pinned compatibility contract before running tests."
        }
        val method = candidates.single()
        check(method.returnType == returnType && method.parameterTypes.contentEquals(parameterTypes)) {
            "MockBukkit ${owner.name}#$methodName signature changed; expected " +
                "${returnType.typeName}(${parameterTypes.joinToString { it.typeName }}), found ${method.toGenericString()}"
        }
    }
}

/** Static delegation bodies injected into existing MockBukkit methods. */
internal object MockBukkitPatchBodies {
    @JvmStatic
    fun saveData(@This player: PlayerMock) {
        MockBukkitObservations.recordPlayerDataSave(player)
    }

    @JvmStatic
    fun effectiveName(@This item: ItemStackMock): Component {
        val meta = item.itemMeta
        return when {
            meta.hasDisplayName() -> requireNotNull(meta.displayName())
            meta.hasItemName() -> requireNotNull(meta.itemName())
            else -> Component.translatable(item.translationKey())
        }
    }

    @JvmStatic
    fun asHoverEvent(
        @Argument(0) item: ItemStack,
        @Argument(1) renderer: UnaryOperator<HoverEvent.ShowItem>,
    ): HoverEvent<HoverEvent.ShowItem> {
        val base = HoverEvent.ShowItem.showItem(item.type.key, item.amount)
        return HoverEvent.showItem(renderer.apply(base))
    }

    @JvmStatic
    fun isPassable(@This block: Block): Boolean = !block.type.isSolid

    @JvmStatic
    fun teleportAsync(
        @This entity: EntityMock,
        @Argument(0) destination: Location,
        @Argument(1) cause: PlayerTeleportEvent.TeleportCause,
        @Argument(2) flags: Array<out TeleportFlag>,
    ): CompletableFuture<Boolean> = CompletableFuture.completedFuture(
        entity.teleport(destination, cause, *flags),
    )
}

/** Player implementation for Paper methods that are inherited defaults rather than patchable bodies. */
internal class ArcPlayerMock(
    server: ServerMock,
    name: String,
    uuid: UUID = UUID.randomUUID(),
) : PlayerMock(server, name, uuid) {
    override fun showTitle(title: Title) {
        MockBukkitObservations.recordTitle(this, title)
    }
}

/** Keeps every player created through the canonical runtime on the ARC-enhanced mock. */
internal class ArcServerMock : ServerMock() {
    private val sequence = AtomicInteger()

    override fun addPlayer(): PlayerMock = addPlayer("Player${sequence.incrementAndGet()}")

    override fun addPlayer(name: String): PlayerMock = ArcPlayerMock(this, name).also(::addPlayer)
}

internal object MockBukkitObservations {
    private val playerDataSaves = ConcurrentHashMap<UUID, AtomicInteger>()
    private val titles = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Title>>()

    fun recordPlayerDataSave(player: Player) {
        playerDataSaves.computeIfAbsent(player.uniqueId) { AtomicInteger() }.incrementAndGet()
    }

    fun playerDataSaveCount(player: Player): Int = playerDataSaves[player.uniqueId]?.get() ?: 0

    fun recordTitle(player: Player, title: Title) {
        titles.computeIfAbsent(player.uniqueId) { CopyOnWriteArrayList() }.add(title)
    }

    fun titles(player: Player): List<Title> = titles[player.uniqueId]?.toList().orEmpty()

    fun reset() {
        playerDataSaves.clear()
        titles.clear()
    }
}
