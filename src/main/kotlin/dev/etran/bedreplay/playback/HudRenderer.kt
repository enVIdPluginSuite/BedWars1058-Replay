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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import dev.etran.bedreplay.record.ScoreboardRegistry
import java.util.Optional
import java.util.logging.Level

/**
 * Screen furniture for one viewer: titles / action bar and the sidebar scoreboard, all as packets.
 *
 * The sidebar is a client-side objective of our own that takes over the sidebar slot. The viewer's real scoreboard
 * (the lobby sidebar BedWars draws with packets) is never touched, so it can simply be put back when the replay ends.
 */
class HudRenderer(private val plugin: Plugin, private val viewer: Player) {
    private val pm = PacketEvents.getAPI().playerManager
    private val modernTitles = PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_17)
    private var created = false
    private var shownLines = 0
    private var lastSignature: String? = null
    private var lastTitle: String? = null
    private val lineSigs = ArrayList<String>()
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

    private companion object {
        const val OBJ = "brhud"
        const val SIDEBAR_SLOT = 1
    }

    private fun objectiveTitle(title: String) = AdventureSerializer.fromLegacyFormat(clip(title, if (ServerCompat.atLeast(1, 13)) 128 else 32))

    /** Shows [title]/[lines] as the viewer's sidebar; a null title removes it. Cheap when nothing changed. */
    fun sidebar(title: String?, lines: List<String>) {
        val sig = title + "\u0000" + lines.joinToString("\u0001")
        if (sig == lastSignature) return
        lastSignature = sig
        try {
            if (title == null) { hideSidebar(); return }
            if (!created) {
                send(WrapperPlayServerScoreboardObjective(OBJ, WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE, objectiveTitle(title), WrapperPlayServerScoreboardObjective.RenderType.INTEGER))
                send(WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, OBJ))
                created = true
                lastTitle = title
            } else if (title != lastTitle) {
                send(WrapperPlayServerScoreboardObjective(OBJ, WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE, objectiveTitle(title), WrapperPlayServerScoreboardObjective.RenderType.INTEGER))
                lastTitle = title
            }
            val maxPart = if (ServerCompat.atLeast(1, 13)) 64 else 16
            val shown = lines.take(15)
            for (i in shown.indices) {
                val entry = entryFor(i)
                val (prefix, suffix) = split(shown[i], maxPart)
                val lineSig = "$prefix|$suffix"
                val existed = i < shownLines
                if (!existed || lineSigs.getOrNull(i) != lineSig) {
                    val info = WrapperPlayServerTeams.ScoreBoardTeamInfo(
                        net.kyori.adventure.text.Component.text(OBJ + i),
                        AdventureSerializer.fromLegacyFormat(prefix),
                        AdventureSerializer.fromLegacyFormat(suffix),
                        WrapperPlayServerTeams.NameTagVisibility.NEVER,
                        WrapperPlayServerTeams.CollisionRule.NEVER,
                        net.kyori.adventure.text.format.NamedTextColor.WHITE,
                        WrapperPlayServerTeams.OptionData.NONE,
                    )
                    send(WrapperPlayServerTeams(OBJ + i, if (existed) WrapperPlayServerTeams.TeamMode.UPDATE else WrapperPlayServerTeams.TeamMode.CREATE, info, listOf(entry)))
                    if (i < lineSigs.size) lineSigs[i] = lineSig else lineSigs.add(lineSig)
                }
                send(WrapperPlayServerUpdateScore(entry, WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM, OBJ, Optional.of(shown.size - i)))
            }
            for (i in shown.size until shownLines) removeLine(i)
            while (lineSigs.size > shown.size) lineSigs.removeAt(lineSigs.size - 1)
            shownLines = shown.size
        } catch (t: Throwable) {
            if (!warned) { warned = true; plugin.logger.log(Level.WARNING, "Could not draw the replay scoreboard", t) }
        }
    }

    private fun removeLine(i: Int) {
        send(WrapperPlayServerUpdateScore(entryFor(i), WrapperPlayServerUpdateScore.Action.REMOVE_ITEM, OBJ, Optional.empty()))
        send(WrapperPlayServerTeams(OBJ + i, WrapperPlayServerTeams.TeamMode.REMOVE, null as WrapperPlayServerTeams.ScoreBoardTeamInfo?))
    }

    /**
     * Lobby scoreboard / tab plugins can put their own objective into the sidebar slot again (for instance when the
     * viewer changes world). Take the slot back so the replay sidebar stays on screen.
     */
    fun reassert() {
        if (!created) return
        send(WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, OBJ))
    }

    private fun hideSidebar() {
        if (created) {
            for (i in 0 until shownLines) removeLine(i)
            send(WrapperPlayServerScoreboardObjective(OBJ, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, net.kyori.adventure.text.Component.empty(), WrapperPlayServerScoreboardObjective.RenderType.INTEGER))
        }
        created = false
        shownLines = 0
        lastTitle = null
        lineSigs.clear()
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

    /**
     * Ends the HUD and hands the sidebar slot back to whatever the player had before the replay (the BedWars lobby
     * sidebar is drawn with packets the server does not know about, so it has to be pointed at again explicitly).
     */
    fun close() {
        try {
            clearTitles()
            val had = created
            hideSidebar()
            if (viewer.isOnline && had) {
                val original = ScoreboardRegistry.peek(viewer.uniqueId)?.sidebarObjective()
                if (original != null) send(WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, original))
            }
        } catch (_: Throwable) {
        }
    }
}
