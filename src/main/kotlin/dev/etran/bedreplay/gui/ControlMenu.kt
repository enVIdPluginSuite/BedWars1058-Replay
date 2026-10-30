package dev.etran.bedreplay.gui

import dev.etran.bedreplay.playback.PlaybackSession

/** The replay menu behind the nether-star: everything that is not a quick playback control. */
object ControlMenu {
    fun open(s: PlaybackSession) {
        val menu = Menu("§8Replay menu", 3)

        menu.set(10, Items.make(Items.COMPASS, "§bTeleport to player", listOf("§7Left-click: teleport", "§7Right-click: first-person view", "§7Shift-click: see their inventory"))) { _, _ ->
            PlayerPickerMenu.open(s)
        }
        menu.set(12, Items.make(Items.BOOK, "§bMatch events", listOf("§7Kills, bed breaks and eliminations", "§7plus quick jumps through the match"))) { _, _ ->
            TimelineMenu.open(s)
        }

        val self = s.selfId
        if (self != null) {
            menu.set(14, Items.head(s.viewer.name, "§aWatch yourself", listOf("§7First-person view of your own player", "§7in this match"))) { p, _ ->
                s.teleportTo(self, camera = true); p.closeInventory()
            }
        }
        if (s.following != null) {
            menu.set(15, Items.make(Items.PAPER, "§eStop following", listOf("§7Back to the free camera and whole-match chat"))) { p, _ ->
                s.stopFollowing(); p.closeInventory()
            }
        }
        val svc = ReplayServices
        if (s.isHost) {
            menu.set(13, Items.make(Items.BEACON, "§bSharing & guests", listOf(
                "§7Allow others to join, whitelist,", "§7transfer host. §f${s.room.members.size - 1}§7 guest(s) watching",
            ))) { p, _ -> SharingMenu.open(p, s) }
        } else {
            menu.set(11, Items.head(s.room.host.viewer.name, "§bHost: §f${s.room.host.viewer.name}", listOf(
                "§7Only the host controls playback.", "§f${s.room.members.size}§7 watching",
            )))
            val settings = svc.settings.get(s.viewer.uniqueId)
            val alreadyHas = s.stored.hasParticipant(s.viewer.uniqueId) || s.stored.id in settings.savedSnapshot()
            if (s.room.hostSettings.guestSave && !alreadyHas) {
                menu.set(13, Items.make(Items.EMERALD, "§aSave this replay", listOf("§7Add it to your own /replay list"))) { p, _ ->
                    settings.addSaved(s.stored.id); svc.settings.save(settings)
                    p.sendMessage(s.config.msg("saved")); open(s)
                }
            } else if (!alreadyHas) {
                menu.set(13, Items.make(Items.LAVA, "§7Saving not allowed", listOf("§7The host has not allowed guests to save")))
            }
        }
        menu.set(16, Items.make(Items.EXIT, "§cLeave replay", listOf("§7Return to where you were"))) { p, _ ->
            p.closeInventory(); s.stop()
        }
        menu.open(s.viewer)
    }
}
