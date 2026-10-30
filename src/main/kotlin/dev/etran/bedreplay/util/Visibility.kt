package dev.etran.bedreplay.util

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Player#hidePlayer / showPlayer changed signature in 1.12.1; call whichever exists. */
object Visibility {
    fun set(plugin: Plugin, observer: Player, target: Player, visible: Boolean) {
        if (!observer.isOnline || !target.isOnline || observer.uniqueId == target.uniqueId) return
        val name = if (visible) "showPlayer" else "hidePlayer"
        try {
            Player::class.java.getMethod(name, Plugin::class.java, Player::class.java).invoke(observer, plugin, target)
        } catch (_: NoSuchMethodException) {
            try { Player::class.java.getMethod(name, Player::class.java).invoke(observer, target) } catch (_: Throwable) {}
        } catch (_: Throwable) {
        }
    }
}
