package dev.etran.bedreplay.record

import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.util.adventure.AdventureSerializer
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a player's sidebar looks like right now, as plain legacy-formatted text. */
class SidebarView(val title: String, val lines: List<String>)

/** Name tag decoration the client applies to [name] through its scoreboard teams. */
class TagView(val prefix: String, val suffix: String, val hidden: Boolean)

/**
 * Rebuilds a player's client-side scoreboard from the packets they were sent.
 *
 * BedWars1058 draws its sidebar and name tags with packets (its own sidebar library), not with Bukkit
 * scoreboards, so reading `player.scoreboard` shows nothing useful. This follows the same packets the
 * client sees (objectives, display slot, scores, teams), so the in-game sidebar is exactly what the player saw.
 */
class ScoreboardTracker {
    private class Objective(var title: String) {
        val scores = LinkedHashMap<String, Row>()
    }

    private class Row(var value: Int, var custom: String?)
    private class Team(var prefix: String = "", var suffix: String = "", var hidden: Boolean = false) {
        val entries = HashSet<String>()
    }

    private val objectives = HashMap<String, Objective>()
    private val teams = HashMap<String, Team>()
    private var sidebar: String? = null

    @Volatile
    var seenSidebarPackets = false
        private set

    private fun legacy(c: net.kyori.adventure.text.Component?): String = if (c == null) "" else AdventureSerializer.toLegacyFormat(c)

    @Synchronized
    fun handle(event: PacketSendEvent): Boolean {
        when (event.packetType) {
            PacketType.Play.Server.SCOREBOARD_OBJECTIVE -> {
                val w = WrapperPlayServerScoreboardObjective(event)
                when (w.mode) {
                    WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE -> {
                        objectives.remove(w.name)
                        if (sidebar == w.name) sidebar = null
                    }
                    else -> {
                        val o = objectives.getOrPut(w.name) { Objective("") }
                        o.title = legacy(w.displayName)
                    }
                }
            }
            PacketType.Play.Server.DISPLAY_SCOREBOARD -> {
                val w = WrapperPlayServerDisplayScoreboard(event)
                if (w.position == SIDEBAR_SLOT) {
                    sidebar = w.scoreName.takeIf { it.isNotEmpty() }
                    seenSidebarPackets = true
                }
            }
            PacketType.Play.Server.UPDATE_SCORE -> {
                val w = WrapperPlayServerUpdateScore(event)
                if (w.action == WrapperPlayServerUpdateScore.Action.REMOVE_ITEM) {
                    if (w.objectiveName.isNullOrEmpty()) objectives.values.forEach { it.scores.remove(w.entityName) }
                    else objectives[w.objectiveName]?.scores?.remove(w.entityName)
                } else {
                    val o = objectives.getOrPut(w.objectiveName) { Objective("") }
                    val custom = w.entityDisplayName?.let { legacy(it) }
                    o.scores[w.entityName] = Row(w.value.orElse(0), custom)
                }
            }
            PacketType.Play.Server.RESET_SCORE -> {
                val w = WrapperPlayServerResetScore(event)
                val obj = w.objective
                if (obj.isNullOrEmpty()) objectives.values.forEach { it.scores.remove(w.targetName) }
                else objectives[obj]?.scores?.remove(w.targetName)
            }
            PacketType.Play.Server.TEAMS -> team(WrapperPlayServerTeams(event))
            else -> return false
        }
        return true
    }

    private fun team(w: WrapperPlayServerTeams) {
        val name = w.teamName
        when (w.teamMode) {
            WrapperPlayServerTeams.TeamMode.REMOVE -> teams.remove(name)
            WrapperPlayServerTeams.TeamMode.ADD_ENTITIES -> teams.getOrPut(name) { Team() }.entries.addAll(w.players)
            WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES -> teams[name]?.entries?.removeAll(w.players.toSet())
            else -> { // CREATE / UPDATE
                val t = teams.getOrPut(name) { Team() }
                w.teamInfo.ifPresent { info ->
                    t.prefix = legacy(info.prefix)
                    t.suffix = legacy(info.suffix)
                    t.hidden = info.tagVisibility == WrapperPlayServerTeams.NameTagVisibility.NEVER
                }
                if (w.teamMode == WrapperPlayServerTeams.TeamMode.CREATE) { t.entries.clear(); t.entries.addAll(w.players) }
            }
        }
    }

    /** The sidebar as it is drawn, or null when no sidebar is shown. */
    @Synchronized
    fun sidebar(): SidebarView? {
        val o = sidebar?.let { objectives[it] } ?: return null
        val owner = HashMap<String, Team>()
        for (t in teams.values) for (e in t.entries) owner[e] = t
        val rows = o.scores.entries.sortedWith(compareByDescending<Map.Entry<String, Row>> { it.value.value }.thenByDescending { it.key })
        val lines = rows.take(15).map { (entry, row) ->
            row.custom ?: run {
                val t = owner[entry]
                (t?.prefix ?: "") + entry + (t?.suffix ?: "")
            }
        }
        return SidebarView(o.title, lines)
    }

    @Synchronized
    fun tag(name: String): TagView? {
        val t = teams.values.firstOrNull { name in it.entries } ?: return null
        return TagView(t.prefix, t.suffix, t.hidden)
    }

    private companion object {
        const val SIDEBAR_SLOT = 1
    }
}

/** All trackers, keyed by player. Written on Netty threads, read on the main thread. */
object ScoreboardRegistry {
    private val trackers = ConcurrentHashMap<UUID, ScoreboardTracker>()
    fun of(uuid: UUID): ScoreboardTracker = trackers.computeIfAbsent(uuid) { ScoreboardTracker() }
    fun peek(uuid: UUID): ScoreboardTracker? = trackers[uuid]
    fun forget(uuid: UUID) { trackers.remove(uuid) }
    fun clear() = trackers.clear()
}
