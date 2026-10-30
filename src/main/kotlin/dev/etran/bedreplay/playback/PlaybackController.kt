package dev.etran.bedreplay.playback

import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.gui.InventoryViewMenus
import dev.etran.bedreplay.gui.PlayerPickerMenu
import dev.etran.bedreplay.gui.TimelineMenu
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
) : Listener {
    private val sessions = HashMap<UUID, PlaybackSession>()
    private val starting = HashSet<UUID>()
    private val lastClick = HashMap<UUID, Long>()
    private val stateDir = File(plugin.dataFolder, "viewerstate")

    fun isWatching(p: Player) = sessions.containsKey(p.uniqueId)
    fun session(p: Player): PlaybackSession? = sessions[p.uniqueId]

    // ------------------------------------------------------------------ lifecycle

    fun start(viewer: Player, r: StoredReplay) {
        val cfg = config()
        val id = viewer.uniqueId
        if (sessions.containsKey(id) || !starting.add(id)) return
        if (sessions.size + starting.size - 1 >= cfg.maxConcurrentPlayback) {
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
                val s = PlaybackSession(plugin, cfg, viewer, r, replay, world, stateDir) { ended ->
                    sessions.remove(ended.viewer.uniqueId); repo.release(ended.stored.id)
                }
                starting.remove(id)
                sessions[id] = s
                try {
                    s.begin(ViewerState.fileFor(stateDir, id))
                } catch (t: Throwable) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "Could not start playback", t)
                    s.stop()
                    viewer.sendMessage(cfg.msg("load-failed"))
                }
            }
        }
    }

    fun stop(p: Player) { sessions[p.uniqueId]?.stop() }

    fun shutdown() {
        for (s in ArrayList(sessions.values)) s.stop()
        sessions.clear()
    }

    // ------------------------------------------------------------------ input

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(e: PlayerInteractEvent) {
        val s = sessions[e.player.uniqueId] ?: return
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
        when (e.player.inventory.heldItemSlot) {
            0 -> s.slower()
            1 -> s.skipSeconds(-config().skipSeconds)
            2 -> s.togglePause()
            3 -> s.skipSeconds(config().skipSeconds)
            4 -> s.faster()
            5 -> if (right) TimelineMenu.open(s) else {
                s.awaitingTime = true; e.player.sendMessage(config().msg("type-time"))
            }
            6 -> PlayerPickerMenu.open(s)
            7 -> TimelineMenu.open(s)
            8 -> s.stop()
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(e: AsyncPlayerChatEvent) {
        // Replay viewers neither see live server chat nor speak into it.
        e.recipients.removeIf { sessions.containsKey(it.uniqueId) }
        val s = sessions[e.player.uniqueId] ?: return
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
        if (!sessions.containsKey(e.player.uniqueId) || e.player.hasPermission("bedreplay.admin")) return
        val cmd = e.message.trim().removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        if (cmd != "replay" && cmd != "bedreplay") { e.isCancelled = true }
    }

    // ------------------------------------------------------------------ keep the viewer harmless

    @EventHandler(priority = EventPriority.HIGH)
    fun onInvClick(e: InventoryClickEvent) {
        val p = e.whoClicked as? Player ?: return
        if (sessions.containsKey(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInvDrag(e: InventoryDragEvent) {
        val p = e.whoClicked as? Player ?: return
        if (sessions.containsKey(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrop(e: PlayerDropItemEvent) { if (sessions.containsKey(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onBreak(e: BlockBreakEvent) { if (sessions.containsKey(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlace(e: BlockPlaceEvent) { if (sessions.containsKey(e.player.uniqueId)) e.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(e: EntityDamageEvent) {
        val p = e.entity as? Player ?: return
        if (sessions.containsKey(p.uniqueId)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onFood(e: FoodLevelChangeEvent) {
        val p = e.entity as? Player ?: return
        if (sessions.containsKey(p.uniqueId)) e.isCancelled = true
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val id = e.player.uniqueId
        lastClick.remove(id)
        // The saved state file stays on disk and is restored on the next join.
        sessions[id]?.stop(restore = false)
    }

    /** Restores a viewer whose server crashed (or who quit) mid-replay. */
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        val file = ViewerState.fileFor(stateDir, p.uniqueId)
        if (!file.isFile) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || sessions.containsKey(p.uniqueId)) return@Runnable
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
}
