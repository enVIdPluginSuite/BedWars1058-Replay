package dev.etran.bedreplay.gui

import dev.etran.bedreplay.playback.PlaybackSession
import dev.etran.bedreplay.playback.ReplayRoom
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

private fun onOff(v: Boolean) = if (v) "§aON" else "§cOFF"

private fun toggleItem(title: String, value: Boolean, vararg lore: String) =
    Items.make(if (value) Items.PLAY else Items.PAUSE, "$title: ${onOff(value)}", lore.toList() + listOf("", "§eClick to toggle"))

/** Who may join your replays, your whitelist, and whether guests may save. Choices are remembered per player. */
object SharingMenu {
    /** [s] is the replay the player is currently in (if any); the settings themselves always belong to the player. */
    fun open(p: Player, s: PlaybackSession?) {
        val svc = ReplayServices
        val settings = svc.settings.get(p.uniqueId)
        val cfg = svc.controller.settings.let { s?.config } ?: null
        val menu = Menu("§8Sharing settings", 3)

        menu.set(10, toggleItem("Allow others to join", settings.allowJoin,
            "§7Other players can watch your replays", "§7with you and teleport to you")) { who, _ ->
            settings.allowJoin = !settings.allowJoin
            svc.settings.save(settings)
            who.sendMessage(msg(s, "setting-allow-join", onOff(settings.allowJoin)))
            if (settings.allowJoin && s != null && s.isHost) svc.controller.announce(s.room)
            open(who, s)
        }
        menu.set(11, toggleItem("Whitelist only", settings.whitelistOnly,
            "§7ON: only whitelisted players can join", "§7OFF: anyone can join")) { who, _ ->
            settings.whitelistOnly = !settings.whitelistOnly
            svc.settings.save(settings)
            who.sendMessage(msg(s, "setting-whitelist-only", onOff(settings.whitelistOnly)))
            open(who, s)
        }
        menu.set(12, Items.make(Items.BOOK, "§bWhitelist §7(${settings.whitelistSnapshot().size})",
            listOf("§7Players who may always join", "§7Remembered between sessions"))) { who, _ -> WhitelistMenu.open(who, s) }
        menu.set(13, toggleItem("Guests may save", settings.guestSave,
            "§7Players who were not in the match can", "§7add the replay to their own list")) { who, _ ->
            settings.guestSave = !settings.guestSave
            svc.settings.save(settings)
            who.sendMessage(msg(s, "setting-guest-save", onOff(settings.guestSave)))
            open(who, s)
        }
        if (s != null && s.isHost && s.otherViewers.isNotEmpty()) {
            menu.set(15, Items.make(Items.NETHER_STAR, "§bTransfer host",
                listOf("§7Give control of the timeline", "§7to another viewer"))) { who, _ -> TransferMenu.open(who, s) }
        }
        menu.set(22, Items.make(Items.ARROW, "§7Back")) { who, _ -> if (s != null && !s.ended) ControlMenu.open(s) else who.closeInventory() }
        menu.open(p)
    }

    private fun msg(s: PlaybackSession?, key: String, value: String): String {
        val cfg = s?.config ?: return value
        return cfg.msg(key, "value" to value)
    }
}

object WhitelistMenu {
    fun open(p: Player, s: PlaybackSession?) {
        val svc = ReplayServices
        val settings = svc.settings.get(p.uniqueId)
        val menu = Menu("§8Replay whitelist", 6)

        val listed = settings.whitelistSnapshot()
        val entries = ArrayList<Pair<UUID, String>>(listed)
        // online players who are not on the list yet, to add with one click
        val online = Bukkit.getOnlinePlayers().filter { it.uniqueId != p.uniqueId && !settings.isWhitelisted(it.uniqueId) }
        var slot = 0
        for ((id, name) in entries.take(36)) {
            menu.set(slot++, Items.head(name, "§a$name", listOf("§7On your whitelist", "", "§eClick to remove"))) { who, _ ->
                settings.removeWhitelist(id); svc.settings.save(settings)
                who.sendMessage(s?.config?.msg("whitelist-removed", "player" to name) ?: name)
                open(who, s)
            }
        }
        for (o in online) {
            if (slot >= 45) break
            menu.set(slot++, Items.head(o.name, "§7${o.name}", listOf("§7Not whitelisted", "", "§eClick to add"))) { who, _ ->
                settings.addWhitelist(o.uniqueId, o.name); svc.settings.save(settings)
                who.sendMessage(s?.config?.msg("whitelist-added", "player" to o.name) ?: o.name)
                open(who, s)
            }
        }
        menu.fill(Items.make(Items.GRAY_PANE, " "), 45, 54)
        menu.set(45, Items.make(Items.ARROW, "§7Back")) { who, _ -> SharingMenu.open(who, s) }
        menu.set(49, Items.make(Items.PAPER, "§bAdd by name", listOf("§7Type the name of a player in chat", "§7(they must have joined before)"))) { who, _ ->
            who.closeInventory()
            val cfg = s?.config
            svc.controller.prompt(who, cfg?.msg("whitelist-prompt") ?: "Type a player name") { text ->
                val name = text.trim()
                if (!name.equals("cancel", true) && name.isNotEmpty()) addByName(who, name, s)
                open(who, s)
            }
        }
        menu.open(p)
    }

    private fun addByName(who: Player, name: String, s: PlaybackSession?) {
        val svc = ReplayServices
        val settings = svc.settings.get(who.uniqueId)
        val online = Bukkit.getPlayerExact(name)
        val off = online ?: Bukkit.getOfflinePlayer(name)
        if (online == null && !off.hasPlayedBefore()) {
            who.sendMessage(s?.config?.msg("whitelist-unknown") ?: "Unknown player")
            return
        }
        val shown = off.name ?: name
        settings.addWhitelist(off.uniqueId, shown); svc.settings.save(settings)
        who.sendMessage(s?.config?.msg("whitelist-added", "player" to shown) ?: shown)
    }
}

object TransferMenu {
    fun open(p: Player, s: PlaybackSession) {
        val others = s.otherViewers
        val menu = Menu("§8Transfer host to...", ((others.size + 8) / 9 + 1).coerceIn(2, 6))
        others.take(45).forEachIndexed { i, o ->
            menu.set(i, Items.head(o.viewer.name, "§f${o.viewer.name}", listOf("§7Click to make them the host"))) { who, _ ->
                who.closeInventory(); s.room.transfer(o)
            }
        }
        menu.set(menu.rows * 9 - 5, Items.make(Items.ARROW, "§7Back")) { who, _ -> SharingMenu.open(who, s) }
        menu.open(p)
    }
}

/** Replays other people are watching that you are allowed to join. */
object ActiveReplaysMenu {
    fun open(p: Player) {
        val svc = ReplayServices
        val rooms = svc.controller.rooms().filter { svc.controller.mayJoin(p, it) }
        val menu = Menu("§8Active replays", ((rooms.size + 8) / 9 + 1).coerceIn(2, 6))
        for ((i, room) in rooms.take(45).withIndex()) {
            menu.set(i, icon(room)) { who, _ -> who.closeInventory(); svc.controller.join(who, room) }
        }
        if (rooms.isEmpty()) menu.set(menu.rows * 9 / 2 - 5, Items.make(Items.PAPER, "§7No replays you can join right now"))
        menu.open(p)
    }

    private fun icon(room: ReplayRoom) = Items.make(Items.MAP, "§a${room.mapName}", listOf(
        "§7Host: §f${room.host.viewer.name}",
        "§7Watching: §f${room.members.size}",
        "§7Time: §f${dev.etran.bedreplay.record.RecordingManager.formatTime(room.cursor / 20)}",
        "", "§eClick to join",
    ))
}
