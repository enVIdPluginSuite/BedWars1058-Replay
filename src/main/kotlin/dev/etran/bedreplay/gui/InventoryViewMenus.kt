package dev.etran.bedreplay.gui

import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.ItemSnapshot
import dev.etran.bedreplay.playback.PlaybackSession
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.inventory.ItemStack

/** Read-only views of recorded inventories, as they were at the current point in the replay. */
object InventoryViewMenus {
    private fun stack(s: ItemSnapshot?): ItemStack? = ServerCompat.toItemStack(s)

    /** A player's whole inventory: main grid, hotbar, armor and off hand. */
    fun openPlayer(s: PlaybackSession, rid: Int) {
        val inv = s.inventoryOf(rid)
        val menu = Menu("§8${s.playerName(rid)}'s inventory", 6)
        if (inv == null) {
            menu.set(22, Items.make(Items.PAPER, "§7No inventory recorded yet"))
        } else {
            // main inventory rows 0-2, hotbar row 3 (slot 0-8 of the hotbar are the first nine recorded slots)
            for (i in 9 until 36) stack(inv.items.getOrNull(i))?.let { menu.set(i - 9, it) }
            for (i in 0 until 9) stack(inv.items.getOrNull(i))?.let { menu.set(27 + i, it) }
            menu.fill(Items.make(Items.GRAY_PANE, " "), 36, 45)
            // armor, top to bottom order: helmet, chestplate, leggings, boots, then off hand
            val labels = listOf(39 to "helmet", 38 to "chestplate", 37 to "leggings", 36 to "boots", 40 to "off hand")
            labels.forEachIndexed { n, (slot, label) ->
                val it = stack(inv.items.getOrNull(slot))
                menu.set(45 + n, it ?: Items.make(Items.GRAY_PANE, "§7Empty $label"))
            }
        }
        menu.set(53, Items.make(Items.ARROW, "§7Back")) { p, _ -> PlayerPickerMenu.open(s) }
        menu.open(s.viewer)
    }

    /** A chest / furnace / hopper / ender chest as last seen before the current time. */
    fun openContainer(s: PlaybackSession, c: Action.Container) {
        val rows = ((c.items.size + 8) / 9).coerceIn(1, 6)
        val pretty = c.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        val title = if (c.owner >= 0) "§8${s.playerName(c.owner)}'s ender chest" else "§8$pretty"
        val menu = Menu(title, rows)
        c.items.take(rows * 9).forEachIndexed { i, snap -> stack(snap)?.let { menu.set(i, it) } }
        menu.open(s.viewer)
    }
}
