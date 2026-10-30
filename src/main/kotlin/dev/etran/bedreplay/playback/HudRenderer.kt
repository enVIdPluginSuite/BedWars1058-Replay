package dev.etran.bedreplay.playback

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer
import com.github.retrooper.packetevents.wrapper.PacketWrapper
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerClearTitles
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleSubtitle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleText
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetTitleTimes
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTitle
import com.github.retrooper.packetevents.manager.server.ServerVersion
import dev.etran.bedreplay.format.TitleKind
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.util.logging.Level

/**
 * Screen furniture for one viewer: titles / action bar (as packets, so it works on every version) and the
 * sidebar scoreboard (a private Bukkit scoreboard that is swapped in and out).
 */
class HudRenderer(private val plugin: Plugin, private val viewer: Player) {
    private val pm = PacketEvents.getAPI().playerManager
    private val modernTitles = PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_17)
    private val original: Scoreboard? = viewer.scoreboard
    private var board: Scoreboard? = null
    private var objective: Objective? = null
    private var shownLines = 0
    private var lastSignature: String? = null
    private var warned = false

    private fun send(w: PacketWrapper<*>) {
        try { pm.sendPacket(viewer, w) } catch (t: Throwable) {
            if (!warned) { warned = true; plugin.logger.log(Level.WARNING, "Replay HUD packet failed; further failures are silent", t) }
        }
    }

    // ------------------------------------------------------------------ titles

    fun title(kind: Int, text: String, fadeIn: Int, stay: Int, fadeOut: Int) {
        val c = AdventureSerializer.fromLegacyFormat(text)
        if (modernTitles) {
            when (kind) {
                TitleKind.TITLE -> send(WrapperPlayServerSetTitleText(c))
                TitleKind.SUBTITLE -> send(WrapperPlayServerSetTitleSubtitle(c))
                TitleKind.ACTION_BAR -> send(WrapperPlayServerActionBar(c))
                TitleKind.TIMES -> send(WrapperPlayServerSetTitleTimes(fadeIn, stay, fadeOut))
                TitleKind.CLEAR -> send(WrapperPlayServerClearTitles(false))
                TitleKind.RESET -> send(WrapperPlayServerClearTitles(true))
            }
        } else {
            val e = net.kyori.adventure.text.Component.empty()
            when (kind) {
                TitleKind.TITLE -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.SET_TITLE, c, e, e, 0, 0, 0))
                TitleKind.SUBTITLE -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.SET_SUBTITLE, e, c, e, 0, 0, 0))
                TitleKind.ACTION_BAR -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.SET_ACTION_BAR, e, e, c, 0, 0, 0))
                TitleKind.TIMES -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.SET_TIMES_AND_DISPLAY, e, e, e, fadeIn, stay, fadeOut))
                TitleKind.CLEAR -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.HIDE, e, e, e, 0, 0, 0))
                TitleKind.RESET -> send(WrapperPlayServerTitle(WrapperPlayServerTitle.TitleAction.RESET, e, e, e, 0, 0, 0))
            }
        }
    }

    fun clearTitles() {
        title(TitleKind.RESET, "", 0, 0, 0)
        title(TitleKind.ACTION_BAR, "", 0, 0, 0)
    }

    // ------------------------------------------------------------------ sidebar

    /** Shows [title]/[lines] as the viewer's sidebar; a null title removes it. Cheap when nothing changed. */
    fun sidebar(title: String?, lines: List<String>) {
        val sig = title + "\u0000" + lines.joinToString("\u0001")
        if (sig == lastSignature) return
        lastSignature = sig
        try {
            if (title == null) { hideSidebar(); return }
            val sb = board ?: Bukkit.getScoreboardManager().newScoreboard.also { board = it }
            var obj = objective
            if (obj == null) {
                obj = newObjective(sb)
                obj.displaySlot = DisplaySlot.SIDEBAR
                objective = obj
            }
            obj.displayName = clip(title, if (ServerCompat.atLeast(1, 13)) 128 else 32)
            val maxPart = if (ServerCompat.atLeast(1, 13)) 64 else 16

            for (i in lines.indices) {
                val entry = entryFor(i)
                val team = sb.getTeam("l$i") ?: sb.registerNewTeam("l$i").also { it.addEntry(entry) }
                val (prefix, suffix) = split(lines[i], maxPart)
                team.prefix = prefix
                team.suffix = suffix
                obj.getScore(entry).score = lines.size - i
            }
            for (i in lines.size until shownLines) {
                val entry = entryFor(i)
                sb.resetScores(entry)
                sb.getTeam("l$i")?.unregister()
            }
            shownLines = lines.size
            if (viewer.scoreboard !== sb) viewer.scoreboard = sb
        } catch (t: Throwable) {
            if (!warned) { warned = true; plugin.logger.log(Level.WARNING, "Could not draw the replay scoreboard", t) }
        }
    }

    /**
     * Lobby scoreboard / tab plugins re-assign a player's scoreboard when they change world, and again whenever they
     * refresh. Take it back if that happened, so the replay sidebar stays on screen.
     */
    fun reassert() {
        val sb = board ?: return
        try {
            if (viewer.scoreboard !== sb) viewer.scoreboard = sb
        } catch (_: Throwable) {
        }
    }

    private fun hideSidebar() {
        objective?.unregister()
        objective = null
        board?.let { sb -> for (i in 0 until shownLines) { sb.getTeam("l$i")?.unregister() } }
        shownLines = 0
        board?.let { if (viewer.scoreboard === it) viewer.scoreboard = original ?: Bukkit.getScoreboardManager().mainScoreboard }
        board = null
    }

    @Suppress("DEPRECATION")
    private fun newObjective(sb: Scoreboard): Objective =
        try {
            sb.registerNewObjective("replay", "dummy")
        } catch (_: Throwable) {
            // 1.13+ also takes a display name; the two-argument form is deprecated and may disappear.
            val m = Scoreboard::class.java.getMethod("registerNewObjective", String::class.java, String::class.java, String::class.java)
            m.invoke(sb, "replay", "dummy", "Replay") as Objective
        }

    /** A unique invisible score entry per line: a colour code pair that never renders. */
    private fun entryFor(i: Int): String {
        val c = ChatColor.values().filter { it.isColor }[i % 16]
        return "§${c.char}§r" + (if (i >= 16) "§r" else "")
    }

    private fun clip(s: String, max: Int) = if (s.length <= max) s else s.substring(0, max)

    /** Splits a long line over team prefix and suffix, never cutting a colour code in half. */
    private fun split(line: String, max: Int): Pair<String, String> {
        if (line.length <= max) return line to ""
        var cut = max
        if (line[cut - 1] == '§') cut--
        val prefix = line.substring(0, cut)
        val rest = ChatColor.getLastColors(prefix) + line.substring(cut)
        return prefix to clip(rest, max)
    }

    /** Puts the viewer's own scoreboard back. */
    fun close() {
        try {
            clearTitles()
            hideSidebar()
            if (viewer.isOnline) viewer.scoreboard = original ?: Bukkit.getScoreboardManager().mainScoreboard
        } catch (_: Throwable) {
        }
    }
}
