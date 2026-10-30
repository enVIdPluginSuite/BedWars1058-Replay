package dev.etran.bedreplay.gui

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** A chest menu whose buttons are plain lambdas. Clicks are routed by [MenuListener]. */
class Menu(title: String, val rows: Int) : InventoryHolder {
    private val inv: Inventory = Bukkit.createInventory(this, rows * 9, title.take(32))
    private val handlers = HashMap<Int, (Player, ClickType) -> Unit>()

    override fun getInventory(): Inventory = inv

    fun set(slot: Int, item: ItemStack, onClick: ((Player, ClickType) -> Unit)? = null) {
        if (slot !in 0 until rows * 9) return
        inv.setItem(slot, item)
        if (onClick != null) handlers[slot] = onClick else handlers.remove(slot)
    }

    fun fill(item: ItemStack, from: Int, to: Int) {
        for (i in from until to) if (inv.getItem(i) == null) inv.setItem(i, item)
    }

    fun click(p: Player, slot: Int, type: ClickType) {
        handlers[slot]?.invoke(p, type)
    }

    fun open(p: Player) {
        p.openInventory(inv)
    }
}
