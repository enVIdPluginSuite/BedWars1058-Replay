package dev.etran.bedreplay.record

import org.bukkit.entity.Player

/** Reads a player's skin texture without depending on any server implementation at compile time. */
object SkinLookup {
    /** Paper (1.12.2+): Player#getPlayerProfile().getProperties() -> ProfileProperty(name, value, signature). */
    fun paper(p: Player): Pair<String?, String?>? = try {
        val profile = p.javaClass.getMethod("getPlayerProfile").invoke(p)
        val props = profile.javaClass.getMethod("getProperties").invoke(profile) as Iterable<*>
        props.firstOrNull { it != null && call(it, "getName") == "textures" }?.let { prop ->
            val value = call(prop, "getValue")
            if (value == null) null else value to call(prop, "getSignature")
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * CraftPlayer#getProfile() returns Mojang's GameProfile, whose property map holds "textures".
     * authlib 5+ turned Property into a record (value() / signature()), older versions use getValue() / getSignature().
     */
    fun gameProfile(p: Player): Pair<String?, String?>? = try {
        val profile = p.javaClass.getMethod("getProfile").invoke(p)
        val map = profile.javaClass.getMethod("getProperties").invoke(profile)
        val values = map.javaClass.getMethod("get", Any::class.java).invoke(map, "textures") as? Iterable<*>
        values?.firstOrNull()?.let { prop ->
            val value = call(prop, "getValue") ?: call(prop, "value")
            if (value == null) null else value to (call(prop, "getSignature") ?: call(prop, "signature"))
        }
    } catch (_: Throwable) {
        null
    }

    private fun call(target: Any, method: String): String? = try {
        target.javaClass.getMethod(method).invoke(target) as? String
    } catch (_: Throwable) {
        null
    }
}
