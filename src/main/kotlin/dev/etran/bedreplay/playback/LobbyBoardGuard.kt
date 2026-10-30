package dev.etran.bedreplay.playback

import dev.etran.bedreplay.record.ScoreboardRegistry
import dev.etran.bedreplay.record.SidebarView
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Safety net for the lobby sidebar. If the sidebar the player had before the replay was removed while they were away
 * and nothing draws a sidebar again after they are back, a copy of what it showed is drawn until some plugin shows a
 * sidebar again (BedWars entering a game, the lobby board refreshing, ...).
 */
object LobbyBoardGuard {
    private class Guard(val hud: HudRenderer, val view: SidebarView) { var task: BukkitTask? = null }

    /** Set by the plugin; used to ask BedWars to redraw its lobby sidebar itself. */
    @Volatile
    var bridge: dev.etran.bedreplay.bedwars.BedWarsBridge? = null

    private val guards = ConcurrentHashMap<UUID, Guard>()

    /** Stops the guard of [uuid] (a new replay is starting, or a real sidebar is back). Returns what it was showing. */
    fun cancel(uuid: UUID): SidebarView? {
        val g = guards.remove(uuid) ?: return null
        g.task?.cancel()
        g.hud.close(clearTitles = false)
        return g.view
    }

    /** Checks shortly after the viewer is back; draws [view] if no sidebar is displayed at all. */
    fun check(plugin: Plugin, viewer: Player, view: SidebarView?) {
        // Preferred: let BedWars rebuild its own lobby sidebar (twice, in case it ignores the first call).
        for (delay in longArrayOf(5L, 40L)) Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!viewer.isOnline) return@Runnable
            val b = bridge ?: return@Runnable
            if (b.arenaOf(viewer) != null) return@Runnable
            val ok = b.refreshLobbySidebar(viewer)
            if (plugin.config.getBoolean("debug", false))
                plugin.logger.info("[debug] lobby sidebar refresh for ${viewer.name}: api=$ok snapshot=${view != null}")
        }, delay)
        if (view == null) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!viewer.isOnline) return@Runnable
            val tracker = ScoreboardRegistry.peek(viewer.uniqueId) ?: return@Runnable
            if (tracker.sidebarObjective() != null) return@Runnable
            cancel(viewer.uniqueId)
            val hud = HudRenderer(plugin, viewer)
            hud.sidebar(view.title, view.lines)
            val g = Guard(hud, view)
            guards[viewer.uniqueId] = g
            g.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
                val t = ScoreboardRegistry.peek(viewer.uniqueId)
                if (!viewer.isOnline || t == null || t.sidebarObjective() != null) cancel(viewer.uniqueId)
            }, 20L, 20L)
        }, 20L)
    }
}
