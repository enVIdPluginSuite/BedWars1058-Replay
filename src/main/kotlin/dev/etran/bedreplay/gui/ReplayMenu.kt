package dev.etran.bedreplay.gui

import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.playback.PlaybackController
import dev.etran.bedreplay.record.RecordingManager
import dev.etran.bedreplay.storage.ReplayRepository
import dev.etran.bedreplay.storage.StoredReplay
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The replay list. Players see matches they took part in; admins see every match (with a toggle
 * back to "mine"). The repository de-duplicates by id, so no match ever appears twice.
 */
class ReplayMenu(
    private val config: () -> PluginConfig,
    private val repo: ReplayRepository,
    private val playback: PlaybackController,
) {
    private val perPage = 45

    fun open(p: Player, page: Int = 0, allMatches: Boolean = false) {
        val admin = p.hasPermission("bedreplay.admin")
        val showAll = admin && allMatches
        val list = repo.visibleTo(p.uniqueId, showAll, ReplayServices.settings.get(p.uniqueId).savedSnapshot())
        val pages = ((list.size + perPage - 1) / perPage).coerceAtLeast(1)
        val pg = page.coerceIn(0, pages - 1)

        val menu = Menu(if (showAll) config().adminMenuTitle else config().menuTitle, 6)
        val fmt = SimpleDateFormat("MMM d, HH:mm")
        list.drop(pg * perPage).take(perPage).forEachIndexed { i, r ->
            menu.set(i, icon(r, fmt, p, showAll)) { who, click -> onClick(who, r, click, pg, showAll) }
        }
        if (list.isEmpty()) menu.set(22, Items.make(Items.PAPER, "§7No replays yet", listOf("§7Play a match and it shows up here.")))

        menu.fill(Items.make(Items.GRAY_PANE, " "), 45, 54)
        if (pg > 0) menu.set(45, Items.make(Items.ARROW, "§ePrevious page")) { w, _ -> open(w, pg - 1, showAll) }
        if (pg < pages - 1) menu.set(53, Items.make(Items.ARROW, "§eNext page")) { w, _ -> open(w, pg + 1, showAll) }
        menu.set(49, Items.make(Items.CHEST, "§bPage ${pg + 1}/$pages", listOf("§7${list.size} replay(s)")))
        if (admin) {
            menu.set(47, Items.make(Items.BEACON, if (showAll) "§cShowing: all matches" else "§aShowing: my matches",
                listOf("§7Click to switch"))) { w, _ -> open(w, 0, !showAll) }
        }
        menu.set(50, Items.make(Items.BOOK, "§bSharing settings", listOf("§7Who may join your replays,", "§7your whitelist, guest saving"))) { w, _ -> SharingMenu.open(w, null) }
        val joinable = ReplayServices.controller.rooms().count { ReplayServices.controller.mayJoin(p, it) }
        menu.set(51, Items.make(Items.BEACON, "§aActive replays §7($joinable)", listOf("§7Join a replay someone else", "§7is watching right now"))) { w, _ -> ActiveReplaysMenu.open(w) }
        menu.open(p)
    }

    private fun icon(r: StoredReplay, fmt: SimpleDateFormat, viewer: Player, showAll: Boolean): org.bukkit.inventory.ItemStack {
        val lore = ArrayList<String>()
        lore += "§7Played: §f" + fmt.format(Date(r.startedAtMillis))
        lore += "§7Length: §f" + RecordingManager.formatTime(r.durationSeconds)
        lore += "§7Players: §f" + r.players.size
        r.winnerTeam?.let { lore += "§7Winner: §f$it" }
        lore += "§7Expires: " + (r.daysLeft()?.let { "§f$it day(s)" } ?: "§aNever")
        if (showAll) lore += "§8" + r.id
        lore += ""
        lore += "§eClick: §7watch"
        if (canManage(viewer, r)) lore += "§eRight-click: §7keep / extend / delete"
        val won = r.hasParticipant(viewer.uniqueId) && r.winnerTeam != null &&
            r.players.firstOrNull { it.uuid.equals(viewer.uniqueId.toString(), true) }?.team == r.winnerTeam
        val icon = if (won) Items.EMERALD else Items.MAP
        return Items.make(icon, "§a${r.mapName}", lore)
    }

    private fun canManage(p: Player, r: StoredReplay): Boolean =
        p.hasPermission("bedreplay.admin") ||
            (r.hasParticipant(p.uniqueId) && (config().allowKeepForever || config().allowExtend || config().playersCanDelete))

    private fun onClick(p: Player, r: StoredReplay, click: ClickType, page: Int, showAll: Boolean) {
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
            if (canManage(p, r)) manage(p, r, page, showAll)
            return
        }
        p.closeInventory()
        playback.start(p, r)
    }

    private fun manage(p: Player, r: StoredReplay, page: Int, showAll: Boolean) {
        val cfg = config()
        val admin = p.hasPermission("bedreplay.admin")
        val menu = Menu("§8Manage: ${r.mapName}", 3)
        val cur = repo.get(r.id) ?: return open(p, page, showAll)

        if (admin || cfg.allowKeepForever) {
            if (cur.keepForever) {
                menu.set(10, Items.make(Items.NETHER_STAR, "§eStop keeping forever",
                    listOf("§7Replay will expire again", "§7after ${cfg.retentionDefaultDays.takeIf { it > 0 } ?: cfg.extendStepDays} days"))) { w, _ ->
                    repo.setExpiryAsync(r.id, repo.unkeepExpiry()) { u ->
                        w.sendMessage(cfg.msg("keep-off", "days" to (u?.daysLeft() ?: 0))); manage(w, r, page, showAll)
                    }
                }
            } else {
                menu.set(10, Items.make(Items.NETHER_STAR, "§aKeep forever", listOf("§7Never delete this replay"))) { w, _ ->
                    repo.setExpiryAsync(r.id, 0L) { _ -> w.sendMessage(cfg.msg("keep-on")); manage(w, r, page, showAll) }
                }
            }
        }
        if ((admin || cfg.allowExtend) && !cur.keepForever) {
            menu.set(12, Items.make(Items.CLOCK, "§bExtend +${cfg.extendStepDays} days",
                listOf("§7Currently expires in §f${cur.daysLeft()} §7day(s)"))) { w, _ ->
                val newExp = repo.extendedExpiry(cur)
                repo.setExpiryAsync(r.id, newExp) { u ->
                    w.sendMessage(cfg.msg("extended", "days" to (u?.daysLeft() ?: 0))); manage(w, r, page, showAll)
                }
            }
        }
        if (admin || cfg.playersCanDelete) {
            menu.set(14, Items.make(Items.LAVA, "§cDelete replay", listOf("§7Click twice to confirm"))) { w, _ -> confirmDelete(w, r, page, showAll) }
        }
        menu.set(22, Items.make(Items.ARROW, "§7Back")) { w, _ -> open(w, page, showAll) }
        menu.open(p)
    }

    private fun confirmDelete(p: Player, r: StoredReplay, page: Int, showAll: Boolean) {
        val menu = Menu("§8Delete this replay?", 3)
        menu.set(11, Items.make(Items.LIME_PANE, "§aYes, delete it")) { w, _ ->
            repo.deleteAsync(r.id) { ok ->
                w.sendMessage(if (ok) config().msg("deleted") else config().msg("not-found")); open(w, page, showAll)
            }
        }
        menu.set(15, Items.make(Items.GRAY_PANE, "§cCancel")) { w, _ -> manage(w, r, page, showAll) }
        menu.open(p)
    }
}
