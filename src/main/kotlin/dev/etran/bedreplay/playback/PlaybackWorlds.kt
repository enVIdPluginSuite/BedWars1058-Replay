package dev.etran.bedreplay.playback

import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.bedwars.MapSource
import dev.etran.bedreplay.util.ServerCompat
import dev.etran.bedreplay.util.WorldLayout
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Random
import java.util.UUID
import java.util.logging.Level
import java.util.zip.ZipInputStream

/** Generates nothing: chunks missing from the map template stay empty instead of growing terrain. */
class VoidGenerator : ChunkGenerator() {
    override fun generateChunkData(world: World, random: Random, x: Int, z: Int, biome: BiomeGrid): ChunkData =
        createChunkData(world)

    override fun getFixedSpawnLocation(world: World, random: Random): Location = Location(world, 0.0, 64.0, 0.0)
}

/**
 * Builds a private, throw-away copy of the arena map for one viewer: the map is copied from wherever the BedWars
 * setup keeps it (see [dev.etran.bedreplay.bedwars.MapTemplates]) into a new world folder, loaded, and deleted again
 * when playback ends.
 */
object PlaybackWorlds {
    private val GENERATED = Regex(".+_[0-9a-f]{6}$")

    /**
     * Calls [done] on the main thread with the loaded world, or null if no map for the arena exists or it could not be
     * prepared. The reason is logged.
     */
    fun prepare(
        plugin: Plugin,
        bridge: BedWarsBridge,
        prefix: String,
        replayId: String,
        arena: String,
        group: String?,
        done: (World?) -> Unit,
    ) {
        val source = bridge.templates.find(arena, group)
        if (source == null) {
            plugin.logger.warning(
                "No map found for arena '$arena' (looked for ${bridge.templates.candidates(arena, group)} in the " +
                    "BedWars1058-MapLoader library, plugins/BedWars1058/Cache and the server root).",
            )
            done(null)
            return
        }
        val name = WorldLayout.safeName(prefix + replayId + "_" + UUID.randomUUID().toString().replace("-", "").take(6))
        val folder = WorldLayout.worldDir(name)
        if (Bukkit.getWorld(name) != null || folder.exists()) {
            plugin.logger.warning("Cannot create replay world $name: it already exists")
            done(null)
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val ok = try {
                copy(source, folder)
                true
            } catch (t: Throwable) {
                plugin.logger.log(Level.WARNING, "Could not copy the map of $arena from $source", t)
                folder.deleteRecursively()
                false
            }
            runSync(plugin) {
                if (!ok) { done(null); return@runSync }
                done(load(plugin, name, folder))
            }
        })
    }

    private fun load(plugin: Plugin, name: String, folder: File): World? {
        return try {
            val creator = WorldCreator(name).generator(VoidGenerator()).generateStructures(false)
            val world = Bukkit.createWorld(creator)
            if (world == null) {
                plugin.logger.warning("The server refused to create replay world $name")
                folder.deleteRecursively()
                return null
            }
            world.isAutoSave = false
            world.keepSpawnInMemory = false
            world.difficulty = Difficulty.PEACEFUL
            @Suppress("DEPRECATION") world.setSpawnFlags(false, false)
            world.setStorm(false)
            world.time = 6000
            for (rule in listOf("doDaylightCycle", "doMobSpawning", "doWeatherCycle", "doFireTick", "mobGriefing")) {
                ServerCompat.gameRule(world, rule, false)
            }
            world
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not load replay world $name", t)
            folder.deleteRecursively()
            null
        }
    }

    /**
     * Unloads and deletes a replay world. Anybody still inside is moved out first; the folder is only deleted once the
     * server really let go of the world, so a world that is still in use is never pulled from under its players.
     * While the plugin is shutting down nothing can be scheduled, so everything happens right here.
     */
    fun destroy(plugin: Plugin, world: World, attempt: Int = 0) {
        val folder = world.worldFolder
        val name = world.name
        evacuate(world)
        val unloaded = try { Bukkit.unloadWorld(world, false) } catch (t: Throwable) { false }
        if (!unloaded && Bukkit.getWorld(name) != null) {
            if (attempt < MAX_UNLOAD_ATTEMPTS && plugin.isEnabled) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { destroy(plugin, world, attempt + 1) }, 10L)
            } else {
                plugin.logger.warning("Replay world $name could not be unloaded; its folder is removed on the next start.")
            }
            return
        }
        if (!plugin.isEnabled) {
            deleteFolder(plugin, folder, name)
            return
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable { deleteFolder(plugin, folder, name) })
    }

    private fun deleteFolder(plugin: Plugin, folder: File, name: String) {
        if (folder.exists() && !deleteRecursively(folder.toPath())) {
            plugin.logger.warning("Could not fully delete replay world folder $name; it is removed on the next start.")
        }
    }

    /** Moves everybody out of [world] to the main world's spawn. */
    private fun evacuate(world: World) {
        val others = Bukkit.getWorlds().firstOrNull { it.uid != world.uid } ?: return
        for (p in ArrayList(world.players)) {
            try { p.teleport(others.spawnLocation) } catch (_: Throwable) { }
        }
    }

    /** After a crash, replay worlds can be left behind; remove them at startup. */
    fun cleanupLeftovers(plugin: Plugin, prefix: String) {
        val p = WorldLayout.safeName(prefix)
        if (p.isBlank()) return
        val dirs = WorldLayout.containers().flatMap { c ->
            c.listFiles { f -> f.isDirectory && f.name.lowercase().startsWith(p.lowercase()) }?.toList() ?: emptyList()
        }.filter { d ->
            // only folders that are recognisably ours: a marker file, or the exact shape of a generated name
            File(d, WorldLayout.MARKER).exists() || GENERATED.matches(d.name)
        }
        if (dirs.isEmpty()) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            for (d in dirs) {
                if (Bukkit.getWorld(d.name) != null) continue
                if (deleteRecursively(d.toPath())) plugin.logger.info("Removed leftover replay world ${d.name}")
            }
        })
    }

    // ------------------------------------------------------------------ copying

    private fun copy(source: MapSource, dest: File) {
        dest.mkdirs()
        // Written first: if the server dies mid-copy, the half-written folder is still recognised as ours.
        File(dest, WorldLayout.MARKER).writeText(System.currentTimeMillis().toString())
        when (source) {
            is MapSource.Zip -> unzip(source.file, dest)
            is MapSource.Folder -> copyDirectory(source.file.toPath(), dest.toPath())
        }
        WorldLayout.normalize(dest)
        // normalize() removes level-wide files, never our marker; make sure it survived anyway
        val marker = File(dest, WorldLayout.MARKER)
        if (!marker.exists()) marker.writeText(System.currentTimeMillis().toString())
    }

    private fun copyDirectory(from: Path, to: Path) {
        Files.walkFileTree(from, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val rel = from.relativize(file).toString()
                if (!WorldLayout.skip(rel)) Files.copy(file, to.resolve(rel), StandardCopyOption.REPLACE_EXISTING)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun unzip(zip: File, dest: File) {
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                // zip-slip guard: an entry must stay inside the destination folder
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Blocked zip entry outside target: ${entry.name}")
                }
                if (WorldLayout.skip(entry.name)) continue
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile.mkdirs()
                    out.outputStream().buffered().use { zin.copyTo(it) }
                }
            }
        }
    }

    private fun deleteRecursively(path: Path): Boolean {
        return try {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Runs [block] on the main thread; inline if the scheduler no longer accepts tasks (server shutting down). */
    private fun runSync(plugin: Plugin, block: () -> Unit) {
        if (!plugin.isEnabled) { block(); return }
        Bukkit.getScheduler().runTask(plugin, Runnable(block))
    }

    private const val MAX_UNLOAD_ATTEMPTS = 20
}
