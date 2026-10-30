package dev.etran.bedreplay.gui

import dev.etran.bedreplay.format.MarkerKind
import dev.etran.bedreplay.playback.PlaybackSession
import dev.etran.bedreplay.record.RecordingManager

/** Quick jumps: fixed percentages plus every recorded event (kills, beds, eliminations). */
object TimelineMenu {
    /** Splits [text] into lore lines of at most ~38 visible characters, keeping colours across lines. */
    private fun wrap(text: String): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder("§f")
        var visible = 0
        for (word in text.split(' ')) {
            val w = org.bukkit.ChatColor.stripColor(word)?.length ?: word.length
            if (visible + w > 38 && visible > 0) {
                lines += cur.toString().trimEnd()
                cur = StringBuilder("§f" + org.bukkit.ChatColor.getLastColors(cur.toString()))
                visible = 0
            }
            cur.append(word).append(' ')
            visible += w + 1
        }
        if (visible > 0) lines += cur.toString().trimEnd()
        return lines
    }

    fun open(s: PlaybackSession) {
        val menu = Menu("§8Skip to a moment", 6)
        val pcts = listOf(0, 10, 25, 50, 75, 90)
        pcts.forEachIndexed { i, pct ->
            val sec = s.durationTicks / 20 * pct / 100
            menu.set(i, Items.make(Items.CLOCK, "§b$pct%", listOf("§7${RecordingManager.formatTime(sec)}"))) { p, _ ->
                s.seek(s.durationTicks * pct / 100); p.closeInventory()
            }
        }
        val events = s.markers
        events.take(36).forEachIndexed { i, (tick, m) ->
            val icon = when (m.kind) {
                MarkerKind.BED_DESTROYED -> Items.BED
                MarkerKind.FINAL_KILL -> Items.DIAMOND_SWORD
                MarkerKind.KILL -> Items.SWORD
                MarkerKind.TEAM_ELIMINATED -> Items.TNT
                MarkerKind.GAME_END -> Items.FIREWORK
                else -> Items.PAPER
            }
            val sec = tick / 20
            menu.set(9 + i, Items.make(icon, "§e${RecordingManager.formatTime(sec)}", wrap(s.room.markerLabel(m)) + listOf("", "§7Click to jump 3s before"))) { p, _ ->
                s.seek((tick - 60).coerceAtLeast(0)); p.closeInventory()
            }
        }
        if (events.isEmpty()) menu.set(22, Items.make(Items.PAPER, "§7No events recorded"))
        menu.set(53, Items.make(Items.CLOCK, "§bType a time", listOf("§7Close and type e.g. 3:45, +30, 50%"))) { p, _ ->
            p.closeInventory(); s.awaitingTime = true; p.sendMessage(s.config.msg("type-time"))
        }
        menu.open(s.viewer)
    }
}
