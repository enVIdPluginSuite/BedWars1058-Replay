package dev.etran.bedreplay.bedwars

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Locale

/** Where the pristine copy of an arena's map can be copied from. */
sealed class MapSource(val file: File, val origin: String) {
    class Folder(file: File, origin: String) : MapSource(file, origin)
    class Zip(file: File, origin: String) : MapSource(file, origin)

    override fun toString() = "$origin: ${file.path}"
}

/**
 * Finds the map of an arena, whatever plugin keeps it:
 *  1. BedWars1058-MapLoader's library (`storage.path`, a world folder or a zip per map),
 *  2. BedWars1058's own pristine copies (`plugins/BedWars1058/Cache/<map>.zip`),
 *  3. a plain world folder in the server root (very old setups).
 *
 * Variant arenas made by BedWars1058-MultiGroup play another arena's map, so the arena name is first translated to the
 * name of its base map. Everything is looked up by name and reflection with JDK types only: none of these plugins is a
 * compile-time dependency and each of them relocates its own Kotlin.
 *
 * All methods are safe to call from the main thread; none touches a world.
 */
class MapTemplates(private val owner: Plugin) {

    /** Names an arena's map may be stored under, most specific first. */
    fun candidates(arena: String, group: String?): List<String> {
        val out = LinkedHashSet<String>()
        multiGroupBase(arena)?.let { out += it }
        mapLoaderAlias(arena)?.let { out += it }
        out += arena
        // Last resort when the variant is gone from MultiGroup's configuration: its default name is "<map>-<group>".
        if (!group.isNullOrBlank()) {
            val suffix = "-" + group.lowercase(Locale.ROOT)
            if (arena.lowercase(Locale.ROOT).endsWith(suffix) && arena.length > suffix.length) {
                out += arena.substring(0, arena.length - suffix.length)
            }
        }
        return out.toList()
    }

    /** The first map found for [arena], or null if none of the known places has it. */
    fun find(arena: String, group: String?): MapSource? {
        val names = candidates(arena, group)
        for (n in names) {
            libraryDir()?.let { dir -> findIn(dir, n, "MapLoader library")?.let { return it } }
            bedWarsCache()?.let { dir -> findIn(dir, n, "BedWars cache")?.let { return it } }
            rootFolder(n)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ name resolution

    /** BedWars1058-MultiGroup knows which map each of its variant arenas plays. */
    private fun multiGroupBase(arena: String): String? {
        val mg = Bukkit.getPluginManager().getPlugin(MULTIGROUP) ?: return null
        val registry = Reflect.call(mg, "getRegistry") ?: return null
        return (Reflect.call(registry, "baseOf", arena) as? String)?.takeIf { it.isNotBlank() }
    }

    /** MapLoader's alias hook, for whatever other add-on registered one. */
    private fun mapLoaderAlias(arena: String): String? {
        val ml = Bukkit.getPluginManager().getPlugin(MAPLOADER) ?: return null
        for (cn in ALIAS_CLASSES) {
            val cls = runCatching { Class.forName(cn, true, ml.javaClass.classLoader) }.getOrNull() ?: continue
            val r = runCatching { cls.getMethod("resolve", String::class.java).invoke(null, arena) as? String }.getOrNull()
            if (!r.isNullOrBlank() && !r.equals(arena, ignoreCase = true)) return r
        }
        return null
    }

    // ------------------------------------------------------------------ places

    /** MapLoader's library folder, read from the running plugin or, failing that, from its config.yml. */
    private fun libraryDir(): File? {
        val ml = Bukkit.getPluginManager().getPlugin(MAPLOADER) ?: return null
        try {
            val services = Reflect.call(ml, "getServices")
            val store = Reflect.call(services, "getStore")
            val dir = Reflect.call(store, "getStorageDir")
            if (dir is java.nio.file.Path) return dir.toFile()
        } catch (_: Throwable) {
            // fall back to the config file
        }
        val raw = try {
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(File(ml.dataFolder, "config.yml"))
                .getString("storage.path")
        } catch (_: Throwable) { null } ?: "bedwars_worlds"
        val f = File(raw)
        return if (f.isAbsolute) f else File(Bukkit.getWorldContainer(), raw)
    }

    private fun bedWarsCache(): File? {
        val bw = BedWarsBridge.CANDIDATES.firstNotNullOfOrNull { Bukkit.getPluginManager().getPlugin(it.first) } ?: return null
        return File(bw.dataFolder, "Cache")
    }

    private fun findIn(dir: File, name: String, origin: String): MapSource? {
        val kids = dir.listFiles() ?: return null
        val lower = name.lowercase(Locale.ROOT)
        kids.firstOrNull { it.isDirectory && it.name.lowercase(Locale.ROOT) == lower && looksLikeWorld(it) }
            ?.let { return MapSource.Folder(it, origin) }
        kids.firstOrNull { it.isFile && it.name.lowercase(Locale.ROOT) == "$lower.zip" }
            ?.let { return MapSource.Zip(it, origin) }
        return null
    }

    /** A world folder in the server root that no plugin created for a match and that is not one of ours. */
    private fun rootFolder(name: String): MapSource? {
        val f = File(Bukkit.getWorldContainer(), name)
        if (!f.isDirectory || !looksLikeWorld(f)) return null
        if (Bukkit.getWorld(name) != null) return null // a live arena: not pristine
        if (FOREIGN_AND_OWN.any { File(f, it).exists() }) return null
        return MapSource.Folder(f, "server root")
    }

    private fun looksLikeWorld(dir: File) =
        File(dir, "region").isDirectory || File(dir, "level.dat").isFile || File(dir, "dimensions").isDirectory

    companion object {
        const val MAPLOADER = "BedWars1058-MapLoader"
        const val MULTIGROUP = "BedWars1058-MultiGroup"

        /** The package was renamed between MapLoader versions; MultiGroup still asks for the old one. */
        private val ALIAS_CLASSES = listOf("dev.etran.api.MapAliases", "dev.bwworldloader.api.MapAliases")

        private val FOREIGN_AND_OWN = dev.etran.bedreplay.util.WorldLayout.FOREIGN_MARKERS + dev.etran.bedreplay.util.WorldLayout.MARKER
    }
}
