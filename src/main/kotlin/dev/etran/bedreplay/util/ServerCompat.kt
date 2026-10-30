package dev.etran.bedreplay.util

import dev.etran.bedreplay.format.ItemSnapshot
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

/**
 * Everything that differs between Minecraft 1.8 and the newest versions lives here, so the rest of
 * the plugin can be written once against the 1.8 API. Lookups are reflective and cached; if a
 * lookup fails the helper degrades (returns null / does nothing) rather than throwing.
 */
object ServerCompat {
    /** True on 1.13+ ("the flattening": namespaced materials and BlockData). */
    val modern: Boolean = try {
        Class.forName("org.bukkit.block.data.BlockData"); true
    } catch (_: ClassNotFoundException) {
        false
    }

    /** Server version as reported by Bukkit, e.g. "1.8.8" or "1.21.4". */
    val version: String = Bukkit.getBukkitVersion().substringBefore('-')

    /**
     * True if the server is at least Minecraft [major].[minor]. Handles the new year-based scheme
     * (26.x counts as newer than any 1.x) as well as the classic 1.x.y one.
     */
    fun atLeast(major: Int, minor: Int): Boolean {
        val parts = version.split('.')
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val b = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return a > major || (a == major && b >= minor)
    }

    private val getMainHand = method(org.bukkit.inventory.PlayerInventory::class.java, "getItemInMainHand")
    private val getOffHand = method(org.bukkit.inventory.PlayerInventory::class.java, "getItemInOffHand")
    private val isGliding = method(org.bukkit.entity.LivingEntity::class.java, "isGliding")
    private val matchLegacy = try {
        Material::class.java.getMethod("matchMaterial", String::class.java, Boolean::class.javaPrimitiveType)
    } catch (_: Throwable) { null }
    private val setGameRule = World::class.java.methods.firstOrNull { it.name == "setGameRuleValue" }
    private val setGameRuleModern = World::class.java.methods.firstOrNull { it.name == "setGameRule" }

    private fun method(c: Class<*>, name: String, vararg params: Any): java.lang.reflect.Method? = try {
        val types = params.map { if (it is String) Class.forName(it) else it as Class<*> }.toTypedArray()
        c.getMethod(name, *types)
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ player state

    fun mainHand(p: Player): ItemStack? = try {
        getMainHand?.invoke(p.inventory) as ItemStack?
    } catch (_: Throwable) { null } ?: @Suppress("DEPRECATION") p.itemInHand

    fun offHand(p: Player): ItemStack? = try {
        getOffHand?.invoke(p.inventory) as ItemStack?
    } catch (_: Throwable) { null }

    fun gliding(p: Player): Boolean = try {
        isGliding?.invoke(p) as Boolean? ?: false
    } catch (_: Throwable) { false }

    fun maxHealth(p: Player): Double = try {
        @Suppress("DEPRECATION") p.maxHealth
    } catch (_: Throwable) { 20.0 }

    // ------------------------------------------------------------------ materials / items

    /** First material that exists on this server, to bridge renamed materials (WATCH -> CLOCK ...). */
    fun material(vararg names: String): Material {
        for (n in names) {
            val m = Material.matchMaterial(n)
            if (m != null) return m
        }
        return Material.STONE
    }

    fun snapshot(item: ItemStack?): ItemSnapshot? {
        if (item == null || item.type == Material.AIR || item.amount <= 0) return null
        var name: String? = null
        var color = -1
        val enchants = LinkedHashMap<String, Int>()
        try {
            val meta = item.itemMeta
            if (meta != null) {
                if (meta.hasDisplayName()) name = meta.displayName
                if (meta is LeatherArmorMeta) color = meta.color.asRGB()
            }
            for ((e, lvl) in item.enchantments) @Suppress("DEPRECATION") enchants[e.name] = lvl
        } catch (_: Throwable) {
            // some exotic items throw while reading meta; keep the bare stack
        }
        @Suppress("DEPRECATION")
        return ItemSnapshot(item.type.name, item.amount, item.durability.toInt(), name, enchants, color)
    }

    fun toItemStack(s: ItemSnapshot?): ItemStack? {
        if (s == null) return null
        val mat = Material.getMaterial(s.material)
            ?: legacyMaterial(s.material)
            ?: return null
        @Suppress("DEPRECATION")
        val stack = ItemStack(mat, s.amount.coerceAtLeast(1), s.durability.toShort())
        try {
            val meta = stack.itemMeta
            if (meta != null) {
                if (s.displayName != null) meta.displayName = s.displayName
                if (s.color != -1 && meta is LeatherArmorMeta) meta.color = Color.fromRGB(s.color and 0xFFFFFF)
                stack.itemMeta = meta
            }
            for ((name, lvl) in s.enchantments) {
                @Suppress("DEPRECATION")
                val e = Enchantment.getByName(name) ?: continue
                stack.addUnsafeEnchantment(e, lvl)
            }
        } catch (_: Throwable) {
        }
        return stack
    }

    /** Recording made on <=1.12 played on 1.13+: ask Bukkit to translate the old name. */
    private fun legacyMaterial(name: String): Material? {
        if (!modern) return null
        return try {
            matchLegacy?.invoke(null, name, true) as Material?
        } catch (_: Throwable) { null }
    }

    fun gameRule(world: World, rule: String, value: Boolean) {
        try {
            if (setGameRuleModern != null) {
                // 1.13+ takes a typed GameRule; look the constant up reflectively by its legacy string name
                val rules = Class.forName("org.bukkit.GameRule")
                val gr = rules.getMethod("getByName", String::class.java).invoke(null, rule)
                if (gr != null) { setGameRuleModern.invoke(world, gr, value); return }
            }
            @Suppress("DEPRECATION")
            setGameRule?.invoke(world, rule, value.toString())
        } catch (_: Throwable) {
        }
    }
}
