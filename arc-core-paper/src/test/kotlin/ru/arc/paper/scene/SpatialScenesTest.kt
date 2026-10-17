package ru.arc.paper.scene

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.player.ClientVersion

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.function.Consumer
import java.util.logging.Handler
import java.util.logging.LogRecord

class SpatialScenesTest : FreeSpec({
    val priorApi = PacketEvents.getAPI()
    beforeSpec { PacketEvents.setAPI(TestPacketEventsApi()) }
    afterSpec { PacketEvents.setAPI(priorApi) }
    "scene cleanup removes all owned handles after one removal fails and unchanged frames do not reconfigure or re-show" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("cleanup")
            val viewer = UUID.randomUUID()
            val owner = mockk<PaperPacketDisplays>(relaxed = true)
            val first = mockk<PacketTextDisplay>(relaxed = true)
            val second = mockk<PacketTextDisplay>(relaxed = true)
            every { owner.spawnText(any(), any()) } returnsMany listOf(first, second)
            every { first.isValid } returns true; every { second.isValid } returns true
            val scene = PacketDisplayScene<String>(owner)
            fun frame() = SceneFrame(listOf("one", "two").map { key ->
                ScenePart.Text(key, ScenePose(Location(world, 0.0, 65.0, 2.0)), SceneDisplayStyle(), Component.text(key), SceneTextStyle())
            }, SceneAudience.Private(viewer))
            scene.apply(frame()); scene.apply(frame())
            verify(exactly = 1) { first.showTo(viewer) }
            verify(exactly = 1) { first.text(Component.text("one")) }
            verify(exactly = 0) { first.teleport(any()) }
            every { first.remove() } throws IllegalStateException("one removal failed")
            shouldThrow<IllegalStateException> { scene.reset() }
            verify(exactly = 1) { first.remove() }
            verify(exactly = 1) { second.remove() }
            scene.close()
            verify(exactly = 0) { owner.close() }
        }
    }

    "mixed keyed scene preserves handles and avoids reconverting unchanged items while moving every part together" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            scene.apply(f.mixed(2.0))
            val first = f.frames()
            first.size shouldBe 3
            f.transport.itemConversions shouldBe 1
            repeat(3) { scene.apply(f.mixed(2.0)) }
            f.transport.itemConversions shouldBe 1
            f.frames().map { it.entityId } shouldBe first.map { it.entityId }
            scene.apply(f.mixed(2.2, 2))
            val moved = f.frames()
            moved.map { it.entityId } shouldBe first.map { it.entityId }
            moved.all { it.z == 2.2 && it.metadata.teleportDuration == 2 } shouldBe true
            scene.apply(f.mixed(2.2, item = ItemStack(Material.DIAMOND)))
            f.transport.itemConversions shouldBe 2
            f.frames().map { it.entityId } shouldBe first.map { it.entityId }
            scene.hide(); scene.hide()
            f.frames() shouldBe emptyList()
            scene.apply(f.mixed(2.2))
            f.frames().map { it.entityId } shouldBe first.map { it.entityId }
            scene.reset()
            scene.apply(f.mixed(8.0))
            f.frames().none { it.entityId in first.map { p -> p.entityId } } shouldBe true
            scene.close()
            f.frames() shouldBe emptyList()
            f.transport.closed shouldBe false
        } }
    }

    "unchanged item and block comparison does not copy stored payloads again" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("snapshot-diff")
            val owner = mockk<PaperPacketDisplays>(relaxed = true)
            val itemHandle = mockk<PacketItemDisplay>(relaxed = true)
            val blockHandle = mockk<PacketBlockDisplay>(relaxed = true)
            every { owner.spawnItem(any(), any()) } returns itemHandle
            every { owner.spawnBlock(any(), any()) } returns blockHandle
            every { itemHandle.isValid } returns true
            every { blockHandle.isValid } returns true
            val itemSource = mockk<ItemStack>()
            val itemSnapshot = mockk<ItemStack>()
            every { itemSource.clone() } returns itemSnapshot
            every { itemSnapshot.clone() } returns ItemStack(Material.STONE)
            val blockSource = mockk<BlockData>()
            val blockSnapshot = mockk<BlockData>()
            every { blockSource.clone() } returns blockSnapshot
            every { blockSnapshot.clone() } returns mockk()
            val scene = PacketDisplayScene<String>(owner)
            fun frame(): SceneFrame<String> {
                val pose = ScenePose(Location(world, 0.0, 65.0, 2.0))
                return SceneFrame(listOf(
                    ScenePart.Item("item", pose, SceneDisplayStyle(), itemSource, ItemDisplay.ItemDisplayTransform.GUI),
                    ScenePart.Block("block", pose, SceneDisplayStyle(), blockSource),
                ), SceneAudience.Public())
            }
            repeat(6) { scene.apply(frame()) }
            verify(exactly = 6) { itemSource.clone() }
            verify(exactly = 6) { blockSource.clone() }
            verify(exactly = 1) { itemSnapshot.clone() }
            verify(exactly = 1) { blockSnapshot.clone() }
            scene.close()
        }
    }

    "copied transforms and exact audience transitions cannot leak private scene parts" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            val location = Location(f.world, 0.0, 65.0, 2.0)
            val transform = Transformation(Vector3f(), Quaternionf(), Vector3f(2f), Quaternionf())
            val pose = ScenePose(location, transform)
            location.z = 100.0; transform.scale.set(99f)
            val text = ScenePart.Text("title", pose, SceneDisplayStyle(), Component.text("Private"), SceneTextStyle())
            scene.apply(SceneFrame(listOf(text), SceneAudience.Private(f.player.uniqueId)))
            f.capture()
            f.transport.connections[f.player.uniqueId]!!.frames.single().let {
                it.z shouldBe 2.0; it.metadata.transform.scale.x shouldBe 2f
            }
            f.transport.connections[f.other.uniqueId]?.frames.orEmpty() shouldBe emptyList()
            scene.apply(SceneFrame(listOf(text), SceneAudience.Private(f.other.uniqueId)))
            f.capture()
            f.transport.connections[f.player.uniqueId]!!.frames shouldBe emptyList()
            f.transport.connections[f.other.uniqueId]!!.frames.size shouldBe 1
            scene.apply(SceneFrame(listOf(text), SceneAudience.Public()))
            f.capture()
            f.transport.connections[f.player.uniqueId]!!.frames.size shouldBe 1
            f.transport.connections[f.other.uniqueId]!!.frames.size shouldBe 1
            scene.close()
        } }
    }

    "idle placement refresh uses the existing capture tick and unregisters on close" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            scene.apply(f.mixed(2.0))
            var refreshes = 0
            scene.refreshWith { refreshes++; scene.apply(f.mixed(2.0 + refreshes * .1)) }
            repeat(4) { f.capture() }; refreshes shouldBe 0
            f.capture(); refreshes shouldBe 1
            f.transport.connections[f.player.uniqueId]!!.frames.all { it.z == 2.1 } shouldBe true
            scene.close()
            repeat(10) { f.capture() }; refreshes shouldBe 1
            f.transport.closed shouldBe false
        } }
    }

    "authored refresh region retries an initially empty scene and skips absent observers" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            var fits = false
            var attempts = 0
            scene.refreshWith(SceneRefreshRegion(Location(f.world,0.0,65.0,2.0), SceneAudience.Private(f.player.uniqueId), 8.0)) {
                attempts++
                if (fits) scene.apply(f.mixed(2.0)) else scene.hide()
            }
            repeat(5) { f.capture() }; attempts shouldBe 1
            f.present = false
            repeat(10) { f.capture() }; attempts shouldBe 1
            f.present = true; fits = true
            repeat(5) { f.capture() }; attempts shouldBe 2
            f.transport.connections[f.player.uniqueId]!!.frames.size shouldBe 3
            scene.hide()
            repeat(5) { f.capture() }; attempts shouldBe 3
            f.transport.connections[f.player.uniqueId]!!.frames.size shouldBe 3
            scene.close()
        } }
    }

    "idle controls do not capture audience and active unloaded targets recover until the last scene clears" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            repeat(5) { f.capture() }
            f.audienceCaptures shouldBe 0
            f.loaded = false
            f.current = f.controlFrame()
            f.controls.apply(f.key, checkNotNull(f.current))
            f.capture()
            f.audienceCaptures shouldBe 1
            f.interactions shouldBe emptyList()
            f.loaded = true
            f.capture()
            f.audienceCaptures shouldBe 2
            f.interactions.single().isValid shouldBe true

            val second = SpatialSceneKey("second-project")
            f.controls.apply(second, checkNotNull(f.current))
            f.controls.clear(f.key)
            f.capture()
            f.audienceCaptures shouldBe 3
            f.interactions.first().isValid shouldBe false
            f.interactions.last().isValid shouldBe true
            f.controls.clear(second)
            repeat(5) { f.capture() }
            f.audienceCaptures shouldBe 3
            f.interactions.all { !it.isValid } shouldBe true

            f.controls.apply(f.key, checkNotNull(f.current))
            f.capture()
            f.audienceCaptures shouldBe 4
            f.interactions.last().isValid shouldBe true
            f.controls.close()
            repeat(5) { f.capture() }
            f.audienceCaptures shouldBe 4
        } }
    }

    "clearing the last control preserves an independent initially empty scene refresh subscription" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            var attempts = 0
            scene.refreshWith(SceneRefreshRegion(Location(f.world, 0.0, 65.0, 2.0), SceneAudience.Public(), 8.0)) {
                attempts++
            }
            f.controls.apply(f.key, f.controlFrame())
            f.controls.clear(f.key)
            repeat(5) { f.capture() }
            f.audienceCaptures shouldBe 5
            attempts shouldBe 1
            scene.close()
            repeat(5) { f.capture() }
            f.audienceCaptures shouldBe 5
            attempts shouldBe 1
        } }
    }

    "public controls use fresh actual planes and context while preserving early right event and viewer lifetime rules" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            f.current = f.controlFrame()
            f.controls.apply(f.key, checkNotNull(f.current))
            f.capture()
            val first = f.interactions.single()
            val offhand = PlayerInteractEntityEvent(f.player, first, EquipmentSlot.OFF_HAND)
            f.controls.onEarlyClick(offhand); offhand.isCancelled shouldBe false
            val cancelled = PlayerInteractEntityEvent(f.player, first, EquipmentSlot.HAND).apply { isCancelled = true }
            f.controls.onEarlyClick(cancelled); f.actions shouldBe emptyList()
            val click = PlayerInteractEntityEvent(f.player, first, EquipmentSlot.HAND)
            f.controls.onEarlyClick(click); f.actions shouldBe listOf("one")
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, first, EquipmentSlot.HAND))
            f.actions shouldBe listOf("one")
            f.current = f.controlFrame("two")
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, first, EquipmentSlot.HAND))
            f.actions shouldBe listOf("one")
            f.controls.apply(f.key, checkNotNull(f.current))
            val replacement = f.interactions.last()
            replacement.uniqueId shouldNotBe first.uniqueId
            first.isValid shouldBe false
            f.now += 250_000_000
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, first, EquipmentSlot.HAND))
            f.actions shouldBe listOf("one")
            f.blockRay = true
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, replacement, EquipmentSlot.HAND))
            f.actions shouldBe listOf("one")
            f.blockRay = false
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, replacement, EquipmentSlot.HAND))
            f.actions shouldBe listOf("one", "two")
            f.controls.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.other })
            replacement.isValid shouldBe true
            f.controls.contains(f.key) shouldBe true
            f.controls.clear(f.key)
            replacement.isValid shouldBe false
        } }
    }

    "world scene death removes native admission before permission revocation and never destroys public scene" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            var allowed = true
            val group = f.displays.createAudienceGroup { it.uniqueId == f.player.uniqueId && allowed }
            f.current = f.controlFrame(audience = SceneAudience.Public(group))
            f.controls.apply(f.key, checkNotNull(f.current)); f.capture()
            val entity = f.interactions.single()
            verify(exactly = 1) { f.player.showEntity(f.plugin, entity) }
            f.controls.onDeath(mockk<PlayerDeathEvent> { every { player } returns f.player })
            verify(exactly = 1) { f.player.hideEntity(f.plugin, entity) }
            allowed = false
            f.capture()
            verify(exactly = 1) { f.player.showEntity(f.plugin, entity) }
            f.controls.contains(f.key) shouldBe true
            entity.isValid shouldBe true
            f.controls.target(f.key, f.player) shouldBe null
        } }
    }

    "native failure revokes input before feature cleanup and reports once even when the failure hook throws" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            scene.apply(f.mixed(2.0))
            val frame = f.controlFrame()
            val failure = IllegalStateException("Feature activation failed")
            var attempts = 0
            var observations = 0
            var failures = 0
            val logs = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { logs += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            lateinit var controls: PaperSpatialControls<String, String>
            controls = PaperSpatialControls(f.plugin, f.displays, object : SpatialControlController<String, String>() {
                override fun current(player: Player, scene: SpatialSceneKey) = frame
                override fun activate(player: Player, scene: SpatialSceneKey, context: String, action: String) {
                    attempts++; throw failure
                }
                override fun inputObserved(player: Player, scene: SpatialSceneKey, context: String, action: String?,
                    accepted: Boolean, reason: SpatialInputReason) { observations++ }
                override fun inputFailed(player: Player, key: SpatialSceneKey, context: String, error: Exception) {
                    failures++
                    context shouldBe "one"
                    error shouldBe failure
                    controls.contains(key) shouldBe false
                    f.interactions.all { !it.isValid } shouldBe true
                    scene.close()
                    throw failure
                }
            })
            f.plugin.logger.addHandler(handler)
            try {
                controls.apply(f.key, frame)
                val old = f.interactions.single()
                controls.onEarlyClick(PlayerInteractEntityEvent(f.player, old, EquipmentSlot.HAND))
                attempts shouldBe 1
                observations shouldBe 1
                failures shouldBe 1
                logs.count { it.message == "Spatial input failed for ${f.key.value}" } shouldBe 1
                f.frames() shouldBe emptyList()
                controls.onEarlyClick(PlayerInteractEntityEvent(f.player, old, EquipmentSlot.HAND))
                attempts shouldBe 1
                observations shouldBe 1
                failures shouldBe 1
            } finally {
                controls.close(); scene.close(); f.plugin.logger.removeHandler(handler)
            }
        } }
    }

    "native target creation and recovery never load an unknown chunk or recreate surviving packet parts" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val scene = PacketDisplayScene<String>(f.displays)
            scene.apply(f.mixed(2.0))
            f.current = f.controlFrame()
            f.loaded = false
            f.controls.apply(f.key, checkNotNull(f.current))
            f.interactions shouldBe emptyList()
            f.loaded = true; f.capture()
            val ids = f.frames().map { it.entityId }
            val first = f.interactions.single()
            first.remove(); f.capture()
            f.interactions.size shouldBe 2
            f.interactions.last().uniqueId shouldNotBe first.uniqueId
            f.frames().map { it.entityId } shouldBe ids
            verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
            scene.close()
        } }
    }

    "exact collision volumes allow the empty portion of a slab and unknown chunks stop all block reads" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val volume = DisplayOrientedVolume(Vector3d(.5, 65.5, 2.5), Vector3d(1.0,0.0,0.0), Vector3d(0.0,1.0,0.0),
                Vector3d(0.0,0.0,1.0), Vector3d(.2,.1,.2))
            f.boxes = { _,_,_ -> listOf(BoundingBox(0.0,0.0,0.0,1.0,.125,1.0)) }
            val space = PaperSpatialSpace()
            space.clear(f.world, volume, f.player) shouldBe true
            f.boxes = { _,_,_ -> listOf(BoundingBox(0.0,0.0,0.0,1.0,1.0,1.0)) }
            space.clear(f.world, volume, f.player) shouldBe false
            val reads = f.blockReads
            f.sent = false
            space.clear(f.world, volume, f.player) shouldBe false
            f.blockReads shouldBe reads
        } }
    }

    "new visual-edge obstruction rejects input immediately before the next placement refresh" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val volume = DisplayOrientedVolume(Vector3d(0.0,65.62,2.4), Vector3d(1.0,0.0,0.0), Vector3d(0.0,1.0,0.0),
                Vector3d(0.0,0.0,1.0), Vector3d(1.5,.75,.04))
            f.current = f.controlFrame(footprints = listOf(volume))
            f.controls.apply(f.key, checkNotNull(f.current))
            f.current = f.controlFrame(footprints = listOf(volume.translated(Vector3d())))
            f.controls.target(f.key, f.player)?.action shouldBe "inspect"
            val entity = f.interactions.single()
            f.boxes = { x,y,z -> if (x==1 && y==65 && z==2) listOf(BoundingBox(0.0,0.0,0.0,1.0,1.0,1.0)) else emptyList() }
            f.controls.target(f.key, f.player) shouldBe null
            f.controls.onEarlyClick(PlayerInteractEntityEvent(f.player, entity, EquipmentSlot.HAND))
            f.actions shouldBe emptyList()
            f.interactions.single() shouldBe entity
            f.boxes = { _,_,_ -> emptyList() }
            f.controls.target(f.key, f.player)?.action shouldBe "inspect"
        } }
    }

    "anchored surface escapes deterministically and returns only with clearance without mutating its authored pose" {
        MockBukkitTestRuntime.open().use { paper -> SceneFixture(paper).use { f ->
            val authored = Location(f.world, .5, 65.5, 2.5)
            val volume = DisplayOrientedVolume(Vector3d(.5,65.5,2.5), Vector3d(1.0,0.0,0.0), Vector3d(0.0,1.0,0.0),
                Vector3d(0.0,0.0,1.0), Vector3d(.2,.1,.04))
            val surface = AnchoredSurface(authored, listOf(volume), 1.0, 0.0, 0.0)
            val space = PaperSpatialSpace()
            f.boxes = { x,y,z -> if (x==0 && y==65 && z==2) listOf(BoundingBox(0.0,0.0,0.0,1.0,1.0,.6)) else emptyList() }
            val escaped = checkNotNull(space.place(surface))
            escaped.z shouldBe 2.75
            authored.z shouldBe 2.5
            space.place(surface, escaped) shouldBe escaped
            f.boxes = { _,_,_ -> emptyList() }
            space.place(surface, escaped) shouldBe authored
            f.loaded = false
            space.place(surface, escaped) shouldBe null
            verify(exactly = 0) { f.world.getChunkAt(any<Int>(), any<Int>()) }
        } }
    }
})

private class SceneFixture(paper: MockBukkitTestRuntime) : AutoCloseable {
    val plugin = paper.createSimplePlugin("SpatialSceneTest")
    val world = mockk<World>()
    val player = mockk<Player>(relaxed = true)
    val other = mockk<Player>(relaxed = true)
    val transport = SceneTransport()
    var loaded = true
    var sent = true
    var blockRay = false
    var present = true
    var audienceCaptures = 0
    var blockReads = 0
    var boxes: (Int,Int,Int) -> List<BoundingBox> = { _,_,_ -> emptyList() }
    val interactions = mutableListOf<Interaction>()
    val actions = mutableListOf<String>()
    var now = 1_000_000_000L
    val key = SpatialSceneKey("public-project")
    var current: SpatialControlFrame<String,String>? = null
    val displays: PaperPacketDisplays
    val controls: PaperSpatialControls<String,String>
    val block = mockk<BlockData>(relaxed = true)
    init {
        val worldId = UUID.randomUUID()
        every { world.uid } returns worldId
        every { world.minHeight } returns -64; every { world.maxHeight } returns 320
        every { world.isChunkLoaded(any<Int>(), any<Int>()) } answers { loaded }
        every { world.rayTraceBlocks(any(), any(), any(), any(), any(), any()) } answers {
            if (blockRay) org.bukkit.util.RayTraceResult(Vector(0,65,1)) else null
        }
        every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            blockReads++
            val result = boxes(firstArg(),secondArg(),thirdArg())
            mockk<Block> { every { collisionShape.boundingBoxes } returns result }
        }
        listOf(player,other).forEach { p ->
            every { p.uniqueId } returns UUID.randomUUID()
            every { p.world } returns world
            every { p.isOnline } returns true; every { p.isDead } returns false
            every { p.isChunkSent(any<Long>()) } answers { sent }
            every { p.location } answers { Location(world,0.0,64.0,0.0) }
            every { p.eyeLocation } answers { Location(world,0.0,65.62,0.0) }
        }
        every { world.spawn(any<Location>(), eq(Interaction::class.java), any<Consumer<Interaction>>()) } answers {
            var at = firstArg<Location>().clone(); var valid = true
            val entity = mockk<Interaction>(relaxed = true)
            every { entity.uniqueId } returns UUID.randomUUID()
            every { entity.location } answers { at.clone() }
            every { entity.world } returns world
            every { entity.isValid } answers { valid }
            every { entity.teleport(any<Location>()) } answers { at = firstArg<Location>().clone(); true }
            every { entity.remove() } answers { valid = false }
            thirdArg<Consumer<Interaction>>().accept(entity)
            interactions += entity; entity
        }
        every { block.clone() } returns block
        displays = PaperPacketDisplays(plugin, transport, LifecycleTaskScope(TestTaskScheduler()), object : PacketDisplayAudienceSource {
            override fun checkThread() = Unit
            override fun capture(): List<PacketDisplayViewer> {
                audienceCaptures++
                return (if (present) listOf(player,other) else emptyList()).map { p -> PacketDisplayViewer(p,p.uniqueId,worldId,
                    if (p===player) 1 else 2, emptySet(),0.0,64.0,0.0,
                    (-2..2).flatMap { x -> (-2..2).map { z -> Chunk.getChunkKey(x,z) } }.toSet()) }
            }
        })
        controls = PaperSpatialControls(plugin, displays, object : SpatialControlController<String,String>() {
            override fun current(player: Player, scene: SpatialSceneKey) = current
            override fun activate(player: Player, scene: SpatialSceneKey, context: String, action: String) { actions += context }
        }, PaperSpatialSpace()) { now }
    }
    fun mixed(z: Double, ticks: Int = 0, item: ItemStack = ItemStack(Material.STONE)): SceneFrame<String> {
        val pose = ScenePose(Location(world,0.0,65.0,z))
        return SceneFrame(listOf(ScenePart.Item("item",pose,SceneDisplayStyle(),item,ItemDisplay.ItemDisplayTransform.GUI),
            ScenePart.Text("count",pose,SceneDisplayStyle(),Component.text("× 2"),SceneTextStyle()),
            ScenePart.Block("frame",pose,SceneDisplayStyle(),block)), SceneAudience.Private(player.uniqueId),ticks)
    }
    fun controlFrame(context: String = "one", audience: SceneAudience = SceneAudience.Public(),
        footprints: List<DisplayOrientedVolume> = emptyList()): SpatialControlFrame<String,String> =
        SpatialControlFrame(context,audience,SpatialSceneScope.World,listOf(SpatialControl("inspect","inspect",
            listOf(SpatialHitPlane(Location(world,0.0,65.62,2.4,180f,0f),3.0,1.5)),
            SpatialNativeTarget(Location(world,0.0,64.87,2.4),3f,1.5f))),SpatialInputProfile.ENTITY_RIGHT_EARLY,SpatialReach.Fixed(4.0),
            clearanceFootprints = footprints)
    fun capture() = displays.refresh()
    fun frames(): List<PacketDisplayFrame> { capture(); return transport.connections[player.uniqueId]?.frames.orEmpty() }
    override fun close() { controls.close(); displays.close() }
}

private class SceneTransport : PacketDisplayTransport {
    val connections = mutableMapOf<UUID, SceneConnection>()
    var itemConversions = 0
    var next = 100
    var closed = false
    override fun nextEntityId() = next++
    override fun connection(player: Player) = connections.getOrPut(player.uniqueId) { SceneConnection() }
    override fun forget(player: Player) = Unit
    override fun blockStateId(block: BlockData) = 1
    override fun itemSnapshot(item: ItemStack): com.github.retrooper.packetevents.protocol.item.ItemStack {
        itemConversions++
        return com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(if (item.type == Material.DIAMOND) ItemTypes.DIAMOND else ItemTypes.STONE)
            .amount(item.amount).version(ClientVersion.V_1_21_11).build()
    }
    override fun close() { closed = true }
}
private class SceneConnection : PacketDisplayConnection {
    override val identity = Any()
    var frames = emptyList<PacketDisplayFrame>()
    override fun submit(desired: List<PacketDisplayFrame>, resetChunks: Set<Long>, resetAll: Boolean,
        desiredPassengers: Map<Int,List<Int>>, nativePassengerSnapshots: Map<Int,List<Int>>, liveVehicleIds: Set<Int>?) { frames = desired }
}
