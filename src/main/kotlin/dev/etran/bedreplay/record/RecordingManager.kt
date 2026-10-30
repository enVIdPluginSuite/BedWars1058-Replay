package dev.etran.bedreplay.record

import dev.etran.bedreplay.bedwars.ArenaRef
import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.bedwars.BedWarsCallbacks
import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Anim
import dev.etran.bedreplay.format.MarkerKind
import dev.etran.bedreplay.format.ReplayMeta
import dev.etran.bedreplay.format.ReplayReader
import dev.etran.bedreplay.storage.ReplayRepository
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Starts a [RecordingSession] when a BedWars match begins and finishes it when the match ends,
 * the arena is reset or disabled, or the server stops.
 */
class RecordingManager(
    private val plugin: Plugin,
    private val config: () -> PluginConfig,
    private val repo: ReplayRepository,
    private val bridge: BedWarsBridge,
) : BedWarsCallbacks, PacketSink, Listener {

    private val spoolDir = File(plugin.dataFolder, "spool")
    private val sessions = LinkedHashMap<ArenaRef, RecordingSession>() // main thread only
    private val byPlayer = ConcurrentHashMap<UUID, RecordingSession>()
    private val byWorld = ConcurrentHashMap<UUID, RecordingSession>()
    private var task: BukkitTask? = null

    fun start() {
        spoolDir.mkdirs()
        plugin.server.pluginManager.registerEvents(this, plugin)
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickAll() }, 1L, 1L)
        registerDragonEvent()
        recoverOrphans()
    }

    /** Called from onDisable: closes every live recording and saves it synchronously. */
    fun shutdown() {
        task?.cancel()
        for (s in ArrayList(sessions.values)) finish(s, blocking = true)
    }

    fun activeCount() = sessions.size

    // ------------------------------------------------------------------ BedWars lifecycle

    override fun onStateChange(arena: ArenaRef, newState: String) {
        when (newState.lowercase()) {
            "playing" -> {
                if (!config().recordingEnabled) return
                // Teams and spawns are assigned right after the state flips, so start a moment later.
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { begin(arena) }, 2L)
            }
            "restarting" -> sessions[arena]?.let { s ->
                if (s.endAtTick < 0) s.endAtTick = s.tick + config().postGameSeconds * 20
            }
            "waiting", "starting" -> sessions[arena]?.let { finish(it) }
        }
    }

    override fun onGameEnd(arena: ArenaRef, winnerTeam: String?) {
        val s = sessions[arena] ?: return
        s.winner = winnerTeam
        s.marker(MarkerKind.GAME_END, if (winnerTeam != null) "$winnerTeam wins" else "Game over")
        s.endAtTick = s.tick + config().postGameSeconds * 20
    }

    override fun onKill(arena: ArenaRef, victim: Player, killer: Player?, finalKill: Boolean, message: String?) {
        val s = sessions[arena] ?: return
        s.death(victim.uniqueId)
        val label = message?.let { resolvePlaceholders(it, arena, victim, killer) }
            ?: if (killer != null) "${killer.name} killed ${victim.name}" else "${victim.name} died"
        s.marker(if (finalKill) MarkerKind.FINAL_KILL else MarkerKind.KILL, label, victim.uniqueId)
    }

    /** BedWars messages carry placeholders such as {PlayerColor}{PlayerName}; fill them in while the players are known. */
    private fun resolvePlaceholders(message: String, arena: ArenaRef, victim: Player, killer: Player?): String {
        fun color(p: Player?): String = p?.let { arena.teamOf(it)?.rgb }?.let { dev.etran.bedreplay.util.ChatPalette.code(it) } ?: ""
        var t = ChatColor.translateAlternateColorCodes('&', message)
            .replace("{PlayerColor}", color(victim)).replace("{PlayerName}", victim.name + "§f")
            .replace("{TeamColor}", color(victim))
            .replace("{KillerColor}", color(killer)).replace("{KillerName}", (killer?.name ?: "") + "§f")
        t = t.replace(Regex("\\{[A-Za-z]+\\}"), "")
        return t.trim()
    }

    override fun onBedBreak(arena: ArenaRef, breaker: Player, victimTeam: String) {
        sessions[arena]?.marker(MarkerKind.BED_DESTROYED, "${breaker.name} destroyed $victimTeam's bed", breaker.uniqueId)
    }

    override fun onTeamEliminated(arena: ArenaRef, team: String) {
        sessions[arena]?.marker(MarkerKind.TEAM_ELIMINATED, "$team was eliminated")
    }

    override fun onArenaDisabled(arenaName: String) {
        sessions.entries.firstOrNull { it.key.name.equals(arenaName, true) }?.value?.let { finish(it) }
    }

    private fun begin(arena: ArenaRef) {
        if (sessions.containsKey(arena) || !arena.status.equals("playing", true)) return
        val world = arena.world ?: Bukkit.getWorld(arena.worldName) ?: run {
            plugin.logger.warning("Cannot record ${arena.name}: its world is not loaded")
            return
        }
        val s = RecordingSession(plugin, config(), arena, world, spoolDir)
        if (!s.start()) return
        sessions[arena] = s
        byWorld[world.uid] = s
        for (p in arena.players) byPlayer[p.uniqueId] = s
        plugin.logger.info("Recording ${arena.name} as ${s.id}")
    }

    private fun tickAll() {
        if (sessions.isEmpty()) return
        for (s in ArrayList(sessions.values)) {
            s.tick()
            if (s.endAtTick in 0..s.tick) finish(s)
        }
    }

    private fun finish(s: RecordingSession, blocking: Boolean = false) {
        sessions.remove(s.arena)
        byWorld.remove(s.world.uid, s)
        byPlayer.entries.removeIf { it.value === s }

        val meta: ReplayMeta? = try {
            s.finish()
        } catch (t: Throwable) {
            plugin.logger.log(Level.SEVERE, "Failed to close recording ${s.id}", t)
            null
        }
        if (meta == null) {
            plugin.logger.info("Discarded recording ${s.id} (too short)")
            return
        }
        val notify = {
            for (info in meta.players) {
                Bukkit.getPlayer(UUID.fromString(info.uuid))?.sendMessage(
                    config().msg("recording-saved", "id" to meta.id, "duration" to formatTime(meta.durationTicks / 20)),
                )
            }
        }
        if (blocking) {
            repo.saveBlocking(s.spoolFile, meta)
        } else {
            repo.saveAsync(s.spoolFile, meta) { stored -> if (stored != null) notify() }
        }
    }

    /** Spool files left behind by a crash: recover what was flushed, or delete if too short. */
    private fun recoverOrphans() {
        val live = sessions.values.map { it.spoolFile.name }.toSet()
        val parts = spoolDir.listFiles { f -> f.name.endsWith(".brep.part") && f.name !in live } ?: return
        if (parts.isEmpty()) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            for (f in parts) {
                try {
                    val replay = FileInputStream(f).use { ReplayReader.read(it) }
                    if (replay.meta.durationTicks >= config().minDurationSeconds * 20) {
                        repo.saveBlocking(f, replay.meta)
                        plugin.logger.info("Recovered replay ${replay.meta.id} from an unclean shutdown")
                    } else f.delete()
                } catch (t: Throwable) {
                    plugin.logger.log(Level.WARNING, "Could not recover ${f.name}; leaving it in place", t)
                }
            }
        })
    }

    // ------------------------------------------------------------------ PacketSink (Netty threads)

    override fun wants(player: UUID) = byPlayer[player]?.wantsPackets(player) == true
    override fun chat(player: UUID, legacyText: String) { byPlayer[player]?.queueChat(player, legacyText) }
    override fun block(player: UUID, x: Int, y: Int, z: Int, state: String) { byPlayer[player]?.queueBlock(x, y, z, state) }
    override fun title(player: UUID, kind: Int, text: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        byPlayer[player]?.queueTitle(player, kind, text, fadeIn, stay, fadeOut)
    }
    override fun sound(player: UUID, name: String, x: Double, y: Double, z: Double, volume: Float, pitch: Float) {
        byPlayer[player]?.queueSound(name, x, y, z, volume, pitch, -1)
    }
    override fun entitySound(player: UUID, entityId: Int, name: String, volume: Float, pitch: Float) {
        byPlayer[player]?.queueSound(name, 0.0, 0.0, 0.0, volume, pitch, entityId)
    }

    // ------------------------------------------------------------------ Bukkit events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(e: EntityExplodeEvent) {
        if (!config().recordBlocks) return
        byWorld[e.entity.world.uid]?.explosion(e.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) {
        if (!config().recordBlocks) return
        byWorld[e.block.world.uid]?.explosion(e.blockList())
    }

    // Verification handlers: these also run for cancelled events, because other plugins (BedWars' own dragon and
    // explosion handling) often cancel and then remove the blocks themselves.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onExplodeVerify(e: EntityExplodeEvent) {
        byWorld[e.entity.world.uid]?.watchBlocks(e.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBlockExplodeVerify(e: BlockExplodeEvent) {
        byWorld[e.block.world.uid]?.watchBlocks(e.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityChangeBlock(e: org.bukkit.event.entity.EntityChangeBlockEvent) {
        byWorld[e.block.world.uid]?.watchBlocks(listOf(e.block))
    }

    /** EnderDragonChangeBlockEvent does not exist before 1.9, so it is registered by name. */
    private fun registerDragonEvent() {
        try {
            @Suppress("UNCHECKED_CAST")
            val cls = Class.forName("org.bukkit.event.entity.EnderDragonChangeBlockEvent") as Class<out org.bukkit.event.Event>
            val getBlock = cls.getMethod("getBlock")
            plugin.server.pluginManager.registerEvent(cls, this, EventPriority.MONITOR, { _, event ->
                if (cls.isInstance(event)) {
                    val b = getBlock.invoke(event) as org.bukkit.block.Block
                    byWorld[b.world.uid]?.watchBlocks(listOf(b))
                }
            }, plugin)
        } catch (_: Throwable) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSwing(e: PlayerAnimationEvent) {
        val id = e.player.uniqueId
        byPlayer[id]?.animation(id, Anim.SWING_MAIN)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(e: EntityDamageEvent) {
        val p = e.entity as? Player
        if (p == null) {
            if (e.entity is org.bukkit.entity.LivingEntity) byWorld[e.entity.world.uid]?.entityHurt(e.entity.entityId)
            return
        }
        byPlayer[p.uniqueId]?.animation(p.uniqueId, Anim.HURT)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(e: org.bukkit.event.player.PlayerQuitEvent) {
        ScoreboardRegistry.forget(e.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryOpen(e: org.bukkit.event.inventory.InventoryOpenEvent) {
        val p = e.player as? Player ?: return
        byPlayer[p.uniqueId]?.containerOpened(p.uniqueId, e.inventory)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(e: org.bukkit.event.inventory.InventoryCloseEvent) {
        val p = e.player as? Player ?: return
        byPlayer[p.uniqueId]?.containerClosed(p.uniqueId, e.inventory)
    }

    /**
     * 1.19+ sends signed player chat in a packet we do not decode, so ordinary chat is taken from the
     * event there. (Before 1.19 the chat packet capture already sees every line.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(e: AsyncPlayerChatEvent) {
        if (!config().recordChat || !ServerCompat.atLeast(1, 19)) return
        val session = byPlayer[e.player.uniqueId] ?: return
        val text = try {
            String.format(e.format, e.player.displayName, e.message)
        } catch (_: Exception) {
            "${e.player.displayName}: ${e.message}"
        }
        for (r in e.recipients) if (session.isParticipant(r.uniqueId)) session.queueChat(r.uniqueId, text)
    }

    companion object {
        fun formatTime(totalSeconds: Int): String {
            val s = totalSeconds.coerceAtLeast(0)
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }
}
