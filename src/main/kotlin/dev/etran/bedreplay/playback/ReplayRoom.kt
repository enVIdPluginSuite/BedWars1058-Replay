package dev.etran.bedreplay.playback

import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.format.Replay
import dev.etran.bedreplay.record.BlockCodec
import dev.etran.bedreplay.settings.PlayerSettings
import dev.etran.bedreplay.storage.StoredReplay
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import java.util.logging.Level

/**
 * One replay being watched in one private copy of the map, by a host and any number of guests.
 *
 * The room owns the timeline (play/pause, speed, seeking), the simulated entities and the map's blocks. Everything
 * that depends on *who is looking* (following a player, chat view, sidebar, camera) lives in each member's
 * [PlaybackSession]. Entities are packet-only and share ids, so adding a viewer costs one extra set of packets.
 */
class ReplayRoom(
    private val plugin: Plugin,
    val config: PluginConfig,
    val stored: StoredReplay,
    private val replay: Replay,
    val world: World,
    private val stateDir: File,
    private val settingsOf: (UUID) -> PlayerSettings,
    private val onEnded: (ReplayRoom) -> Unit,
) {
    private val frames = replay.frames
    val durationTicks: Int = maxOf(replay.meta.durationTicks, frames.lastOrNull()?.tick ?: 0)
    val players: Map<Int, PlayerInfo> = replay.meta.players.associateBy { it.entityId }
    val roster: List<PlayerInfo> get() = replay.meta.players
    val mapName: String get() = replay.meta.mapName

    val members = LinkedHashMap<UUID, PlaybackSession>()
    lateinit var host: PlaybackSession
        private set

    /** Settings of whoever hosts right now; they decide who may join and whether guests may save. */
    val hostSettings: PlayerSettings get() = settingsOf(host.viewer.uniqueId)

    private val renderer = NpcRenderer(plugin, { members.values.map { it.viewer } }, players, healthMode = config.healthDisplay)
    internal val renderers get() = renderer

    // shared state rebuilt on every seek
    internal val boards = HashMap<Int, Action.Scoreboard>()
    private val tags = HashMap<Int, Action.Nametag>()
    private val inventories = HashMap<Int, Action.Inventory>()
    private val containers = LinkedHashMap<String, Action.Container>()
    private val entMeta = HashMap<Int, HashMap<String, String>>()
    private val sim = LinkedHashMap<Int, Ent>()
    private val dirty = LinkedHashSet<Ent>()

    // timeline
    private var frameIdx = 0
    var cursor = 0
        private set
    private var playPos = 0.0
    var paused = false
        private set
    private var speedIdx = 0
    val speed: Double get() = config.speeds[speedIdx]
    val markers: List<Pair<Int, Action.Marker>>

    // blocks
    private val prevState = IdentityHashMap<Action.BlockChange, String?>()
    private val origin = HashMap<Long, String>()
    private val setupBlocks: MutableSet<Action.BlockChange> = java.util.Collections.newSetFromMap(IdentityHashMap())

    private var task: BukkitTask? = null
    private var counter = 0
    private var errors = 0
    private var started = false
    @Volatile var ended = false
        private set

    init {
        val shadow = HashMap<Long, String>()
        val found = ArrayList<Pair<Int, Action.Marker>>()
        for (f in frames) for (a in f.actions) {
            when (a) {
                is Action.BlockChange -> {
                    val k = pack(a.x, a.y, a.z)
                    prevState[a] = shadow[k]
                    shadow[k] = a.block
                }
                is Action.Marker -> found += f.tick to a
                else -> {}
            }
        }
        markers = found
        speedIdx = config.speeds.indices.minByOrNull { Math.abs(config.speeds[it] - config.defaultSpeed) } ?: 0
    }

    // ------------------------------------------------------------------ membership

    fun start(first: PlaybackSession) {
        host = first
        members[first.viewer.uniqueId] = first
        applySetupBlocks()
        first.enter(firstSpawn())
        // The client needs a moment to finish switching worlds before it can accept entity packets.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (ended || !first.viewer.isOnline) return@Runnable
            first.ensureFlight(force = true)
            started = true
            seek(0)
            task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { onTick() }, 1L, 1L)
            first.viewer.sendMessage(config.msg("now-playing", "map" to mapName))
        }, 12L)
    }

    fun canAdd() = !ended && members.size < config.sharingMaxViewers

    /** Brings a guest into the room, next to the host. */
    fun join(guest: PlaybackSession) {
        // People already spectating are invisible to everyone else, newcomers included.
        for (m in members.values) if (m.cameraMode) dev.etran.bedreplay.util.Visibility.set(plugin, guest.viewer, m.viewer, false)
        members[guest.viewer.uniqueId] = guest
        val at = host.viewer.location.clone().also { it.world = world }
        guest.enter(at)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (ended || guest.ended || !guest.viewer.isOnline) return@Runnable
            guest.ensureFlight(force = true)
            renderer.spawnFor(guest.viewer, sim.values)
            guest.onJoinedRoom()
            guest.viewer.sendMessage(config.msg("joined", "host" to host.viewer.name, "map" to mapName))
        }, 12L)
        for (m in members.values) if (m !== guest) m.viewer.sendMessage(config.msg("guest-joined", "player" to guest.viewer.name))
        refreshControls()
    }

    /** A member leaves (or is removed). The room closes when the last one goes; a host who leaves hands over. */
    fun leave(m: PlaybackSession, restore: Boolean) {
        if (m.ended) return
        m.releaseSpectate()
        m.ended = true
        members.remove(m.viewer.uniqueId)
        try {
            if (m.cameraMode) renderer.camera(m.viewer, null)
            if (m.viewer.isOnline) renderer.despawnFor(m.viewer, sim.values)
        } catch (_: Throwable) {
        }
        m.hud.close()
        if (restore) m.restore(stateDir)

        if (members.isEmpty()) { close(); return }
        if (m === host) {
            val next = members.values.first()
            host = next
            for (x in members.values) x.viewer.sendMessage(config.msg("host-left", "player" to next.viewer.name))
            next.viewer.sendMessage(config.msg("transfer-you"))
        } else {
            for (x in members.values) x.viewer.sendMessage(config.msg("guest-left", "player" to m.viewer.name))
        }
        refreshControls()
    }

    /** Hands the host role to another member. */
    fun transfer(to: PlaybackSession) {
        if (to === host || to.ended) return
        host = to
        for (m in members.values) m.viewer.sendMessage(config.msg("transfer-done", "player" to to.viewer.name))
        to.viewer.sendMessage(config.msg("transfer-you"))
        refreshControls()
    }

    fun refreshControls() { for (m in members.values) m.giveControls() }

    private fun close() {
        if (ended) return
        ended = true
        task?.cancel()
        sim.clear()
        // Give any teleports a moment to complete before the world is unloaded.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { PlaybackWorlds.destroy(plugin, world) }, 5L)
        onEnded(this)
    }

    /** Ends the room for everybody (server shutdown, fatal error). */
    fun shutdown(restore: Boolean = true) {
        for (m in ArrayList(members.values)) leave(m, restore)
        close()
    }

    private fun firstSpawn(): Location {
        for (f in frames) for (a in f.actions) {
            if (a is Action.Spawn && a.kind == "player") return Location(world, a.x, a.y + 4.0, a.z, a.yaw, 25f)
        }
        return world.spawnLocation.add(0.0, 5.0, 0.0)
    }

    // ------------------------------------------------------------------ ticking

    private fun onTick() {
        if (ended) return
        try {
            for (m in ArrayList(members.values)) {
                if (!m.viewer.isOnline || m.viewer.world.uid != world.uid) leave(m, restore = m.viewer.isOnline)
            }
            if (ended) return
            if (!paused) {
                playPos += speed
                val target = playPos.toInt().coerceAtMost(durationTicks)
                if (target > cursor) advance(target)
                if (cursor >= durationTicks) {
                    paused = true
                    refreshControls()
                    for (m in members.values) m.viewer.sendMessage(config.msg("playback-ended"))
                }
            }
            for (m in members.values) {
                if (counter % 10 == 0) m.hud.reassert()
                if (counter % 20 == 0) m.ensureFlight(force = false)
                if (m.cameraMode && counter % 20 == 0) m.keepNearFollowed()
                if (m.cameraMode) m.syncLook()
                if (m.cameraMode && counter % 10 == 0) {
                    if (config.spectateClientMode) m.spectateInfo() else renderer.showHeld(m.viewer, m.following?.let { sim[it] })
                }
            }
            if (++counter % 4 == 0) updateProgress()
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Playback error", t)
            if (++errors >= 5) {
                for (m in members.values) m.viewer.sendMessage(config.msg("load-failed"))
                shutdown()
            }
        }
    }

    private fun advance(target: Int) {
        while (frameIdx < frames.size && frames[frameIdx].tick <= target) {
            for (a in frames[frameIdx].actions) applyAction(a, render = true, blocks = true, events = true)
            frameIdx++
        }
        cursor = target
        for (e in dirty) renderer.teleport(e)
        dirty.clear()
    }

    private fun applyAction(a: Action, render: Boolean, blocks: Boolean, events: Boolean) {
        when (a) {
            is Action.Spawn -> {
                val e = Ent(a.id, a.kind)
                e.x = a.x; e.y = a.y; e.z = a.z; e.yaw = a.yaw; e.pitch = a.pitch
                e.item = a.item; e.extra = a.extra
                entMeta[a.id]?.let { m -> e.customName = m["name"]; e.nameVisible = m["name_visible"] == "true" }
                tags[a.id]?.let { t -> e.prefix = t.prefix; e.suffix = t.suffix; e.tagHidden = t.hidden }
                sim.put(a.id, e)?.let { old -> if (render) { renderer.remove(old); dirty.remove(old) } }
                if (render) {
                    renderer.spawn(e)
                    for (m in members.values) if (m.cameraMode && m.following == a.id) renderer.camera(m.viewer, e)
                }
            }
            is Action.Remove -> {
                val e = sim.remove(a.id) ?: return
                if (render) {
                    dirty.remove(e)
                    for (m in members.values) if (m.cameraMode && m.following == a.id) renderer.camera(m.viewer, null)
                    renderer.remove(e)
                }
            }
            is Action.Move -> sim[a.id]?.let { e ->
                e.x = a.x; e.y = a.y; e.z = a.z; e.yaw = a.yaw; e.pitch = a.pitch; e.onGround = a.onGround
                if (render) dirty += e
            }
            is Action.Flags -> sim[a.id]?.let { e -> e.flags = a.flags; if (render) renderer.flags(e) }
            is Action.Equip -> sim[a.id]?.let { e ->
                if (a.slot in e.equip.indices) {
                    e.equip[a.slot] = a.item
                    if (render) {
                        renderer.equip(e, a.slot)
                        if (a.slot == dev.etran.bedreplay.format.Slot.MAIN_HAND && !config.spectateClientMode)
                            for (m in members.values) if (m.cameraMode && m.following == a.id) renderer.showHeld(m.viewer, e)
                    }
                }
            }
            is Action.Animation -> if (render) sim[a.id]?.let { renderer.animate(it, a.anim) }
            is Action.Vitals -> sim[a.id]?.let { e ->
                val changed = Math.ceil(e.health.toDouble()) != Math.ceil(a.health.toDouble()) || e.maxHealth != a.maxHealth
                e.health = a.health; e.maxHealth = a.maxHealth; e.food = a.food; e.level = a.level
                if (render && changed) renderer.health(e)
            }
            is Action.Hotbar -> sim[a.id]?.let { e -> e.heldSlot = a.heldSlot; e.hotbar = a.items }
            is Action.BlockChange -> if (blocks) applyBlock(a)
            is Action.Chat -> if (events) for (m in members.values) m.deliverChat(a)
            is Action.Sound -> if (events && config.playSounds) for (m in members.values) m.playSound(a)
            is Action.Title -> if (events) for (m in members.values) if (m.sees(a.recipients)) m.hud.title(a.kind, a.text, a.fadeIn, a.stay, a.fadeOut)
            is Action.Scoreboard -> {
                boards[a.id] = a
                if (render) for (m in members.values) if (m.boardOwner() == a.id) m.showBoard()
            }
            is Action.Inventory -> inventories[a.id] = a
            is Action.Container -> containers[containerKey(a.x, a.y, a.z, a.owner)] = a
            is Action.EntityMeta -> {
                entMeta.getOrPut(a.id) { HashMap() }[a.key] = a.value
                if (a.key == "name" || a.key == "name_visible") sim[a.id]?.let { e ->
                    val m = entMeta[a.id]!!
                    e.customName = m["name"]?.takeIf { it.isNotEmpty() }
                    e.nameVisible = m["name_visible"] == "true"
                    if (render) renderer.customName(e)
                }
            }
            is Action.Nametag -> {
                tags[a.id] = a
                sim[a.id]?.let { e ->
                    e.prefix = a.prefix; e.suffix = a.suffix; e.tagHidden = a.hidden
                    if (render) renderer.nametag(e)
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ queries used by members and menus

    private fun containerKey(x: Int, y: Int, z: Int, owner: Int) = if (owner >= 0) "ender:$owner" else "$x,$y,$z"

    fun containerNear(x: Int, y: Int, z: Int): Action.Container? {
        containers[containerKey(x, y, z, -1)]?.let { return it }
        return containers.values.firstOrNull { c ->
            c.owner < 0 && Math.abs(c.x - x) <= 1 && Math.abs(c.y - y) <= 1 && Math.abs(c.z - z) <= 1 &&
                (Math.abs(c.x - x) + Math.abs(c.z - z)) <= 1
        }
    }

    fun inventoryOf(rid: Int): Action.Inventory? = inventories[rid]
    fun enderChestOf(rid: Int): Action.Container? = containers[containerKey(0, 0, 0, rid)]
    fun playerName(rid: Int): String = players[rid]?.name ?: "player"

    /** A timeline label with BedWars placeholders ({PlayerColor}{PlayerName} ...) filled in, also for older recordings. */
    fun markerLabel(m: Action.Marker): String {
        val subject = players[m.subjectId]
        val color = subject?.let { dev.etran.bedreplay.util.ChatPalette.code(it.teamColor) } ?: ""
        var t = m.label
            .replace("{PlayerColor}", color).replace("{PlayerName}", (subject?.name ?: "A player") + "§f")
            .replace("{TeamColor}", color).replace("{KillerColor}", "").replace("{KillerName}", "a player")
        t = t.replace(Regex("\\{[A-Za-z]+\\}"), "")
        return t.trim()
    }
    fun isAlive(rid: Int) = sim.containsKey(rid)
    fun entity(rid: Int): Ent? = sim[rid]
    fun entityByLive(liveId: Int): Ent? = sim.values.firstOrNull { it.spawned && (it.liveId == liveId || (it.standsShown && it.lineIds.contains(liveId))) }
    fun attachCamera(m: PlaybackSession, e: Ent?) = renderer.camera(m.viewer, e)

    // ------------------------------------------------------------------ blocks

    private fun isBed(m: org.bukkit.Material) = m.name.endsWith("_BED") || m.name == "BED_BLOCK" || m.name == "BED"

    /**
     * BedWars deletes the beds of teams with no players before the game starts. Apply those removals to the map once,
     * up front. A bed is two blocks and the recording only names one, so the other half is cleared too.
     */
    private fun applySetupBlocks() {
        for (f in frames) {
            if (f.tick > SETUP_TICKS) break
            for (a in f.actions) {
                if (a !is Action.BlockChange || !a.block.contains("air")) continue
                setupBlocks += a
                val here = world.getBlockAt(a.x, a.y, a.z)
                if (!isBed(here.type)) { BlockCodec.apply(world, a.x, a.y, a.z, a.block); continue }
                val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
                val same = dirs.map { world.getBlockAt(a.x + it[0], a.y, a.z + it[1]) }.filter { isBed(it.type) && it.type == here.type }
                BlockCodec.apply(world, a.x, a.y, a.z, a.block)
                same.firstOrNull()?.let { BlockCodec.apply(world, it.x, it.y, it.z, a.block) }
            }
        }
    }

    private fun applyBlock(a: Action.BlockChange) {
        if (a in setupBlocks) return
        val k = pack(a.x, a.y, a.z)
        if (!origin.containsKey(k)) origin[k] = BlockCodec.current(world, a.x, a.y, a.z) ?: ""
        BlockCodec.apply(world, a.x, a.y, a.z, a.block)
    }

    private fun revertBlock(a: Action.BlockChange) {
        if (a in setupBlocks) return
        val k = pack(a.x, a.y, a.z)
        val state = prevState[a] ?: origin[k]
        if (!state.isNullOrEmpty()) BlockCodec.apply(world, a.x, a.y, a.z, state)
    }

    // ------------------------------------------------------------------ timeline (host only; checked by PlaybackSession)

    private fun framesUpTo(tick: Int): Int {
        var lo = 0
        var hi = frames.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].tick <= tick) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun seek(target: Int) {
        val t = target.coerceIn(0, durationTicks)
        val newIdx = framesUpTo(t)

        if (newIdx > frameIdx) {
            for (i in frameIdx until newIdx) for (a in frames[i].actions) if (a is Action.BlockChange) applyBlock(a)
        } else if (newIdx < frameIdx) {
            for (i in frameIdx - 1 downTo newIdx) for (a in frames[i].actions.asReversed()) if (a is Action.BlockChange) revertBlock(a)
        }

        for (e in sim.values) renderer.remove(e)
        sim.clear()
        dirty.clear()
        boards.clear()
        tags.clear()
        inventories.clear()
        containers.clear()
        entMeta.clear()
        for (m in members.values) m.hud.clearTitles()
        for (i in 0 until newIdx) for (a in frames[i].actions) applyAction(a, render = false, blocks = false, events = false)
        for (e in sim.values) renderer.spawn(e)

        frameIdx = newIdx
        cursor = t
        playPos = t.toDouble()
        for (m in members.values) m.afterSeek()
        updateProgress()
    }

    fun skipSeconds(seconds: Int) = seek(cursor + seconds * 20)

    fun togglePause() {
        if (cursor >= durationTicks) seek(0)
        paused = !paused
        refreshControls()
    }

    fun changeSpeed(delta: Int) {
        speedIdx = (speedIdx + delta).coerceIn(0, config.speeds.lastIndex)
        refreshControls()
    }

    fun speedLabel(): String {
        val s = speed
        return if (s == Math.floor(s)) s.toInt().toString() else s.toString()
    }

    private fun updateProgress() {
        val exp = if (durationTicks == 0) 0f else (cursor.toFloat() / durationTicks).coerceIn(0f, 0.999f)
        for (m in members.values) { m.viewer.level = cursor / 20; m.viewer.exp = exp }
    }

    companion object {
        private const val SETUP_TICKS = 5

        private fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
    }
}
