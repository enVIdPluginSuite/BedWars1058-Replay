package dev.etran.bedreplay.record

import com.github.retrooper.packetevents.PacketEvents
import dev.etran.bedreplay.bedwars.ArenaRef
import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.Anim
import dev.etran.bedreplay.format.Flag
import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.format.ReplayFooter
import dev.etran.bedreplay.format.ReplayMeta
import dev.etran.bedreplay.format.ReplayWriter
import dev.etran.bedreplay.format.Slot
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

/**
 * One recording of one BedWars match. All world access happens on the main thread in [tick];
 * packet-derived data arrives on Netty threads through the concurrent queues and is merged into
 * the next tick's frame.
 */
class RecordingSession(
    private val plugin: Plugin,
    private val config: PluginConfig,
    val arena: ArenaRef,
    val world: World,
    spoolDir: File,
) {
    val id: String = newId()
    val spoolFile = File(spoolDir, "$id.brep.part")
    private val startedAt = System.currentTimeMillis()

    private lateinit var meta: ReplayMeta
    private var writer: ReplayWriter? = null

    var tick = 0
        private set
    var winner: String? = null
    /** Tick at which the session should end (set after the game ends), or -1. */
    var endAtTick = -1

    @Volatile
    var closed = false
        private set

    // participants: stable replay id per player UUID. Read from Netty threads.
    private val replayIds = ConcurrentHashMap<UUID, Int>()
    private val inWorld = ConcurrentHashMap<UUID, Boolean>()
    private val states = LinkedHashMap<UUID, PState>()

    private val pending = ArrayList<Action>()
    private val chatQueue = ConcurrentLinkedQueue<ChatEntry>()
    private val blockQueue = ConcurrentLinkedQueue<BlockEntry>()
    private val blockShadow = HashMap<Long, String>()

    private val entities = HashMap<Int, EState>()
    private val seen = HashSet<Int>()
    private val scratch = Location(null, 0.0, 0.0, 0.0)

    private val titleQueue = ConcurrentLinkedQueue<TitleEntry>()
    private val soundQueue = ConcurrentLinkedQueue<SoundEntry>()
    private val soundSeen = HashSet<String>()
    private val openInvs = LinkedHashMap<org.bukkit.inventory.Inventory, OpenInv>()

    private class TitleEntry(val player: UUID, val kind: Int, val text: String, val a: Int, val b: Int, val c: Int)
    private class SoundEntry(
        val name: String, val x: Double, val y: Double, val z: Double,
        val volume: Float, val pitch: Float, val entityId: Int,
    )
    private class ChatEntry(val player: UUID, val text: String)
    private class BlockEntry(val x: Int, val y: Int, val z: Int, val state: String)

    private class PState(val uuid: UUID, val id: Int) {
        var spawned = false
        var dyingUntil = -1
        var qx = 0; var qy = 0; var qz = 0
        var yaw = 0; var pitch = 0
        var ground = false
        var lastX = 0.0; var lastY = 0.0; var lastZ = 0.0
        var flags = -1
        var held = -1
        var main: ItemStack? = null
        var off: ItemStack? = null
        val hotbar = arrayOfNulls<ItemStack>(9)
        val armor = arrayOfNulls<ItemStack>(4) // boots, leggings, chestplate, helmet
        var health = -1f
        var food = -1
        var level = -1
        var board: String? = null
        var tag: String? = null
        val inv = arrayOfNulls<ItemStack>(41)
        var invSent = false

        fun forceResend() {
            flags = -1; held = -1; main = null; off = null; health = -1f; food = -1; level = -1
            hotbar.fill(null); armor.fill(null)
            hotbarSent = false
        }
        var hotbarSent = false
    }

    private class OpenInv(val inventory: org.bukkit.inventory.Inventory, val type: String, val x: Int, val y: Int, val z: Int, val owner: Int) {
        val viewers = HashSet<UUID>()
        var sig: String? = null
    }

    private class EState(val rid: Int, val kind: String) {
        var dead = false
        var flags = -1
        val equip = arrayOfNulls<ItemStack>(5) // boots, leggings, chestplate, helmet, hand
        var equipSent = false
        val meta = HashMap<String, String>()
        var qx = Int.MIN_VALUE; var qy = Int.MIN_VALUE; var qz = Int.MIN_VALUE
        var yaw = -1; var pitch = -1
        var x = 0.0; var y = 0.0; var z = 0.0
    }

    // ------------------------------------------------------------------ lifecycle

    /** Writes the header and starts tracking everyone currently in the arena. Returns false if nothing to record. */
    fun start(): Boolean {
        val players = arena.players
        if (players.isEmpty()) return false
        val infos = ArrayList<PlayerInfo>()
        for ((i, p) in players.withIndex()) {
            val rid = i + 1
            val team = arena.teamOf(p)
            val (skin, sig) = skinOf(p)
            infos += PlayerInfo(rid, p.uniqueId.toString(), p.name, team?.name, team?.rgb ?: -1, skin, sig)
            replayIds[p.uniqueId] = rid
            inWorld[p.uniqueId] = true
            states[p.uniqueId] = PState(p.uniqueId, rid)
        }
        meta = ReplayMeta(
            id = id,
            arena = arena.name,
            mapName = ChatColor.stripColor(arena.displayName) ?: arena.name,
            group = arena.group,
            mcVersion = ServerCompat.version,
            startedAtMillis = startedAt,
            players = infos,
        )
        spoolFile.parentFile.mkdirs()
        writer = ReplayWriter(FileOutputStream(spoolFile), meta)
        // BedWars removes the beds of teams nobody joined before the game begins. That happens before this
        // recording exists, so write the removals explicitly or the replay map would still show those beds.
        for (team in arena.teams) {
            if (team.members.isNotEmpty()) continue
            team.bed?.let { queueBlock(it.blockX, it.blockY, it.blockZ, BlockCodec.AIR) }
        }
        return true
    }

    /**
     * The player's skin as (value, signature). Tried in order: Paper's profile API, the CraftPlayer game profile
     * (works on every Spigot / Paper version), then PacketEvents' copy of the login profile, which is often empty.
     */
    private fun skinOf(p: Player): Pair<String?, String?> {
        SkinLookup.paper(p)?.let { return it }
        SkinLookup.gameProfile(p)?.let { return it }
        return try {
            val user = PacketEvents.getAPI().playerManager.getUser(p)
            val tex = user?.profile?.textureProperties?.firstOrNull { it.name == "textures" }
            if (tex?.value != null) tex.value to tex.signature else null to null
        } catch (_: Throwable) {
            null to null
        }
    }

    fun isParticipant(uuid: UUID) = replayIds.containsKey(uuid)
    fun wantsPackets(uuid: UUID) = !closed && inWorld[uuid] == true

    // ------------------------------------------------------------------ feeders

    fun queueChat(player: UUID, text: String) { if (!closed) chatQueue.add(ChatEntry(player, text)) }
    fun queueBlock(x: Int, y: Int, z: Int, state: String) { if (!closed) blockQueue.add(BlockEntry(x, y, z, state)) }

    fun queueTitle(player: UUID, kind: Int, text: String, a: Int, b: Int, c: Int) {
        if (!closed && config.recordTitles) titleQueue.add(TitleEntry(player, kind, text, a, b, c))
    }

    fun queueSound(name: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float, entityId: Int) {
        if (!closed && config.recordSounds) soundQueue.add(SoundEntry(name, x, y, z, volume, pitch, entityId))
    }

    fun marker(kind: Int, label: String, subject: UUID? = null) {
        if (closed) return
        pending += Action.Marker(kind, label, subject?.let { replayIds[it] } ?: -1)
    }

    fun animation(player: UUID, anim: Int) {
        val id = replayIds[player] ?: return
        if (!closed && states[player]?.spawned == true) pending += Action.Animation(id, anim)
    }

    /** A player was killed: show the death animation and keep the body for a second before removing it. */
    fun death(player: UUID) {
        val st = states[player] ?: return
        if (closed || !st.spawned) return
        pending += Action.Animation(st.id, Anim.DEATH)
        st.dyingUntil = tick + 20
    }

    /** Blocks removed by an explosion (no packet is sent for those on most versions). */
    fun explosion(blocks: List<org.bukkit.block.Block>) {
        if (closed) return
        for (b in blocks) queueBlock(b.x, b.y, b.z, BlockCodec.AIR)
    }

    private class Watch(val x: Int, val y: Int, val z: Int, val before: String?, val checkAt: Int)
    private val watches = ArrayList<Watch>()

    /**
     * Blocks that something is about to change without telling clients: a dragon eating through walls, a wither,
     * an explosion another plugin cancels and carries out itself. Their state is remembered now and compared again a
     * moment later; whatever differs is recorded, whoever did it.
     */
    fun watchBlocks(blocks: Collection<org.bukkit.block.Block>) {
        if (closed || !config.recordBlocks || blocks.isEmpty()) return
        val first = tick + 1
        for (b in blocks) {
            if (b.world.uid != world.uid) continue
            val before = BlockCodec.current(world, b.x, b.y, b.z)
            // checked twice: right away, and again once chained effects (falling gravel, fluids) have settled
            watches += Watch(b.x, b.y, b.z, before, first)
            watches += Watch(b.x, b.y, b.z, before, first + 4)
        }
    }

    private fun checkWatches() {
        if (watches.isEmpty()) return
        val it = watches.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (w.checkAt > tick) continue
            it.remove()
            val now = BlockCodec.current(world, w.x, w.y, w.z) ?: continue
            if (now != w.before) queueBlock(w.x, w.y, w.z, now)
        }
    }

    // ------------------------------------------------------------------ per-tick work

    fun tick() {
        val w = writer ?: return
        if (closed) return
        tick++
        val actions = ArrayList<Action>(48)
        try {
            val spectators = arena.spectators.mapTo(HashSet()) { it.uniqueId }
            val visible = ArrayList<PState>(states.size)
            for (st in states.values) if (trackPlayer(st, spectators, actions)) visible += st
            if (config.recordEntities) scanEntities(visible, actions)
            checkWatches()
            drainBlocks(actions)
            drainChat(actions)
            drainTitles(actions)
            drainSounds(actions)
            if (config.recordScoreboards && tick % BOARD_INTERVAL == 0) trackBoards(actions)
            if (config.recordInventories && tick % INVENTORY_INTERVAL == 0) {
                trackInventories(actions)
                trackContainers(actions)
            }
            actions += pending
            pending.clear()
            w.writeFrame(tick, actions)
            if (tick % (config.flushIntervalSeconds * 20) == 0) w.flush()
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Recording tick failed for $id", t)
        }
    }

    /** Returns true if the player is currently visible in the match. */
    private fun trackPlayer(st: PState, spectators: Set<UUID>, out: MutableList<Action>): Boolean {
        val p = plugin.server.getPlayer(st.uuid)
        val inArena = p != null && p.isOnline && p.world.uid == world.uid
        inWorld[st.uuid] = inArena
        val visible = inArena && !p!!.isDead && st.uuid !in spectators && !arena.isRespawning(p)

        if (!visible) {
            if (st.spawned && tick >= st.dyingUntil) {
                out += Action.Remove(st.id)
                st.spawned = false
                st.dyingUntil = -1
            }
            return false
        }
        p!!
        val loc = p.location
        if (!st.spawned) {
            out += Action.Spawn(st.id, "player", loc.x, loc.y, loc.z, loc.yaw, loc.pitch)
            st.spawned = true
            st.dyingUntil = -1
            st.forceResend()
            st.qx = quant(loc.x); st.qy = quant(loc.y); st.qz = quant(loc.z)
            st.yaw = angle(loc.yaw); st.pitch = angle(loc.pitch)
        } else {
            val qx = quant(loc.x); val qy = quant(loc.y); val qz = quant(loc.z)
            val yaw = angle(loc.yaw); val pitch = angle(loc.pitch)
            @Suppress("DEPRECATION") val ground = p.isOnGround
            if (qx != st.qx || qy != st.qy || qz != st.qz || yaw != st.yaw || pitch != st.pitch || ground != st.ground) {
                out += Action.Move(st.id, loc.x, loc.y, loc.z, loc.yaw, loc.pitch, ground)
                st.qx = qx; st.qy = qy; st.qz = qz; st.yaw = yaw; st.pitch = pitch; st.ground = ground
            }
        }
        st.lastX = loc.x; st.lastY = loc.y; st.lastZ = loc.z

        var flags = 0
        if (p.isSneaking) flags = flags or Flag.SNEAKING
        if (p.isSprinting) flags = flags or Flag.SPRINTING
        if (isInvisible(p)) flags = flags or Flag.INVISIBLE
        if (p.fireTicks > 0) flags = flags or Flag.BURNING
        if (p.isBlocking) flags = flags or Flag.BLOCKING
        if (ServerCompat.gliding(p)) flags = flags or Flag.GLIDING
        if (flags != st.flags) { out += Action.Flags(st.id, flags); st.flags = flags }

        trackEquipment(st, p, out)

        val health = (Math.round(p.health * 2.0) / 2.0).toFloat()
        val food = p.foodLevel
        val level = p.level
        if (health != st.health || food != st.food || level != st.level) {
            out += Action.Vitals(st.id, health, ServerCompat.maxHealth(p).toFloat(), food, level)
            st.health = health; st.food = food; st.level = level
        }
        return true
    }

    /**
     * Invisible players are still recorded at their real position every tick; the flag only tells playback
     * how to draw them. Covers the potion effect and the 1.16+ invisible state that other plugins can set.
     */
    private fun isInvisible(p: Player): Boolean {
        if (p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return true
        val m = invisibleMethod ?: return false
        return try { m.invoke(p) as? Boolean ?: false } catch (_: Throwable) { false }
    }

    private fun trackEquipment(st: PState, p: Player, out: MutableList<Action>) {
        val inv = p.inventory

        val main = ServerCompat.mainHand(p)
        if (!same(main, st.main)) { out += Action.Equip(st.id, Slot.MAIN_HAND, ServerCompat.snapshot(main)); st.main = main?.clone() }
        val off = ServerCompat.offHand(p)
        if (!same(off, st.off)) { out += Action.Equip(st.id, Slot.OFF_HAND, ServerCompat.snapshot(off)); st.off = off?.clone() }

        val armor = arrayOf(inv.boots, inv.leggings, inv.chestplate, inv.helmet)
        for (i in 0 until 4) {
            if (!same(armor[i], st.armor[i])) {
                out += Action.Equip(st.id, Slot.BOOTS + i, ServerCompat.snapshot(armor[i]))
                st.armor[i] = armor[i]?.clone()
            }
        }

        val held = inv.heldItemSlot
        var hotbarChanged = !st.hotbarSent || held != st.held
        for (i in 0 until 9) {
            val item = inv.getItem(i)
            if (!same(item, st.hotbar[i])) { hotbarChanged = true; st.hotbar[i] = item?.clone() }
        }
        if (hotbarChanged) {
            out += Action.Hotbar(st.id, held, st.hotbar.map { ServerCompat.snapshot(it) })
            st.held = held
            st.hotbarSent = true
        }
    }

    private fun same(a: ItemStack?, b: ItemStack?): Boolean {
        val ea = a == null || a.type == org.bukkit.Material.AIR
        val eb = b == null || b.type == org.bukkit.Material.AIR
        return if (ea || eb) ea == eb else a == b
    }

    // ------------------------------------------------------------------ entities

    private fun scanEntities(visible: List<PState>, out: MutableList<Action>) {
        seen.clear()
        for (e in world.entities) {
            if (e is Player) continue
            if (e.isDead) {
                // Let a dying mob play its death animation instead of vanishing instantly.
                val dying = entities[e.entityId]
                if (dying != null && e is LivingEntity) {
                    seen += e.entityId
                    if (!dying.dead) { dying.dead = true; out += Action.Animation(dying.rid, Anim.DEATH) }
                }
                continue
            }
            val kind = kindOf(e) ?: continue
            val live = e.entityId
            seen += live
            val loc = e.getLocation(scratch)
            val qx = quant(loc.x); val qy = quant(loc.y); val qz = quant(loc.z)
            val yaw = angle(loc.yaw); val pitch = angle(loc.pitch)
            var st = entities[live]
            if (st == null) {
                st = EState(ENTITY_BASE + live, kind)
                entities[live] = st
                val (item, extra) = payloadOf(e)
                out += Action.Spawn(st.rid, kind, loc.x, loc.y, loc.z, loc.yaw, loc.pitch, item, extra)
            } else if (qx != st.qx || qy != st.qy || qz != st.qz || yaw != st.yaw || pitch != st.pitch) {
                out += Action.Move(st.rid, loc.x, loc.y, loc.z, loc.yaw, loc.pitch, false)
            }
            st.qx = qx; st.qy = qy; st.qz = qz; st.yaw = yaw; st.pitch = pitch
            st.x = loc.x; st.y = loc.y; st.z = loc.z
            if (e is LivingEntity && (!st.equipSent || tick % LIVING_INTERVAL == 0)) trackLiving(e, st, out)
        }
        if (entities.size > seen.size) {
            val iter = entities.entries.iterator()
            while (iter.hasNext()) {
                val (live, st) = iter.next()
                if (live in seen) continue
                // An item that vanished right next to a player was almost certainly picked up.
                if (st.kind == "item") {
                    val collector = visible.minByOrNull { dist2(it.lastX, it.lastY, it.lastZ, st) }
                    if (collector != null && dist2(collector.lastX, collector.lastY, collector.lastZ, st) < PICKUP_RANGE2) {
                        out += Action.Pickup(st.rid, collector.id)
                    }
                }
                out += Action.Remove(st.rid)
                iter.remove()
            }
        }
    }

    private fun dist2(x: Double, y: Double, z: Double, st: EState): Double {
        val dx = x - st.x; val dy = y - st.y; val dz = z - st.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun payloadOf(e: Entity): Pair<dev.etran.bedreplay.format.ItemSnapshot?, String?> = when (e) {
        is Item -> ServerCompat.snapshot(e.itemStack) to null
        // FallingBlock#getBlockData() returns byte on 1.8 but BlockData on 1.13+, so only use getMaterial().
        is FallingBlock -> null to e.material.name
        else -> null to null
    }

    /** Entity kind as a modern lower-case id, or null if the entity should not be recorded. */
    private fun kindOf(e: Entity): String? {
        val type = e.type.name
        if (type in SKIPPED) return null
        // Invisible armour stands are BedWars holograms/name tags; without their metadata they would
        // replay as visible empty stands, so leave them out.
        if (e is ArmorStand && !e.isVisible) return null
        return LEGACY_KINDS[type] ?: type.lowercase()
    }

    // ------------------------------------------------------------------ packet-derived data

    private fun drainBlocks(out: MutableList<Action>) {
        if (blockQueue.isEmpty()) return
        val latest = LinkedHashMap<Long, BlockEntry>()
        while (true) {
            val b = blockQueue.poll() ?: break
            latest[pack(b.x, b.y, b.z)] = b
        }
        for ((key, b) in latest) {
            // skip no-ops: the same state re-sent (client correction, several viewers, ...)
            if (blockShadow[key] == b.state) continue
            blockShadow[key] = b.state
            out += Action.BlockChange(b.x, b.y, b.z, b.state, "")
        }
    }

    private fun drainChat(out: MutableList<Action>) {
        if (chatQueue.isEmpty()) return
        val byText = LinkedHashMap<String, MutableSet<Int>>()
        while (true) {
            val c = chatQueue.poll() ?: break
            val rid = replayIds[c.player] ?: continue
            byText.getOrPut(c.text) { java.util.TreeSet() } += rid
        }
        val everyone = replayIds.size
        for ((text, ids) in byText) {
            // Seen by everybody in the match -> store as "all" so it shows in the global view.
            out += Action.Chat(text, if (ids.size >= everyone) null else ids.toIntArray())
        }
    }

    /** Mob appearance: burning / invisible flags, held item and armor, custom name and a few body properties. */
    private fun trackLiving(e: LivingEntity, st: EState, out: MutableList<Action>) {
        var flags = 0
        if (e.fireTicks > 0) flags = flags or Flag.BURNING
        if (e.hasPotionEffect(PotionEffectType.INVISIBILITY)) flags = flags or Flag.INVISIBLE
        if (flags != st.flags) {
            if (st.flags != -1 || flags != 0) out += Action.Flags(st.rid, flags)
            st.flags = flags
        }

        val eq = e.equipment
        if (eq != null) {
            val now = arrayOf<ItemStack?>(eq.boots, eq.leggings, eq.chestplate, eq.helmet, handItem(eq))
            for (i in 0 until 5) {
                if (!same(now[i], st.equip[i]) || (!st.equipSent && now[i] != null)) {
                    val slot = if (i == 4) Slot.MAIN_HAND else Slot.BOOTS + i
                    out += Action.Equip(st.rid, slot, ServerCompat.snapshot(now[i]))
                    st.equip[i] = now[i]?.clone()
                }
            }
        }
        st.equipSent = true

        fun meta(key: String, value: String?) {
            if (value == null) { if (st.meta.remove(key) != null) out += Action.EntityMeta(st.rid, key, "") ; return }
            if (st.meta[key] != value) { st.meta[key] = value; out += Action.EntityMeta(st.rid, key, value) }
        }
        meta("name", e.customName?.takeIf { it.isNotBlank() })
        meta("name_visible", if (e.customName != null) e.isCustomNameVisible.toString() else null)
        if (e is org.bukkit.entity.Ageable && !e.isAdult) meta("baby", "true") else meta("baby", null)
        if (e is org.bukkit.entity.Slime) meta("size", e.size.toString())
        if (e is org.bukkit.entity.Sheep) meta("color", e.color?.name)
    }

    private fun handItem(eq: org.bukkit.inventory.EntityEquipment): ItemStack? {
        val m = hand ?: return null
        return try { m.invoke(eq) as? ItemStack } catch (_: Throwable) { null }
    }

    // ------------------------------------------------------------------ inventories and containers

    /** Full inventory (41 slots) of every participant, written only when something in it changed. */
    private fun trackInventories(out: MutableList<Action>) {
        for (st in states.values) {
            val p = plugin.server.getPlayer(st.uuid) ?: continue
            if (p.world.uid != world.uid) continue
            val inv = p.inventory
            val now = arrayOfNulls<ItemStack>(41)
            for (i in 0 until 36) now[i] = inv.getItem(i)
            now[36] = inv.boots; now[37] = inv.leggings; now[38] = inv.chestplate; now[39] = inv.helmet
            now[40] = ServerCompat.offHand(p)
            var changed = !st.invSent
            for (i in 0 until 41) if (!same(now[i], st.inv[i])) { changed = true; st.inv[i] = now[i]?.clone() }
            if (changed) {
                out += Action.Inventory(st.id, now.map { ServerCompat.snapshot(it) })
                st.invSent = true
            }
        }
    }

    /** A participant opened [inv]. Chests, furnaces, hoppers and the like are tracked until closed. */
    fun containerOpened(player: UUID, inv: org.bukkit.inventory.Inventory) {
        if (closed || !config.recordInventories) return
        val existing = openInvs[inv]
        if (existing != null) { existing.viewers += player; return }
        val type = inv.type.name
        val holder = try { inv.holder } catch (_: Throwable) { null }
        val loc: Location? = when (holder) {
            is org.bukkit.block.BlockState -> holder.location
            is org.bukkit.block.DoubleChest -> holder.location
            else -> null
        }
        val owner = if (type == "ENDER_CHEST") replayIds[player] ?: -1 else -1
        if (loc == null && type != "ENDER_CHEST") return // crafting grids, the player's own inventory, shops ...
        val open = OpenInv(inv, type, loc?.blockX ?: 0, loc?.blockY ?: 0, loc?.blockZ ?: 0, owner)
        open.viewers += player
        openInvs[inv] = open
    }

    fun containerClosed(player: UUID, inv: org.bukkit.inventory.Inventory) {
        val open = openInvs[inv] ?: return
        open.viewers -= player
        if (open.viewers.isEmpty()) {
            val last = ArrayList<Action>(1)
            emitContainer(open, last)
            pending += last
            openInvs.remove(inv)
        }
    }

    private fun trackContainers(out: MutableList<Action>, final: Boolean = false) {
        if (openInvs.isEmpty()) return
        for (open in openInvs.values) emitContainer(open, out)
        if (final) openInvs.clear()
    }

    private fun emitContainer(open: OpenInv, out: MutableList<Action>) {
        val items = open.inventory.contents.map { ServerCompat.snapshot(it) }
        val sig = items.joinToString("|") { it?.let { s -> "${s.material}:${s.durability}x${s.amount}${s.displayName ?: ""}" } ?: "" }
        if (sig == open.sig) return
        open.sig = sig
        out += Action.Container(open.x, open.y, open.z, open.type, open.owner, items)
    }

    /** Hurt animation for a mob (players are handled separately). */
    fun entityHurt(liveId: Int) {
        val st = entities[liveId] ?: return
        if (!closed) pending += Action.Animation(st.rid, Anim.HURT)
    }

    private fun drainTitles(out: MutableList<Action>) {
        if (titleQueue.isEmpty()) return
        class Key(val kind: Int, val text: String, val a: Int, val b: Int, val c: Int) {
            override fun equals(other: Any?) = other is Key && kind == other.kind && text == other.text &&
                a == other.a && b == other.b && c == other.c
            override fun hashCode() = ((kind * 31 + text.hashCode()) * 31 + a) * 31 + b * 7 + c
        }
        val grouped = LinkedHashMap<Key, MutableSet<Int>>()
        while (true) {
            val t = titleQueue.poll() ?: break
            val rid = replayIds[t.player] ?: continue
            grouped.getOrPut(Key(t.kind, t.text, t.a, t.b, t.c)) { java.util.TreeSet() } += rid
        }
        for ((k, ids) in grouped) {
            out += Action.Title(k.kind, k.text, k.a, k.b, k.c, if (ids.size >= replayIds.size) null else ids.toIntArray())
        }
    }

    private fun drainSounds(out: MutableList<Action>) {
        if (soundQueue.isEmpty()) return
        soundSeen.clear()
        while (true) {
            val s = soundQueue.poll() ?: break
            var x = s.x; var y = s.y; var z = s.z
            if (s.entityId >= 0) {
                // Entity sounds: find where that entity is (a participant or a tracked entity).
                val ent = entities[s.entityId]
                val player = states.values.firstNotNullOfOrNull { st ->
                    plugin.server.getPlayer(st.uuid)?.takeIf { it.entityId == s.entityId }
                }
                if (player != null) { x = player.location.x; y = player.location.y; z = player.location.z }
                else if (ent != null) { x = ent.x; y = ent.y; z = ent.z }
                else continue
            }
            // Every nearby participant is sent the same packet; keep one copy.
            val key = "${s.name}|${quant(x)}|${quant(y)}|${quant(z)}|${s.volume}|${s.pitch}"
            if (!soundSeen.add(key)) continue
            out += Action.Sound(s.name, x, y, z, s.volume, s.pitch)
        }
    }

    /**
     * Sidebar scoreboard and name tag (team prefix / suffix / hidden) of every participant, sampled twice a
     * second and written only when something changed.
     */
    private fun trackBoards(out: MutableList<Action>) {
        for (st in states.values) {
            val p = plugin.server.getPlayer(st.uuid) ?: continue
            if (p.world.uid != world.uid) continue
            val tracker = ScoreboardRegistry.peek(st.uuid)
            val packetBased = tracker != null && tracker.seenSidebarPackets

            var title: String? = null
            var lines: List<String> = emptyList()
            var tag: Action.Nametag? = null

            if (packetBased) {
                val view = tracker!!.sidebar()
                if (view != null) { title = view.title; lines = view.lines }
                tracker.tag(p.name)?.let { tag = Action.Nametag(st.id, it.prefix, it.suffix, it.hidden) }
            } else {
                // Plugins that use real Bukkit scoreboards.
                val board = try { p.scoreboard } catch (_: Throwable) { null } ?: continue
                val teamOf = HashMap<String, org.bukkit.scoreboard.Team>()
                for (t in board.teams) for (e in t.entries) teamOf[e] = t
                val objective = board.getObjective(org.bukkit.scoreboard.DisplaySlot.SIDEBAR)
                if (objective != null) {
                    val rows = ArrayList<Pair<Int, String>>()
                    for (entry in board.entries) {
                        val score = objective.getScore(entry)
                        if (!score.isScoreSet) continue
                        val team = teamOf[entry]
                        rows += score.score to ((team?.prefix ?: "") + entry + (team?.suffix ?: ""))
                    }
                    rows.sortByDescending { it.first }
                    title = objective.displayName
                    lines = rows.take(15).map { it.second }
                }
                val team = teamOf[p.name]
                if (team != null) {
                    val hidden = try { team.nameTagVisibility == org.bukkit.scoreboard.NameTagVisibility.NEVER } catch (_: Throwable) { false }
                    tag = Action.Nametag(st.id, team.prefix ?: "", team.suffix ?: "", hidden)
                }
            }

            val sig = title + "\u0000" + lines.joinToString("\u0001")
            if (sig != st.board) { st.board = sig; out += Action.Scoreboard(st.id, title, lines) }

            val t = tag
            if (t != null) {
                val tagSig = "${t.prefix}\u0000${t.suffix}\u0000${t.hidden}"
                if (tagSig != st.tag) { st.tag = tagSig; out += t }
            }
        }
    }

    // ------------------------------------------------------------------ finishing

    /**
     * Closes the file. Returns the finished metadata, or null if the recording was too short and was discarded.
     */
    fun finish(): ReplayMeta? {
        if (closed) return null
        val w = writer
        // flush anything still queued into a last frame
        try {
            val last = ArrayList<Action>()
            drainBlocks(last); drainChat(last); drainTitles(last); drainSounds(last); trackContainers(last, final = true); last += pending; pending.clear()
            if (last.isNotEmpty()) w?.writeFrame(tick.coerceAtLeast(1), last)
        } catch (_: Throwable) {
        }
        closed = true
        val ended = System.currentTimeMillis()
        val keep = tick >= config.minDurationSeconds * 20
        if (w == null) return null
        return if (keep) {
            w.finish(ReplayFooter(tick, ended, winner))
            meta.copy(durationTicks = tick, endedAtMillis = ended, winnerTeam = winner)
        } else {
            w.close()
            spoolFile.delete()
            null
        }
    }

    companion object {
        private const val ENTITY_BASE = 1000
        private const val BOARD_INTERVAL = 5
        private const val INVENTORY_INTERVAL = 5
        private const val LIVING_INTERVAL = 5

        /** EntityEquipment#getItemInMainHand (1.9+) or getItemInHand (1.8); we compile against 1.8. */
        private val hand: java.lang.reflect.Method? = try {
            org.bukkit.inventory.EntityEquipment::class.java.getMethod("getItemInMainHand")
        } catch (_: Throwable) {
            try { org.bukkit.inventory.EntityEquipment::class.java.getMethod("getItemInHand") } catch (_: Throwable) { null }
        }

        /** Player#isInvisible exists from 1.16.3; resolved once, reflectively, because we compile against 1.8. */
        private val invisibleMethod: java.lang.reflect.Method? =
            try { Player::class.java.getMethod("isInvisible") } catch (_: Throwable) { null }
        private const val PICKUP_RANGE2 = 3.0 * 3.0

        private val SKIPPED = setOf(
            "PLAYER", "LIGHTNING", "LIGHTNING_BOLT", "WEATHER", "COMPLEX_PART", "UNKNOWN",
            "AREA_EFFECT_CLOUD",
        )

        /** Pre-1.13 / 1.20.5 names mapped to the modern ids PacketEvents understands. */
        private val LEGACY_KINDS = mapOf(
            "DROPPED_ITEM" to "item",
            "PRIMED_TNT" to "tnt",
            "THROWN_EXP_BOTTLE" to "experience_bottle",
            "SPLASH_POTION" to "potion",
            "FIREWORK" to "firework_rocket",
            "MINECART_CHEST" to "chest_minecart",
            "FISHING_HOOK" to "fishing_bobber",
        )

        fun quant(v: Double): Int = Math.round(v * 32.0).toInt()
        fun angle(deg: Float): Int = Math.round(deg * 256f / 360f) and 0xFF
        private fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        private fun newId(): String {
            val rnd = UUID.randomUUID().toString().replace("-", "").take(4)
            return java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + rnd
        }
    }
}
