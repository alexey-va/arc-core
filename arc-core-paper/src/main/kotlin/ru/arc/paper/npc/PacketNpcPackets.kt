package ru.arc.paper.npc

import com.github.retrooper.packetevents.protocol.attribute.Attributes
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.*
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.util.EnumSet
import java.util.Optional
import kotlin.math.abs

/** PacketEvents 2.12.1, Minecraft 1.21.11. Pure, invoked only on the connection event loop. */
internal object PacketNpcPackets {
    fun spawn(frame: PacketNpcFrame): List<PacketWrapper<*>> = buildList {
        val profile = UserProfile(frame.profile.uuid, frame.profile.name, frame.profile.texture?.let {
            listOf(TextureProperty("textures", it, frame.profile.signature))
        }.orEmpty())
        // Keep the profile for the entire visible lifetime so asynchronous client skin lookup
        // can finish. UPDATE_LISTED=false prevents any tab-list entry from the first packet.
        add(WrapperPlayServerPlayerInfoUpdate(EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED, Action.UPDATE_GAME_MODE),
            listOf(PlayerInfo(profile, false, 0, GameMode.SURVIVAL, null, null))))
        add(WrapperPlayServerTeams(frame.teamName, TeamMode.CREATE,
            ScoreBoardTeamInfo(Component.empty(), Component.empty(), Component.empty(),
                NameTagVisibility.NEVER, CollisionRule.NEVER, NamedTextColor.WHITE, OptionData.NONE),
            listOf(frame.profile.name)))
        add(WrapperPlayServerSpawnEntity(frame.entityId, Optional.of(frame.profile.uuid), EntityTypes.PLAYER,
            Vector3d(frame.x, frame.y, frame.z), frame.pitch, frame.bodyYaw, frame.headYaw, 0, Optional.empty()))
        // LivingEntity.onSyncedDataUpdated captures the held item when use starts.
        // It must already be equipped before the active-hand metadata arrives.
        if (frame.equipment.isNotEmpty()) add(equipment(frame))
        add(metadata(frame))
        add(scale(frame))
        add(WrapperPlayServerEntityHeadLook(frame.entityId, frame.headYaw))
        if (frame.seated) addAll(spawnSeat(frame))
    }

    fun update(old: PacketNpcFrame, frame: PacketNpcFrame): List<PacketWrapper<*>> = buildList {
        if (old.seated && !frame.seated) {
            val seat = requireNotNull(old.seat)
            add(WrapperPlayServerSetPassengers(seat.entityId, intArrayOf()))
            add(WrapperPlayServerDestroyEntities(seat.entityId))
        }
        if (old.equipment != frame.equipment) add(equipment(frame))
        if (old.pose != frame.pose || old.bed != frame.bed || old.usingMainHand != frame.usingMainHand) add(metadata(frame))
        if (old.scale != frame.scale) add(scale(frame))
        val dx = frame.x - old.x
        val dy = frame.y - old.y
        val dz = frame.z - old.z
        if (dx != 0.0 || dy != 0.0 || dz != 0.0 || old.bodyYaw != frame.bodyYaw || old.pitch != frame.pitch || old.seated != frame.seated) {
            // Quantized absolute endpoints prevent accumulated relative-movement rounding drift.
            if (old.seated == frame.seated && abs(dx) < 8.0 && abs(dy) < 8.0 && abs(dz) < 8.0) {
                add(WrapperPlayServerEntityRelativeMoveAndRotation(frame.entityId, dx, dy, dz, frame.bodyYaw, frame.pitch, true))
            } else {
                // In 1.21.11 ENTITY_TELEPORT moves the render position but leaves the
                // client's VecDeltaCodec base unchanged. POSITION_SYNC resets both;
                // the next relative step must start at the new authoritative position.
                add(WrapperPlayServerEntityPositionSync(frame.entityId,
                    EntityPositionData(Vector3d(frame.x, frame.y, frame.z), Vector3d.zero(), frame.bodyYaw, frame.pitch), true))
            }
        }
        if (frame.seated) {
            if (!old.seated) addAll(spawnSeat(frame))
            else if (dx != 0.0 || dy != 0.0 || dz != 0.0 || old.scale != frame.scale || old.bodyYaw != frame.bodyYaw)
                add(WrapperPlayServerEntityPositionSync(requireNotNull(frame.seat).entityId,
                    EntityPositionData(seatPosition(frame), Vector3d.zero(), frame.bodyYaw, 0f), true))
        }
        if (old.headYaw != frame.headYaw) add(WrapperPlayServerEntityHeadLook(frame.entityId, frame.headYaw))
        // At most one recent gesture survives a coalesced burst, never historical animation replay.
        if (old.swing != frame.swing) add(WrapperPlayServerEntityAnimation(frame.entityId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM))
        if (old.hurt != frame.hurt) add(WrapperPlayServerHurtAnimation(frame.entityId, frame.bodyYaw))
    }

    fun remove(frame: PacketNpcFrame, lease: PacketNpcLease?): List<PacketWrapper<*>> = buildList {
        lease?.revoke()
        val children = lease?.childIds() ?: intArrayOf()
        if (children.isNotEmpty()) {
            add(WrapperPlayServerSetPassengers(frame.entityId, intArrayOf()))
            add(WrapperPlayServerDestroyEntities(*children))
        }
        frame.seat?.let { add(WrapperPlayServerSetPassengers(it.entityId, intArrayOf())) }
        add(WrapperPlayServerDestroyEntities(frame.entityId))
        frame.seat?.let { add(WrapperPlayServerDestroyEntities(it.entityId)) }
        add(WrapperPlayServerPlayerInfoRemove(frame.profile.uuid))
        add(WrapperPlayServerTeams(frame.teamName, TeamMode.REMOVE, null as ScoreBoardTeamInfo?, emptyList<String>()))
    }

    private fun spawnSeat(frame: PacketNpcFrame): List<PacketWrapper<*>> {
        val seat = requireNotNull(frame.seat)
        return listOf(
            WrapperPlayServerSpawnEntity(seat.entityId, Optional.of(seat.uuid), EntityTypes.ARMOR_STAND,
                seatPosition(frame), 0f, frame.bodyYaw, frame.bodyYaw, 0, Optional.empty()),
            WrapperPlayServerEntityMetadata(seat.entityId, listOf(
                EntityData(0, EntityDataTypes.BYTE, 0x20.toByte()), // invisible
                EntityData(5, EntityDataTypes.BOOLEAN, true), // no gravity
                EntityData(15, EntityDataTypes.BYTE, 0x10.toByte()), // marker: zero native dimensions
            )),
            WrapperPlayServerSetPassengers(seat.entityId, intArrayOf(frame.entityId)),
        )
    }

    // Exact 1.21.11: marker PASSENGER=(0,0,0), Avatar VEHICLE=(0,.6,0)*scale;
    // Entity.positionRider subtracts VEHICLE from the carrier's passenger position.
    private fun seatPosition(frame: PacketNpcFrame) = Vector3d(frame.x, frame.y + .6 * frame.scale, frame.z)

    private fun equipment(frame: PacketNpcFrame) = WrapperPlayServerEntityEquipment(frame.entityId,
        frame.equipment.map { (slot, item) -> Equipment(slot, item.copy()) })

    private fun scale(frame: PacketNpcFrame) = WrapperPlayServerUpdateAttributes(frame.entityId,
        listOf(WrapperPlayServerUpdateAttributes.Property(Attributes.SCALE, frame.scale, emptyList())))

    internal fun metadata(frame: PacketNpcFrame): WrapperPlayServerEntityMetadata {
        // Verified from the mapped 1.21.11 server's Entity/LivingEntity/Avatar defineId order:
        // Entity has 0..7; LivingEntity adds flags8 and 9..14 (sleeping position); Avatar adds main arm15
        // and skin layers16. Player's old skin index17 is WRONG since the Avatar extraction.
        return WrapperPlayServerEntityMetadata(frame.entityId, listOf(
            EntityData(6, EntityDataTypes.ENTITY_POSE, EntityPose.valueOf(frame.pose.name)),
            // IS_USING=1; OFF_HAND=2 remains clear, selecting the main hand; SPIN_ATTACK=4 is clear.
            EntityData(8, EntityDataTypes.BYTE, (if (frame.usingMainHand) 0x01 else 0x00).toByte()),
            EntityData(14, EntityDataTypes.OPTIONAL_BLOCK_POSITION,
                Optional.ofNullable(frame.bed?.let { Vector3i(it.x, it.y, it.z) })),
            EntityData(16, EntityDataTypes.BYTE, 0x7f.toByte()),
        ))
    }
}
