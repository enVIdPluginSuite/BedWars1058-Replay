package dev.etran.bedreplay.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

/** Makes every [Menu] read-only and dispatches button clicks. */
class MenuListener : Listener {
    @EventHandler(priority = EventPriority.HIGH)
    fun onClick(e: InventoryClickEvent) {
        val menu = e.view.topInventory.holder as? Menu ?: return
        e.isCancelled = true
        val p = e.whoClicked as? Player ?: return
        if (e.clickedInventory === e.view.topInventory) menu.click(p, e.rawSlot, e.click)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is Menu) e.isCancelled = true
    }
}
