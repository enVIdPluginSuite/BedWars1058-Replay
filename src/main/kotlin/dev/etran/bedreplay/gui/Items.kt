package dev.etran.bedreplay.gui

import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/** Builds menu / hotbar items that look right on 1.8 as well as on the newest versions. */
object Items {
    /** (material name, legacy data value). The first name that exists on this server wins. */
    class Mat(val name: String, val legacyData: Int = 0)

    fun make(candidates: List<Mat>, display: String, lore: List<String> = emptyList(), amount: Int = 1): ItemStack {
        val chosen = candidates.firstOrNull { Material.matchMaterial(it.name) != null }
        val material = chosen?.let { Material.matchMaterial(it.name) } ?: Material.STONE
        val data = if (!ServerCompat.modern) chosen?.legacyData ?: 0 else 0
        @Suppress("DEPRECATION")
        val stack = ItemStack(material, amount.coerceAtLeast(1), data.toShort())
        val meta = stack.itemMeta
        if (meta != null) {
            meta.displayName = display
            if (lore.isNotEmpty()) meta.lore = lore
            stack.itemMeta = meta
        }
        return stack
    }

    fun make(vararg names: String, display: String, lore: List<String> = emptyList(), amount: Int = 1) =
        make(names.map { Mat(it) }, display, lore, amount)

    fun head(owner: String, display: String, lore: List<String>): ItemStack {
        val stack = make(listOf(Mat("PLAYER_HEAD"), Mat("SKULL_ITEM", 3)), display, lore)
        val meta = stack.itemMeta
        if (meta is SkullMeta) {
            @Suppress("DEPRECATION")
            meta.owner = owner
            stack.itemMeta = meta
        }
        return stack
    }

    // Commonly used icons, with their pre-1.13 equivalents.
    val SLOWER = listOf(Mat("RED_DYE"), Mat("INK_SACK", 1))
    val FASTER = listOf(Mat("GREEN_DYE"), Mat("INK_SACK", 2))
    val PLAY = listOf(Mat("LIME_DYE"), Mat("INK_SACK", 10))
    val PAUSE = listOf(Mat("GRAY_DYE"), Mat("INK_SACK", 8))
    val ARROW = listOf(Mat("ARROW"))
    val CLOCK = listOf(Mat("CLOCK"), Mat("WATCH"))
    val COMPASS = listOf(Mat("COMPASS"))
    val BOOK = listOf(Mat("BOOK"))
    val EXIT = listOf(Mat("BARRIER"), Mat("REDSTONE_BLOCK"))
    val PAPER = listOf(Mat("PAPER"))
    val MAP = listOf(Mat("FILLED_MAP"), Mat("MAP"))
    val BED = listOf(Mat("RED_BED"), Mat("BED"))
    val SWORD = listOf(Mat("IRON_SWORD"))
    val DIAMOND_SWORD = listOf(Mat("DIAMOND_SWORD"))
    val TNT = listOf(Mat("TNT"))
    val FIREWORK = listOf(Mat("FIREWORK_ROCKET"), Mat("FIREWORK"))
    val LIME_PANE = listOf(Mat("LIME_STAINED_GLASS_PANE"), Mat("STAINED_GLASS_PANE", 5))
    val GRAY_PANE = listOf(Mat("GRAY_STAINED_GLASS_PANE"), Mat("STAINED_GLASS_PANE", 7))
    val NETHER_STAR = listOf(Mat("NETHER_STAR"))
    val ARROW_NAV = listOf(Mat("ARROW"))
    val CLOCK_ICON = CLOCK
    val EMERALD = listOf(Mat("EMERALD"))
    val BEACON = listOf(Mat("BEACON"))
    val LAVA = listOf(Mat("LAVA_BUCKET"))
    val CHEST = listOf(Mat("CHEST"))
}
