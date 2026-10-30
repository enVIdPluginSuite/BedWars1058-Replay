package dev.etran.bedreplay.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose
import com.github.retrooper.packetevents.protocol.entity.type.EntityType
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.ItemStack as PeItemStack
import com.github.retrooper.packetevents.protocol.player.Equipment
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot
import com.github.retrooper.packetevents.protocol.player.GameMode
import com.github.retrooper.packetevents.protocol.player.TextureProperty
import com.github.retrooper.packetevents.protocol.player.UserProfile
import com.github.retrooper.packetevents.protocol.world.Location
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import dev.etran.bedreplay.format.Anim
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import dev.etran.bedreplay.format.Flag
import dev.etran.bedreplay.format.ItemSnapshot
import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.format.Slot
import dev.etran.bedreplay.util.ServerCompat
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.EnumSet
import java.util.Optional
import java.util.UUID
import java.util.logging.Level

const val CAMERA_SLOT = 7

/** Mutable state of one replayed entity, shared between the timeline and the renderer. */
class Ent(val rid: Int, val kind: String) {
    val isPlayer get() = kind == "player"
    var x = 0.0; var y = 0.0; var z = 0.0
    var yaw = 0f; var pitch = 0f
    var onGround = false
    var flags = 0
    val equip = arrayOfNulls<ItemSnapshot>(6)
    var item: ItemSnapshot? = null
    var extra: String? = null
    var health = 20f; var maxHealth = 20f; var food = 20; var level = 0
    var heldSlot = 0
    var hotbar: List<ItemSnapshot?> = emptyList()

    // name tag decoration (team prefix / suffix) and whether the tag is hidden
    var prefix = ""
    var suffix = ""
    var tagHidden = false
    var tagSent: String? = null
    var customName: String? = null
    var nameVisible = false

    /** Entity id the viewer's client knows this entity by (assigned when spawned). */
    var liveId = -1
    var uuid: UUID = UUID.randomUUID()
    var spawned = false
}

/**
 * Draws replay entities to a single viewer as packet-only entities (no real entity ever exists on
 * the server), so any number of viewers can watch different replays at once.
 *
 * Packet shapes differ a lot between 1.8 and 26.x; the version switches below follow the protocol
 * changes at 1.9, 1.14, 1.17, 1.19.3 and 1.20.2. Every send is guarded so an unsupported shape on
 * some version loses one visual rather than breaking playback.
 */
class NpcRenderer(
    private val plugin: Plugin,
    /** Everyone currently watching; entities are shown to all of them (they share ids, so packets are identical). */
    private val targets: () -> Collection<Player>,
    private val players: Map<Int, PlayerInfo>,
    private val skinLayers: Boolean = true,
    /** OFF, NUMBER, HEARTS or BOTH: how player health is shown above the head. */
    private val healthMode: String = "BOTH",
) {
    private val pm = PacketEvents.getAPI().playerManager
    private val sv: ServerVersion = PacketEvents.getAPI().serverManager.version
    private var warned = false

    /** While set, packets go to this one viewer only (a viewer joining late, or leaving). */
    private var only: Player? = null

    private fun send(w: PacketWrapper<*>) {
        val list = only?.let { listOf(it) } ?: targets()
        for (p in list) {
            try {
                pm.sendPacket(p, w)
            } catch (t: Throwable) {
                if (!warned) {
                    warned = true
                    plugin.logger.log(Level.WARNING, "Replay packet could not be sent (${w.javaClass.simpleName}); further failures are silent", t)
                }
            }
        }
    }

    private inline fun toOnly(p: Player, block: () -> Unit) {
        val prev = only
        only = p
        try { block() } finally { only = prev }
    }

    /** Shows everything that is already spawned to a viewer who joined after playback started. */
    fun spawnFor(p: Player, ents: Collection<Ent>) {
        toOnly(p) { for (e in ents) if (e.spawned) sendSpawn(e) }
    }

    /** Removes everything from one viewer's client (they are leaving, the others keep watching). */
    fun despawnFor(p: Player, ents: Collection<Ent>) {
        toOnly(p) { for (e in ents) if (e.spawned) destroyPackets(e) }
        removeHealthObjective(p)
    }

    // ------------------------------------------------------------------ spawn / despawn

    fun spawn(e: Ent) {
        if (e.spawned) return
        e.liveId = SpigotReflectionUtil.generateEntityId()
        e.uuid = UUID.randomUUID()
        sendSpawn(e)
    }

    /** Sends the spawn packets for an entity that already has its ids, to the current targets. */
    private fun sendSpawn(e: Ent) {
        if (e.isPlayer) spawnPlayer(e) else spawnOther(e) ?: return
        e.spawned = true
        applyAppearance(e)
        if (e.isPlayer) { nametag(e); healthScore(e) }
    }

    private fun spawnPlayer(e: Ent) {
        val info = players[e.rid]
        val textures = if (info?.skinValue != null) listOf(TextureProperty("textures", info.skinValue, info.skinSignature)) else emptyList()
        val profile = UserProfile(e.uuid, (info?.name ?: "Player").take(16), textures)
        val loc = Location(e.x, e.y, e.z, e.yaw, e.pitch)

        // Tab-list entry is needed for the skin; keep it out of the visible tab list.
        if (sv.isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
            send(
                WrapperPlayServerPlayerInfoUpdate(
                    EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER, WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                    WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, false, 0, GameMode.SURVIVAL, null, null),
                ),
            )
        } else {
            send(
                WrapperPlayServerPlayerInfo(
                    WrapperPlayServerPlayerInfo.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfo.PlayerData(null, profile, GameMode.SURVIVAL, 0),
                ),
            )
        }

        if (sv.isNewerThanOrEquals(ServerVersion.V_1_20_2)) {
            send(WrapperPlayServerSpawnEntity(e.liveId, e.uuid, EntityTypes.PLAYER, loc, e.yaw, 0, Vector3d(0.0, 0.0, 0.0)))
        } else {
            send(WrapperPlayServerSpawnPlayer(e.liveId, e.uuid, loc, emptyList<EntityData<*>>()))
        }
        send(WrapperPlayServerEntityHeadLook(e.liveId, e.yaw))

        if (sv.isOlderThan(ServerVersion.V_1_19_3)) {
            // Older clients show every info entry in the tab list, so drop it once the skin has loaded.
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                send(
                    WrapperPlayServerPlayerInfo(
                        WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER,
                        WrapperPlayServerPlayerInfo.PlayerData(null, profile, GameMode.SURVIVAL, 0),
                    ),
                )
            }, 3L)
        }
    }

    private fun spawnOther(e: Ent): Unit? {
        val type = entityType(e.kind) ?: return null
        val loc = Location(e.x, e.y, e.z, e.yaw, e.pitch)
        val data = if (e.kind == "falling_block") fallingBlockData(e.extra) else 0
        send(WrapperPlayServerSpawnEntity(e.liveId, e.uuid, type, loc, e.yaw, data, Vector3d(0.0, 0.0, 0.0)))
        return Unit
    }

    private fun entityType(kind: String): EntityType? = try {
        EntityTypes.getByName(kind) ?: EntityTypes.getByName("minecraft:$kind")
    } catch (_: Throwable) {
        null
    }

    private fun fallingBlockData(material: String?): Int = try {
        WrappedBlockState.getByString("minecraft:" + (material ?: "sand").lowercase()).globalId
    } catch (_: Throwable) {
        0
    }

    fun remove(e: Ent) {
        if (!e.spawned) return
        e.spawned = false
        destroyPackets(e)
        e.tagSent = null
    }

    private fun destroyPackets(e: Ent) {
        send(WrapperPlayServerDestroyEntities(e.liveId))
        if (e.isPlayer && e.tagSent != null) send(WrapperPlayServerTeams(teamName(e), WrapperPlayServerTeams.TeamMode.REMOVE, null as WrapperPlayServerTeams.ScoreBoardTeamInfo?))
        if (e.isPlayer && sv.isNewerThanOrEquals(ServerVersion.V_1_19_3)) send(WrapperPlayServerPlayerInfoRemove(e.uuid))
    }

    // ------------------------------------------------------------------ updates

    fun teleport(e: Ent) {
        if (!e.spawned) return
        send(WrapperPlayServerEntityTeleport(e.liveId, Location(e.x, e.y, e.z, e.yaw, e.pitch), e.onGround))
        if (e.isPlayer) send(WrapperPlayServerEntityHeadLook(e.liveId, e.yaw))
    }

    fun equip(e: Ent, slot: Int) {
        if (!e.spawned || (slot == Slot.OFF_HAND && sv.isOlderThan(ServerVersion.V_1_9))) return
        send(WrapperPlayServerEntityEquipment(e.liveId, listOf(Equipment(peSlot(slot), peItem(e.equip[slot])))))
    }

    fun flags(e: Ent) {
        if (!e.spawned) return
        var bits = 0
        if (e.flags and Flag.BURNING != 0) bits = bits or 0x01
        if (e.flags and Flag.SNEAKING != 0) bits = bits or 0x02
        if (e.flags and Flag.SPRINTING != 0) bits = bits or 0x08
        if (e.flags and Flag.INVISIBLE != 0) bits = bits or 0x20
        if (e.flags and Flag.GLIDING != 0) bits = bits or 0x80
        val data = ArrayList<EntityData<*>>(2)
        data += EntityData(0, EntityDataTypes.BYTE, bits.toByte())
        // From 1.14 a crouching player also needs the pose entity data (index 6 in every version since).
        if (e.isPlayer && sv.isNewerThanOrEquals(ServerVersion.V_1_14)) {
            val pose = if (e.flags and Flag.SNEAKING != 0) EntityPose.CROUCHING else EntityPose.STANDING
            data += EntityData(6, EntityDataTypes.ENTITY_POSE, pose)
        }
        send(WrapperPlayServerEntityMetadata(e.liveId, data))
        if (e.isPlayer) nametag(e) // an invisible player keeps their name tag even if the match hid it
    }

    // ------------------------------------------------------------------ name tags

    private fun teamName(e: Ent) = "br" + e.rid

    /**
     * Name tag = team prefix + player name + suffix, drawn by a client-side team that only this viewer is told about.
     * Falls back to the recorded team colour when the match had no prefix.
     */
    fun nametag(e: Ent) {
        if (!e.spawned || !e.isPlayer) return
        val info = players[e.rid]
        val name = (info?.name ?: "Player").take(16)
        var prefix = e.prefix
        if (prefix.isBlank() && info != null && info.teamColor >= 0) prefix = "§" + nearestColorCode(info.teamColor)
        var suffix = e.suffix
        if (heartRow()) {
            val row = heartRow(e)
            // 1.8 - 1.12 limit a team suffix to 16 characters
            if (!(sv.isOlderThan(ServerVersion.V_1_13) && suffix.length + row.length > 16)) suffix += row
        }
        // Invisible players are only findable by their tag, so it stays even if the match hid it.
        val hidden = e.tagHidden && e.flags and Flag.INVISIBLE == 0
        val sig = "$prefix|$suffix|$hidden"
        // A viewer who just joined has no team yet, so it must be created for them even if others already have it.
        val first = e.tagSent == null || only != null
        if (sig == e.tagSent && only == null) return
        e.tagSent = sig

        val visibility = if (hidden) WrapperPlayServerTeams.NameTagVisibility.NEVER else WrapperPlayServerTeams.NameTagVisibility.ALWAYS
        val teamInfo = WrapperPlayServerTeams.ScoreBoardTeamInfo(
            net.kyori.adventure.text.Component.text(teamName(e)),
            AdventureSerializer.fromLegacyFormat(prefix),
            AdventureSerializer.fromLegacyFormat(suffix),
            visibility,
            WrapperPlayServerTeams.CollisionRule.NEVER,
            net.kyori.adventure.text.format.NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE,
        )
        val mode = if (first) WrapperPlayServerTeams.TeamMode.CREATE else WrapperPlayServerTeams.TeamMode.UPDATE
        send(WrapperPlayServerTeams(teamName(e), mode, teamInfo, listOf(name)))
    }

    // ------------------------------------------------------------------ health

    private val objectiveReady = HashSet<UUID>()

    private fun belowName() = healthMode == "NUMBER" || healthMode == "BOTH"
    private fun heartRow() = healthMode == "HEARTS" || healthMode == "BOTH"

    /** " ❤❤❤❤❤❤❤❤❤❤": one heart per 10% of max health, red for what is left and dark gray for what is missing. */
    private fun heartRow(e: Ent): String {
        val max = if (e.maxHealth > 0f) e.maxHealth else 20f
        val full = Math.ceil((e.health.coerceIn(0f, max) / max * 10f).toDouble()).toInt().coerceIn(0, 10)
        return " §c" + "❤".repeat(full) + (if (full < 10) "§8" + "❤".repeat(10 - full) else "")
    }

    private fun healthObjectiveName() = "brhp"

    private fun ensureHealthObjective() {
        if (!belowName()) return
        val list = only?.let { listOf(it) } ?: targets()
        for (p in list) {
            if (!objectiveReady.add(p.uniqueId)) continue
            try {
                pm.sendPacket(p, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective(
                    healthObjectiveName(),
                    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE,
                    AdventureSerializer.fromLegacyFormat("§c❤"),
                    com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
                ))
                pm.sendPacket(p, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard(2, healthObjectiveName()))
            } catch (_: Throwable) {
            }
        }
    }

    /** Removes the health objective from one viewer's client. */
    fun removeHealthObjective(p: Player) {
        if (!objectiveReady.remove(p.uniqueId)) return
        try {
            pm.sendPacket(p, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective(
                healthObjectiveName(),
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                AdventureSerializer.fromLegacyFormat("§c❤"),
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType.INTEGER,
            ))
        } catch (_: Throwable) {
        }
    }

    /** The number under the name (health + a red heart); a score is looked up by the player's name. */
    private fun healthScore(e: Ent) {
        if (!belowName() || !e.spawned || !e.isPlayer) return
        ensureHealthObjective()
        val name = (players[e.rid]?.name ?: "Player").take(16)
        val value = Math.ceil(e.health.toDouble()).toInt().coerceAtLeast(0)
        send(com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore(
            name, com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
            healthObjectiveName(), Optional.of(value),
        ))
    }

    /** Health changed: update the number under the name and the heart row after it. */
    fun health(e: Ent) {
        if (!e.spawned || !e.isPlayer || healthMode == "OFF") return
        healthScore(e)
        nametag(e)
    }

    private fun nearestColorCode(rgb: Int): Char {
        var best = 'f'
        var bestDist = Int.MAX_VALUE
        for ((code, c) in PALETTE) {
            val dr = ((rgb shr 16) and 0xFF) - ((c shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((c shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = code }
        }
        return best
    }

    fun animate(e: Ent, anim: Int) {
        if (!e.spawned) return
        when (anim) {
            Anim.SWING_MAIN -> send(WrapperPlayServerEntityAnimation(e.liveId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM))
            Anim.SWING_OFF -> send(WrapperPlayServerEntityAnimation(e.liveId, WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND))
            Anim.CRIT -> send(WrapperPlayServerEntityAnimation(e.liveId, WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT))
            Anim.MAGIC_CRIT -> send(WrapperPlayServerEntityAnimation(e.liveId, WrapperPlayServerEntityAnimation.EntityAnimationType.MAGIC_CRITICAL_HIT))
            Anim.HURT -> send(WrapperPlayServerEntityStatus(e.liveId, 2))
            Anim.DEATH -> send(WrapperPlayServerEntityStatus(e.liveId, 3))
            else -> {}
        }
    }

    /** Camera attaches the viewer's first-person view to an entity (like spectating it). */
    fun camera(p: Player, e: Ent?) {
        toOnly(p) { send(WrapperPlayServerCamera(if (e != null && e.spawned) e.liveId else p.entityId)) }
    }

    /** First-person hand: the client draws its own selected slot, so mirror the followed entity's item into hotbar slot 7. */
    fun showHeld(p: Player, e: Ent?) {
        try {
            val item = peItem(e?.equip?.get(Slot.MAIN_HAND))
            val pkt = com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot(0, 0, 36 + CAMERA_SLOT, item)
            PacketEvents.getAPI().playerManager.sendPacket(p, pkt)
        } catch (_: Throwable) {
        }
    }

    /** Everything that is not position: equipment, flags, skin layers, item stack. */
    private fun applyAppearance(e: Ent) {
        for (slot in Slot.MAIN_HAND..Slot.HELMET) if (e.equip[slot] != null) equip(e, slot)
        if (e.flags != 0) flags(e)
        metadataExtras(e)
    }

    /** Custom name over a mob / armor stand. Metadata index 2 (name) and 3 (visible) have been stable since 1.8; only the types changed. */
    fun customName(e: Ent) {
        if (!e.spawned || e.isPlayer) return
        val data = ArrayList<EntityData<*>>(2)
        val name = e.customName ?: ""
        val shown = e.nameVisible && name.isNotEmpty()
        when {
            sv.isOlderThan(ServerVersion.V_1_9) -> {
                data += EntityData(2, EntityDataTypes.STRING, name.take(64))
                data += EntityData(3, EntityDataTypes.BYTE, (if (shown) 1 else 0).toByte())
            }
            sv.isOlderThan(ServerVersion.V_1_13) -> {
                data += EntityData(2, EntityDataTypes.STRING, name.take(64))
                data += EntityData(3, EntityDataTypes.BOOLEAN, shown)
            }
            else -> {
                data += EntityData(
                    2, EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                    if (name.isEmpty()) java.util.Optional.empty() else java.util.Optional.of(AdventureSerializer.fromLegacyFormat(name)),
                )
                data += EntityData(3, EntityDataTypes.BOOLEAN, shown)
            }
        }
        send(WrapperPlayServerEntityMetadata(e.liveId, data))
    }

    private fun metadataExtras(e: Ent) {
        if (!e.isPlayer && e.customName != null) customName(e)
        val data = ArrayList<EntityData<*>>(2)
        if (e.isPlayer && skinLayers) {
            val idx = skinLayerIndex()
            if (idx >= 0) data += EntityData(idx, EntityDataTypes.BYTE, 0x7F.toByte())
        }
        if (e.kind == "item" && e.item != null) {
            val idx = itemStackIndex()
            val stack = peItem(e.item)
            data += EntityData(idx, EntityDataTypes.ITEMSTACK, stack)
        }
        if (data.isNotEmpty()) send(WrapperPlayServerEntityMetadata(e.liveId, data))
    }

    /** Index of the "displayed skin parts" byte on player entities; -1 when unsure (skip rather than risk a client error). */
    private fun skinLayerIndex(): Int = when {
        sv.isOlderThan(ServerVersion.V_1_9) -> 10
        sv.isOlderThan(ServerVersion.V_1_10) -> 12
        sv.isOlderThan(ServerVersion.V_1_14) -> 13
        sv.isOlderThan(ServerVersion.V_1_15) -> 15
        sv.isOlderThan(ServerVersion.V_1_17) -> 16
        else -> 17
    }

    /** Index of the item stack field on dropped item entities. */
    private fun itemStackIndex(): Int = when {
        sv.isOlderThan(ServerVersion.V_1_9) -> 10
        sv.isOlderThan(ServerVersion.V_1_10) -> 5
        sv.isOlderThan(ServerVersion.V_1_14) -> 6
        sv.isOlderThan(ServerVersion.V_1_17) -> 7
        else -> 8
    }

    private companion object {
        val PALETTE = listOf(
            '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA,
            '6' to 0xFFAA00, '7' to 0xAAAAAA, '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
            'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
        )
    }

    // ------------------------------------------------------------------ conversions

    private fun peSlot(slot: Int): EquipmentSlot = when (slot) {
        Slot.MAIN_HAND -> EquipmentSlot.MAIN_HAND
        Slot.OFF_HAND -> EquipmentSlot.OFF_HAND
        Slot.BOOTS -> EquipmentSlot.BOOTS
        Slot.LEGGINGS -> EquipmentSlot.LEGGINGS
        Slot.CHESTPLATE -> EquipmentSlot.CHEST_PLATE
        else -> EquipmentSlot.HELMET
    }

    internal fun peItem(s: ItemSnapshot?): PeItemStack {
        val bukkit = ServerCompat.toItemStack(s) ?: return PeItemStack.EMPTY
        return try {
            SpigotConversionUtil.fromBukkitItemStack(bukkit)
        } catch (_: Throwable) {
            PeItemStack.EMPTY
        }
    }
}
