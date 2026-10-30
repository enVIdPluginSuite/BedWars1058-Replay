package dev.etran.bedreplay.playback

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

/**
 * A viewer's normal state, captured before playback takes over their inventory and flight.
 * It is also written to disk so that a crash mid-replay can never cost a player their items:
 * the file is restored the next time they join.
 */
class ViewerState(
    val gameMode: GameMode,
    val world: String,
    val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float,
    val contents: List<ItemStack?>,
    val armor: List<ItemStack?>,
    val allowFlight: Boolean,
    val flying: Boolean,
    val flySpeed: Float,
    val health: Double,
    val food: Int,
    val exp: Float,
    val level: Int,
) {
    fun location(): Location? = Bukkit.getWorld(world)?.let { Location(it, x, y, z, yaw, pitch) }

    fun apply(p: Player) {
        p.inventory.contents = contents.toTypedArray()
        p.inventory.armorContents = armor.toTypedArray()
        @Suppress("DEPRECATION") p.updateInventory()
        p.gameMode = gameMode
        p.allowFlight = allowFlight
        p.isFlying = allowFlight && flying
        p.flySpeed = flySpeed
        p.health = health.coerceIn(0.5, p.maxHealth)
        p.foodLevel = food
        p.exp = exp
        p.level = level
    }

    fun save(file: File) {
        val c = YamlConfiguration()
        c.set("gameMode", gameMode.name)
        c.set("world", world)
        c.set("x", x); c.set("y", y); c.set("z", z); c.set("yaw", yaw.toDouble()); c.set("pitch", pitch.toDouble())
        c.set("contents", contents)
        c.set("armor", armor)
        c.set("allowFlight", allowFlight); c.set("flying", flying); c.set("flySpeed", flySpeed.toDouble())
        c.set("health", health); c.set("food", food); c.set("exp", exp.toDouble()); c.set("level", level)
        file.parentFile.mkdirs()
        c.save(file)
    }

    companion object {
        fun capture(p: Player) = ViewerState(
            p.gameMode, p.world.name, p.location.x, p.location.y, p.location.z, p.location.yaw, p.location.pitch,
            p.inventory.contents.map { it?.clone() },
            p.inventory.armorContents.map { it?.clone() },
            p.allowFlight, p.isFlying, p.flySpeed, p.health, p.foodLevel, p.exp, p.level,
        )

        fun load(file: File): ViewerState? {
            if (!file.isFile) return null
            return try {
                val c = YamlConfiguration.loadConfiguration(file)
                fun items(key: String) = (c.getList(key) ?: emptyList<Any>()).map { it as? ItemStack }
                ViewerState(
                    runCatching { GameMode.valueOf(c.getString("gameMode") ?: "SURVIVAL") }.getOrDefault(GameMode.SURVIVAL),
                    c.getString("world") ?: return null,
                    c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                    c.getDouble("yaw").toFloat(), c.getDouble("pitch").toFloat(),
                    items("contents"), items("armor"),
                    c.getBoolean("allowFlight"), c.getBoolean("flying"), c.getDouble("flySpeed", 0.1).toFloat(),
                    c.getDouble("health", 20.0), c.getInt("food", 20), c.getDouble("exp").toFloat(), c.getInt("level"),
                )
            } catch (_: Throwable) {
                null
            }
        }

        fun fileFor(dir: File, uuid: UUID) = File(dir, "$uuid.yml")
    }
}
