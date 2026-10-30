package dev.etran.bedreplay.playback

import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.gui.ControlMenu
import dev.etran.bedreplay.gui.InventoryViewMenus
import dev.etran.bedreplay.gui.PlayerPickerMenu
import dev.etran.bedreplay.gui.TimelineMenu
import dev.etran.bedreplay.settings.PlayerSettingsStore
import dev.etran.bedreplay.storage.ReplayRepository
import dev.etran.bedreplay.storage.StoredReplay
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID

/** Owns every running [PlaybackSession] and translates player input into timeline controls. */
class PlaybackController(
    private val plugin: Plugin,
    private val config: () -> PluginConfig,
    private val repo: ReplayRepository,
    private val bridge: BedWarsBridge,
    val settings: PlayerSettingsStore,
) : Listener {
    private val sessions = java.util.concurrent.ConcurrentHashMap<UUID, PlaybackSession>()
    private val rooms = LinkedHashSet<ReplayRoom>()
    /** One-shot chat prompts (type a player name ...), keyed by player. */
    private val pendingInput = java.util.concurrent.ConcurrentHashMap<UUID, (String) -> Unit>()
    private val starting = HashSet<UUID>()
    private val lastClick = HashMap<UUID, Long>()
    private val stateDir = File(plugin.dataFolder, "viewerstate")

    private fun active(id: UUID): PlaybackSession? {
        val s = sessions[id] ?: return null
        if (s.ended) { sessions.remove(id); return null }
        return s
    }

    private fun isActive(id: UUID) = active(id) != null

    fun isWatching(p: Player) = isActive(p.uniqueId)
    fun isWatchingId(id: UUID) = isActive(id)

    /** A viewer punched a replay entity (by its client-side id). With an empty hand that starts spectating it. */
    fun punch(p: Player, liveId: Int) {
        val s = active(p.uniqueId) ?: return
        if (s.cameraMode) return
        val inv = p.inventory
        val held = inv.getItem(inv.heldItemSlot)
        if (held != null && !held.type.name.endsWith("AIR")) return
        val e = s.room.entityByLive(liveId) ?: return
        s.teleportTo(e.rid, camera = true)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSneak(e: org.bukkit.event.player.PlayerToggleSneakEvent) {
        val s = active(e.player.uniqueId) ?: return
        if (s.cameraMode && e.isSneaking) s.stopFollowing()
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onHeld(e: org.bukkit.event.player.PlayerItemHeldEvent) {
        val s = active(e.player.uniqueId) ?: return
        if (s.cameraMode) e.isCancelled = true
    }
    fun session(p: Player): PlaybackSession? = active(p.uniqueId)
    fun rooms(): List<ReplayRoom> = rooms.filter { !it.ended }

    /** Ask for a line of chat from [p]; it is consumed instead of being sent to the server. */
    fun prompt(p: Player, message: String, handler: (String) -> Unit) {
        pendingInput[p.uniqueId] = handler
        p.sendMessage(message)
    }

    /** May [p] join the replay [room]? Admins always may; everyone else follows the host's settings. */
    fun mayJoin(p: Player, room: ReplayRoom): Boolean {
        if (room.ended || room.host.viewer.uniqueId == p.uniqueId) return false
        if (p.hasPermission("bedreplay.admin")) return true
        return p.hasPermission("bedreplay.use") && room.hostSettings.mayJoin(p.uniqueId)
    }

    // ------------------------------------------------------------------ lifecycle

    fun start(viewer: Player, r: StoredReplay) {
        val cfg = config()
        val id = viewer.uniqueId
        if (isActive(id) || !starting.add(id)) return
        if (rooms().size + starting.size - 1 >= cfg.maxConcurrentPlayback) {
            starting.remove(id); viewer.sendMessage(cfg.msg("playback-busy")); return
        }
        if (bridge.arenaOf(viewer) != null) {
            starting.remove(id); viewer.sendMessage(cfg.msg("no-permission")); return
        }
        viewer.sendMessage(cfg.msg("loading"))
        repo.acquire(r.id)
        fun abort(msg: String) {
            starting.remove(id); repo.release(r.id)
            if (viewer.isOnline) viewer.sendMessage(cfg.msg(msg))
        }
        repo.loadAsync(r.id) { replay ->
            if (replay == null) return@loadAsync abort("load-failed")
            if (!viewer.isOnline) return@loadAsync abort("load-failed")
            PlaybackWorlds.prepare(plugin, bridge, cfg.worldPrefix, r.id, r.arena) { world ->
                if (world == null) return@prepare abort("map-missing")
                if (!viewer.isOnline) {
                    PlaybackWorlds.destroy(plugin, world); return@prepare abort("load-failed")
                }
                val room = ReplayRoom(plugin, cfg, r, replay, world, stateDir, { settings.get(it) }) { ended ->
                    rooms.remove(ended); repo.release(ended.stored.id)
                }
                val s = PlaybackSession(plugin, cfg, viewer, room, stateDir)
                starting.remove(id)
                rooms += room
                sessions[id] = s
                try {
                    room.start(s)
                    announce(room)
                } catch (t: Throwable) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "Could not start playback", t)
                    room.shutdown()
                    viewer.sendMessage(cfg.msg("load-failed"))
                }
            }
        }
    }

    /** Joins [guest] to [room] as a viewer, after checking they are allowed to. */
    fun join(guest: Player, room: ReplayRoom) {
        val cfg = config()
        if (isActive(guest.uniqueId)) { guest.sendMessage(cfg.msg("join-already")); return }
        if (bridge.arenaOf(guest) != null) { guest.sendMessage(cfg.msg("join-in-game")); return }
        if (!mayJoin(guest, room)) { guest.sendMessage(cfg.msg("join-denied")); return }
        if (!room.canAdd()) { guest.sendMessage(cfg.msg("join-full")); return }
        val s = PlaybackSession(plugin, cfg, guest, room, stateDir)
        sessions[guest.uniqueId] = s
        try {
            room.join(s)
        } catch (t: Throwable) {
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not join replay", t)
            s.stop()
            guest.sendMessage(cfg.msg("load-failed"))
        }
    }

    /** The room hosted by the player called [name], if any. */
    fun roomOf(name: String): ReplayRoom? =
        rooms().firstOrNull { it.host.viewer.name.equals(name, ignoreCase = true) }
            ?: rooms().firstOrNull { r -> r.members.values.any { it.viewer.name.equals(name, ignoreCase = true) } }

    /** Tells everyone in the lobby who may join that a replay is open, with a clickable join message. */
    fun announce(room: ReplayRoom) {
        val cfg = config()
        if (!cfg.sharingAnnounce || !room.hostSettings.allowJoin) return
        val host = room.host.viewer
        for (p in Bukkit.getOnlinePlayers()) {
            if (p.uniqueId == host.uniqueId || isActive(p.uniqueId)) continue
            if (bridge.arenaOf(p) != null) continue
            if (!mayJoin(p, room)) continue
            val text = cfg.msg("announce", "host" to host.name, "map" to room.mapName)
            val hover = cfg.msg("announce-hover", "host" to host.name, prefix = false)
            if (!ClickableChat.send(p, text, "/replaysjoin ${host.name}", hover)) p.sendMessage(text + " §7(/replaysjoin ${host.name})")
        }
    }

    /** Hands the host role to [name], who must be in the same replay as [from]. */
    fun transfer(from: Player, name: String) {
        val cfg = config()
        val s = active(from.uniqueId)
        if (s == null) { from.sendMessage(cfg.msg("not-watching")); return }
        if (!s.isHost) { from.sendMessage(cfg.msg("host-only")); return }
        val target = s.room.members.values.firstOrNull { it.viewer.name.equals(name, ignoreCase = true) && it !== s }
        if (target == null) { from.sendMessage(cfg.msg("transfer-not-member")); return }
        s.room.transfer(target)
    }

    fun stop(p: Player) { active(p.uniqueId)?.stop() }

    fun shutdown() {
        for (r in ArrayList(rooms)) r.shutdown()
        rooms.clear()
        sessions.clear()
    }

    // ------------------------------------------------------------------ input

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        val s = active(e.player.uniqueId) ?: return
        e.isCancelled = true
        if (e.action == Action.PHYSICAL) return
        // 1.9+ fires once per hand; 1.8 fires a left-click per swing. Debounce both.
        val now = System.currentTimeMillis()
        if (now - (lastClick[e.player.uniqueId] ?: 0L) < 150) return
        lastClick[e.player.uniqueId] = now

        // Clicking a chest / furnace / ... that was opened during the match shows what was inside at this moment.
        val clicked = e.clickedBlock
        if (e.action == Action.RIGHT_CLICK_BLOCK && clicked != null && e.player.inventory.heldItemSlot != 8) {
            val c = s.containerNear(clicked.x, clicked.y, clicked.z)
            if (c != null) { InventoryViewMenus.openContainer(s, c); return }
        }

        val right = e.action == Action.RIGHT_CLICK_AIR || e.action == Action.RIGHT_CLICK_BLOCK
        val slot = e.player.inventory.heldItemSlot
        if (slot == 8) { ControlMenu.open(s); return }
        if (!s.isHost) return // guests only have the menu; the host runs the timeline
        when (slot) {
            0 -> s.slower()
            1 -> s.skipSeconds(-config().skipSeconds)
            2 -> s.togglePause()
            3 -> s.skipSeconds(config().skipSeconds)
            4 -> s.faster()
            5 -> if (right) TimelineMenu.open(s) else {
                s.awaitingTime = true; e.player.sendMessage(config().msg("type-time"))
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(e: AsyncPlayerChatEvent) {
        // Replay viewers neither see live server chat nor speak into it.
        e.recipients.removeIf { isActive(it.uniqueId) }
        pendingInput.remove(e.player.uniqueId)?.let { handler ->
            e.isCancelled = true
            val text = e.message
            Bukkit.getScheduler().runTask(plugin, Runnable { handler(text) })
            return
        }
        val s = active(e.player.uniqueId) ?: return
        e.isCancelled = true
        if (!s.awaitingTime) return
        val text = e.message
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (s.ended) return@Runnable
            s.awaitingTime = false
            if (text.trim().equals("cancel", true)) return@Runnable
            val target = TimeParser.parse(text, s.cursor / 20, s.durationTicks / 20)
            if (target == null) s.viewer.sendMessage(config().msg("bad-time"))
            else s.jumpToSeconds(target)
        })
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onCommand(e: PlayerCommandPreprocessEvent) {
        if (!isActive(e.player.uniqueId) || e.player.hasPermission("bedreplay.admin")) return
        val cmd = e.message.trim().removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        if (cmd !in ALLOWED_COMMANDS) { e.isCancelled = true }
    }

    // ------------------------------------------------------------------ keep the viewer harmless

    @EventHandler(priority = EventPriority.HIGH)
    fun onInvClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if (isActive(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInvDrag(e: InventoryDragEvent) {
        val p = e.whoClicked as? Player ?: return
        if (isActive(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrop(e: PlayerDropItemEvent) { if (isActive(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBreak(e: BlockBreakEvent) { if (isActive(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlace(e: BlockPlaceEvent) { if (isActive(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(e: EntityDamageEvent) {
        val p = e.entity as? Player ?: return
        if (isActive(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFood(e: FoodLevelChangeEvent) {
        val p = e.entity as? Player ?: return
        if (isActive(p.uniqueId)) e.isCancelled = true
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val id = e.player.uniqueId
        lastClick.remove(id)
        // The saved state file stays on disk and is restored on the next join.
        active(id)?.stop(restore = false)
    }

    /** Restores a viewer whose server crashed (or who quit) mid-replay. */
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        val file = ViewerState.fileFor(stateDir, p.uniqueId)
        if (!file.isFile) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || isActive(p.uniqueId)) return@Runnable
            val state = ViewerState.load(file)
            if (state != null) {
                val back = state.location()
                    ?: Bukkit.getWorld(config().returnWorld)?.spawnLocation
                    ?: Bukkit.getWorlds()[0].spawnLocation
                p.teleport(back)
                state.apply(p)
            }
            file.delete()
        }, 5L)
    }

    private companion object {
        val ALLOWED_COMMANDS = setOf("replay", "replays", "bedreplay", "replaysjoin", "replaytransfer")
    }
}
