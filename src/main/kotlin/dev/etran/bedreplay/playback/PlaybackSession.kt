package dev.etran.bedreplay.playback

import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.format.Replay
import dev.etran.bedreplay.gui.Items
import dev.etran.bedreplay.record.BlockCodec
import dev.etran.bedreplay.record.RecordingManager
import dev.etran.bedreplay.util.ServerCompat
import dev.etran.bedreplay.storage.StoredReplay
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.IdentityHashMap
import java.util.logging.Level

/**
 * One viewer watching one replay in a private copy of the map.
 *
 * The timeline is a tick cursor over the recorded frames. Playing forward applies frames as the
 * cursor passes them; seeking rebuilds entity state from the start (cheap, memory only) and
 * applies or reverts the block changes in between, so you can jump anywhere in either direction.
 */
class PlaybackSession(
    private val plugin: Plugin,
    val config: PluginConfig,
    val viewer: Player,
    val stored: StoredReplay,
    private val replay: Replay,
    val world: World,
    private val stateDir: File,
    private val onEnded: (PlaybackSession) -> Unit,
) {
    private val frames = replay.frames
    val durationTicks: Int = maxOf(replay.meta.durationTicks, frames.lastOrNull()?.tick ?: 0)
    private val players: Map<Int, PlayerInfo> = replay.meta.players.associateBy { it.entityId }

    private val renderer = NpcRenderer(plugin, viewer, players, ghostInvisible = config.showInvisibleAsGhost)
    private val hud = HudRenderer(plugin, viewer)
    private val boards = HashMap<Int, Action.Scoreboard>()
    private val tags = HashMap<Int, Action.Nametag>()
    private val inventories = HashMap<Int, Action.Inventory>()
    private val containers = LinkedHashMap<String, Action.Container>()
    private val entMeta = HashMap<Int, HashMap<String, String>>()
    private val sim = LinkedHashMap<Int, Ent>()
    private val dirty = LinkedHashSet<Ent>()

    // ---- timeline state
    private var frameIdx = 0
    var cursor = 0
        private set
    private var playPos = 0.0
    var paused = false
        private set
    private var speedIdx = 0

    // ---- following / chat view
    /** Replay id of the player the viewer is following, or null for the whole-match view. */
    var following: Int? = null
        private set
    private var cameraMode = false

    // ---- blocks
    private val prevState = IdentityHashMap<Action.BlockChange, String?>()
    private val origin = HashMap<Long, String>()

    val markers: List<Pair<Int, Action.Marker>>

    private var task: BukkitTask? = null
    private var saved: ViewerState? = null
    private var counter = 0
    private var errors = 0
    @Volatile var ended = false
        private set
    /** True while the viewer is typing a time into chat. */
    var awaitingTime = false

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

    val speed: Double get() = config.speeds[speedIdx]

    // ------------------------------------------------------------------ lifecycle

    fun begin(stateFile: File) {
        saved = ViewerState.capture(viewer).also { it.save(stateFile) }

        viewer.inventory.clear()
        viewer.inventory.armorContents = arrayOfNulls<ItemStack>(4)
        viewer.gameMode = GameMode.ADVENTURE
        viewer.allowFlight = true
        viewer.foodLevel = 20
        viewer.health = viewer.maxHealth

        val start = firstSpawn()
        viewer.teleport(start)
        // Changing world can reset flight (world-gamemode / multiworld plugins), so set it again afterwards.
        ensureFlight(force = true)
        giveControls()

        // The client needs a moment to finish switching worlds before it can accept entity packets.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (ended || !viewer.isOnline) return@Runnable
            ensureFlight(force = true)
            seek(0)
            task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { onTick() }, 1L, 1L)
            viewer.sendMessage(config.msg("now-playing", "map" to replay.meta.mapName))
        }, 12L)
    }

    /** Keeps the viewer able to fly around; other plugins may reset this when the world or game mode changes. */
    private fun ensureFlight(force: Boolean) {
        try {
            if (viewer.gameMode != GameMode.ADVENTURE) viewer.gameMode = GameMode.ADVENTURE
            if (!viewer.allowFlight) viewer.allowFlight = true
            if (force && viewer.allowFlight) viewer.isFlying = true
        } catch (_: Throwable) {
        }
    }

    private fun firstSpawn(): Location {
        for (f in frames) for (a in f.actions) {
            if (a is Action.Spawn && a.kind == "player") return Location(world, a.x, a.y + 4.0, a.z, a.yaw, 25f)
        }
        return world.spawnLocation.add(0.0, 5.0, 0.0)
    }

    /** Ends playback: puts the viewer back exactly as they were and deletes the replay world. */
    fun stop(restore: Boolean = true) {
        if (ended) return
        ended = true
        task?.cancel()
        try {
            if (cameraMode) renderer.camera(null)
            for (e in sim.values) renderer.remove(e)
        } catch (_: Throwable) {
        }
        sim.clear()
        hud.close()

        if (restore && viewer.isOnline) {
            val state = saved
            if (state != null) {
                val back = state.location()
                    ?: Bukkit.getWorld(config.returnWorld)?.spawnLocation
                    ?: Bukkit.getWorlds().first { it.uid != world.uid }.spawnLocation
                viewer.teleport(back)
                state.apply(viewer)
                ViewerState.fileFor(stateDir, viewer.uniqueId).delete()
            }
        }
        // Give the teleport a moment to complete before the world is unloaded.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            PlaybackWorlds.destroy(plugin, world)
        }, 5L)
        onEnded(this)
    }

    // ------------------------------------------------------------------ ticking

    private fun onTick() {
        if (ended) return
        try {
            if (!viewer.isOnline || viewer.world.uid != world.uid) { stop(); return }
            if (!paused) {
                playPos += speed
                val target = playPos.toInt().coerceAtMost(durationTicks)
                if (target > cursor) advance(target)
                if (cursor >= durationTicks) {
                    paused = true
                    refreshControls()
                    viewer.sendMessage(config.msg("playback-ended"))
                }
            }
            if (counter % 20 == 0) ensureFlight(force = false)
            if (++counter % 4 == 0) updateProgress()
            if (cameraMode && counter % 20 == 0) keepNearFollowed()
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Playback error", t)
            if (++errors >= 5) { viewer.sendMessage(config.msg("load-failed")); stop() }
        }
    }

    private fun advance(target: Int) {
        while (frameIdx < frames.size && frames[frameIdx].tick <= target) {
            for (a in frames[frameIdx].actions) applyAction(a, render = true, blocks = true, chat = true)
            frameIdx++
        }
        cursor = target
        for (e in dirty) renderer.teleport(e)
        dirty.clear()
    }

    private fun applyAction(a: Action, render: Boolean, blocks: Boolean, chat: Boolean) {
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
                    if (cameraMode && following == a.id) renderer.camera(e)
                }
            }
            is Action.Remove -> {
                val e = sim.remove(a.id) ?: return
                if (render) {
                    dirty.remove(e)
                    if (cameraMode && following == a.id) renderer.camera(null)
                    renderer.remove(e)
                }
            }
            is Action.Move -> sim[a.id]?.let { e ->
                e.x = a.x; e.y = a.y; e.z = a.z; e.yaw = a.yaw; e.pitch = a.pitch; e.onGround = a.onGround
                if (render) dirty += e
            }
            is Action.Flags -> sim[a.id]?.let { e -> e.flags = a.flags; if (render) renderer.flags(e) }
            is Action.Equip -> sim[a.id]?.let { e ->
                if (a.slot in e.equip.indices) { e.equip[a.slot] = a.item; if (render) renderer.equip(e, a.slot) }
            }
            is Action.Animation -> if (render) sim[a.id]?.let { renderer.animate(it, a.anim) }
            is Action.Vitals -> sim[a.id]?.let { e -> e.health = a.health; e.maxHealth = a.maxHealth; e.food = a.food; e.level = a.level }
            is Action.Hotbar -> sim[a.id]?.let { e -> e.heldSlot = a.heldSlot; e.hotbar = a.items }
            is Action.BlockChange -> if (blocks) applyBlock(a)
            is Action.Chat -> if (chat) deliverChat(a)
            is Action.Sound -> if (chat && config.playSounds) playSound(a)
            is Action.Title -> if (chat && visibleToViewer(a.recipients)) hud.title(a.kind, a.text, a.fadeIn, a.stay, a.fadeOut)
            is Action.Scoreboard -> {
                boards[a.id] = a
                if (render && a.id == boardOwner()) showBoard()
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

    /** Whole-match view shows only what everyone saw; following a player also shows what that player saw. */
    private fun visibleToViewer(recipients: IntArray?): Boolean {
        val f = following
        return if (f == null) recipients == null else (recipients == null || recipients.contains(f))
    }

    private fun deliverChat(a: Action.Chat) {
        if (visibleToViewer(a.recipients)) viewer.sendMessage(a.text)
    }

    private fun playSound(a: Action.Sound) {
        var name = a.name
        // 1.8 knows sounds as "random.pop"; the namespace prefix only exists on 1.9+.
        if (!ServerCompat.atLeast(1, 9)) name = name.removePrefix("minecraft:")
        try {
            viewer.playSound(Location(world, a.x, a.y, a.z), name, a.volume, a.pitch)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ inventories and containers

    private fun containerKey(x: Int, y: Int, z: Int, owner: Int) = if (owner >= 0) "ender:$owner" else "$x,$y,$z"

    /** The recorded container at (or, for a double chest, next to) a clicked block, as of the current time. */
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

    // ------------------------------------------------------------------ sidebar

    /** Whose scoreboard the viewer sees: the followed player's, otherwise the first player who had one. */
    private fun boardOwner(): Int? = following ?: boards.entries.firstOrNull { it.value.title != null }?.key ?: boards.keys.minOrNull()

    private fun showBoard() {
        val b = boardOwner()?.let { boards[it] }
        if (b == null) hud.sidebar(null, emptyList()) else hud.sidebar(b.title, b.lines)
    }

    // ------------------------------------------------------------------ blocks

    private fun applyBlock(a: Action.BlockChange) {
        val k = pack(a.x, a.y, a.z)
        if (!origin.containsKey(k)) origin[k] = BlockCodec.current(world, a.x, a.y, a.z) ?: ""
        BlockCodec.apply(world, a.x, a.y, a.z, a.block)
    }

    private fun revertBlock(a: Action.BlockChange) {
        val k = pack(a.x, a.y, a.z)
        val state = prevState[a] ?: origin[k]
        if (!state.isNullOrEmpty()) BlockCodec.apply(world, a.x, a.y, a.z, state)
    }

    // ------------------------------------------------------------------ seeking

    /** Number of frames at or before [tick]. */
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
        hud.clearTitles()
        for (i in 0 until newIdx) for (a in frames[i].actions) applyAction(a, render = false, blocks = false, chat = false)
        for (e in sim.values) renderer.spawn(e)
        showBoard()

        frameIdx = newIdx
        cursor = t
        playPos = t.toDouble()
        if (cameraMode) following?.let { id -> renderer.camera(sim[id]) }
        updateProgress()
    }

    fun skipSeconds(seconds: Int) = seek(cursor + seconds * 20)

    fun jumpToSeconds(seconds: Int) {
        seek(seconds * 20)
        viewer.sendMessage(config.msg("jumped", "time" to RecordingManager.formatTime(seconds)))
    }

    // ------------------------------------------------------------------ controls

    fun togglePause() {
        if (cursor >= durationTicks) seek(0)
        paused = !paused
        refreshControls()
        viewer.sendMessage(config.msg(if (paused) "paused" else "resumed"))
    }

    fun faster() = changeSpeed(+1)
    fun slower() = changeSpeed(-1)

    private fun changeSpeed(delta: Int) {
        speedIdx = (speedIdx + delta).coerceIn(0, config.speeds.lastIndex)
        refreshControls()
        viewer.sendMessage(config.msg("speed", "speed" to speedLabel()))
    }

    private fun speedLabel(): String {
        val s = speed
        return if (s == Math.floor(s)) s.toInt().toString() else s.toString()
    }

    fun giveControls() {
        val skip = config.skipSeconds
        val inv = viewer.inventory
        inv.setItem(0, Items.make(Items.SLOWER, "§c« Slower §7(${speedLabel()}x)", listOf("§7Click to slow playback down")))
        inv.setItem(1, Items.make(Items.ARROW, "§eBack ${skip}s", listOf("§7Jump backwards")))
        inv.setItem(2, pauseItem())
        inv.setItem(3, Items.make(Items.ARROW, "§eForward ${skip}s", listOf("§7Jump forwards")))
        inv.setItem(4, Items.make(Items.FASTER, "§aFaster » §7(${speedLabel()}x)", listOf("§7Click to speed playback up")))
        inv.setItem(5, Items.make(Items.CLOCK, "§bSkip to time", listOf("§7Left-click: type a time", "§7Right-click: pick a moment")))
        inv.setItem(6, Items.make(Items.COMPASS, "§bTeleport to player", listOf("§7Left-click a player: teleport", "§7Right-click: first-person view")))
        inv.setItem(7, Items.make(Items.BOOK, "§bMatch events", listOf("§7Kills, beds and eliminations")))
        inv.setItem(8, Items.make(Items.EXIT, "§cLeave replay", listOf("§7Return to where you were")))
        @Suppress("DEPRECATION") viewer.updateInventory()
    }

    private fun pauseItem(): ItemStack =
        if (paused) Items.make(Items.PLAY, "§aPlay", listOf("§7Resume playback"))
        else Items.make(Items.PAUSE, "§ePause", listOf("§7Pause playback"))

    private fun refreshControls() = giveControls()

    private fun updateProgress() {
        viewer.level = cursor / 20
        viewer.exp = if (durationTicks == 0) 0f else (cursor.toFloat() / durationTicks).coerceIn(0f, 0.999f)
    }

    // ------------------------------------------------------------------ players

    val roster: List<PlayerInfo> get() = replay.meta.players

    fun isAlive(rid: Int) = sim.containsKey(rid)
    fun entity(rid: Int): Ent? = sim[rid]

    fun teleportTo(rid: Int, camera: Boolean) {
        val e = sim[rid] ?: return
        val name = players[rid]?.name ?: "player"
        viewer.teleport(Location(world, e.x, e.y + 0.1, e.z, e.yaw, e.pitch))
        following = rid
        cameraMode = camera
        if (camera) renderer.camera(e) else renderer.camera(null)
        showBoard()
        viewer.sendMessage(config.msg("following", "player" to name))
    }

    fun stopFollowing() {
        if (cameraMode) renderer.camera(null)
        cameraMode = false
        following = null
        showBoard()
        viewer.sendMessage(config.msg("stopped-following"))
    }

    /** While in first-person view the viewer's real position must stay near the target so chunks keep loading. */
    private fun keepNearFollowed() {
        val e = following?.let { sim[it] } ?: return
        val loc = viewer.location
        val dx = loc.x - e.x; val dz = loc.z - e.z
        if (dx * dx + dz * dz > 24 * 24) viewer.teleport(Location(world, e.x, e.y + 0.1, e.z, loc.yaw, loc.pitch))
    }

    companion object {
        private fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)
    }
}
