package dev.etran.bedreplay.gui

import dev.etran.bedreplay.playback.PlaybackSession
import org.bukkit.event.inventory.ClickType

/** Pick a player to teleport to (left-click) or to watch in first person (right-click). */
object PlayerPickerMenu {
    fun open(s: PlaybackSession) {
        val roster = s.roster
        val viewers = s.otherViewers
        val rows = ((roster.size + viewers.size + 8) / 9 + 1).coerceIn(2, 6)
        val menu = Menu("§8Teleport to player", rows)
        roster.take((rows - 1) * 9).forEachIndexed { i, info ->
            val alive = s.isAlive(info.entityId)
            val lore = ArrayList<String>()
            lore += if (info.team != null) "§7Team: §f${info.team}" else "§7No team"
            lore += if (alive) "§aIn the match" else "§cDead or respawning §7(you will follow them when they return)"
            if (info.uuid.equals(s.viewer.uniqueId.toString(), ignoreCase = true)) lore += "§bThis is you"
            if (s.following == info.entityId) lore += "§eCurrently following"
            lore += ""
            lore += "§eLeft-click: §7teleport"
            lore += "§eRight-click: §7first-person view"
            lore += "§eShift-click: §7see their inventory"
            menu.set(i, Items.head(info.name, "§f${info.name}", lore)) { p, click ->
                if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    InventoryViewMenus.openPlayer(s, info.entityId); return@set
                }
                s.teleportTo(info.entityId, camera = click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT)
                p.closeInventory()
            }
        }
        var slot = roster.take((rows - 1) * 9).size
        for (v in viewers) {
            if (slot >= (rows - 1) * 9) break
            val lore = listOf(if (v.isHost) "§6Host of this replay" else "§bWatching with you", "", "§eClick: §7teleport to them")
            menu.set(slot++, Items.head(v.viewer.name, "§b${v.viewer.name} §7(viewer)", lore)) { p, _ ->
                s.teleportToViewer(v); p.closeInventory()
            }
        }
        val last = rows * 9
        menu.set(last - 9, Items.make(Items.CHEST, "§bEnder chest of followed player", listOf("§7Shows the team/ender chest", "§7of the player you follow"))) { p, _ ->
            val f = s.following
            val c = f?.let { s.enderChestOf(it) }
            if (c != null) InventoryViewMenus.openContainer(s, c) else p.sendMessage(s.config.msg("no-container"))
        }
        menu.set(last - 5, Items.make(Items.EXIT, "§cStop following", listOf("§7Go back to the whole-match view"))) { p, _ ->
            s.stopFollowing(); p.closeInventory()
        }
        menu.open(s.viewer)
    }
}
