package dev.etran.bedreplay.util

import org.bukkit.Bukkit
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Where the files of a world live, which differs between server versions.
 *  - Up to 1.21.x a world is a folder in the server root: `<root>/<name>/region`.
 *  - From 26.1 Paper keeps every world, including the ones plugins create, inside the main level folder:
 *    `<level>/dimensions/minecraft/<name lower case>/region`. A map copied into the server root is ignored there and
 *    the world is generated from scratch, so a replay would play on an empty void.
 *
 * This mirrors the logic BedWars1058-MapLoader and BedWars1058-MultiGroup use for their own arena copies.
 */
object WorldLayout {
    private val DATA = listOf("region", "entities", "poi")

    /** Files that belong to a whole level (or to another server instance) and must not be carried into a copy. */
    private val JUNK = listOf(
        "dimensions", "DIM-1", "DIM1", "playerdata", "players", "stats", "advancements",
        "level.dat", "level.dat_old", "level.dat_mcr", "uid.dat", "session.lock",
    )

    /** Marker files other plugins use to recognise (and delete) their own disposable worlds. A copy must never carry them. */
    val FOREIGN_MARKERS = setOf(".maploader-runtime", ".maploader-setup", ".multigroup-runtime")

    /** Marker written into every replay world so leftovers can be recognised after a crash. */
    const val MARKER = ".bedreplay-world"

    /** True on Minecraft 26.1+ (versions are "26.3-R0.1-SNAPSHOT" there, "1.21.8-R0.1-SNAPSHOT" before). */
    val modern: Boolean by lazy {
        (Bukkit.getBukkitVersion().takeWhile { it.isDigit() }.toIntOrNull() ?: 1) >= 26
    }

    /** The folder of the main level ("world"). */
    fun levelDirectory(): File =
        try {
            val server = Bukkit.getServer()
            val p = server.javaClass.getMethod("getLevelDirectory").invoke(server) as Path
            val f = p.toFile()
            if (f.isAbsolute) f else File(Bukkit.getWorldContainer(), f.path)
        } catch (_: Throwable) {
            File(Bukkit.getWorldContainer(), Bukkit.getWorlds().first().name)
        }

    /** The name Bukkit knows a world by. On 26.1+ folders are lower case and spaces become underscores. */
    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").let { if (modern) it.lowercase(Locale.ENGLISH) else it }

    /** Where the files of the world called [name] live while it is loaded. */
    fun worldDir(name: String): File =
        if (!modern) File(Bukkit.getWorldContainer(), name)
        else File(levelDirectory(), "dimensions/minecraft/" + name.lowercase(Locale.ENGLISH).replace(' ', '_'))

    /** Folders that can hold world folders: the server root, plus the dimensions folder on 26.1+. */
    fun containers(): List<File> =
        if (modern) listOf(Bukkit.getWorldContainer(), File(levelDirectory(), "dimensions/minecraft"))
        else listOf(Bukkit.getWorldContainer())

    /** True for entries that must be left out when copying a map: foreign markers and per-instance lock files. */
    fun skip(relative: String): Boolean {
        val r = relative.replace('\\', '/').trimStart('/')
        return r in FOREIGN_MARKERS || r == MARKER || r == "uid.dat" || r == "session.lock" || r.endsWith("/session.lock")
    }

    /**
     * Makes a freshly copied map usable. On 26.1+ [dir] is the final dimension folder: the data is moved up from
     * `dimensions/minecraft/overworld` if the map was saved in the new layout, and files that belong to a whole
     * level are dropped. On older servers a map in the new layout is converted back to the old one.
     */
    fun normalize(dir: File) {
        val overworld = File(dir, "dimensions/minecraft/overworld")
        if (modern) {
            if (!File(dir, "region").isDirectory && File(overworld, "region").isDirectory) {
                DATA.forEach { move(File(overworld, it), File(dir, it)) }
            }
            JUNK.forEach { n ->
                val f = File(dir, n)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        } else if (!File(dir, "region").isDirectory && File(overworld, "region").isDirectory) {
            DATA.forEach { move(File(overworld, it), File(dir, it)) }
            File(dir, "dimensions").deleteRecursively()
        }
    }

    private fun move(from: File, to: File) {
        if (!from.exists() || to.exists()) return
        Files.move(from.toPath(), to.toPath())
    }
}
