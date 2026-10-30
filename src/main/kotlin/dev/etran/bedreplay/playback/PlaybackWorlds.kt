package dev.etran.bedreplay.playback

import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.Difficulty
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.logging.Level
import java.util.zip.ZipInputStream

/** Generates nothing: chunks missing from the map template stay empty instead of growing terrain. */
class VoidGenerator : ChunkGenerator() {
    override fun generateChunkData(world: World, random: Random, x: Int, z: Int, biome: BiomeGrid): ChunkData =
        createChunkData(world)
}

/**
 * Builds a private, throw-away copy of the arena map for one viewer: the zipped template BedWars
 * keeps in `plugins/BedWars1058/Cache/<arena>.zip` is extracted into a new world folder, loaded,
 * and deleted again when playback ends.
 */
object PlaybackWorlds {
    /**
     * Calls [done] on the main thread with the loaded world, or null if no template for the arena exists.
     */
    fun prepare(
        plugin: Plugin,
        bridge: BedWarsBridge,
        prefix: String,
        replayId: String,
        arena: String,
        done: (World?) -> Unit,
    ) {
        val zip = bridge.templateZip(arena)
        val name = prefix + replayId + "_" + UUID.randomUUID().toString().take(6)
        val folder = File(Bukkit.getWorldContainer(), name)

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val ok = try {
                if (zip != null) { unzip(zip, folder); true } else false
            } catch (t: Throwable) {
                plugin.logger.log(Level.WARNING, "Could not extract map template for $arena", t)
                folder.deleteRecursively()
                false
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!ok) { done(null); return@Runnable }
                done(load(plugin, name, folder))
            })
        })
    }

    private fun load(plugin: Plugin, name: String, folder: File): World? {
        // These files make the server think the folder belongs to another running instance.
        File(folder, "uid.dat").delete()
        File(folder, "session.lock").delete()
        return try {
            val creator = WorldCreator(name).generator(VoidGenerator()).generateStructures(false)
            val world = Bukkit.createWorld(creator) ?: return null
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

    /** Unloads and deletes a replay world. Players must already have been moved out. */
    fun destroy(plugin: Plugin, world: World) {
        val folder = world.worldFolder
        val name = world.name
        Bukkit.unloadWorld(world, false)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            if (!folder.deleteRecursively()) plugin.logger.warning("Could not fully delete replay world folder $name")
        })
    }

    /** After a crash, replay worlds can be left behind; remove them at startup. */
    fun cleanupLeftovers(plugin: Plugin, prefix: String) {
        if (prefix.isBlank()) return
        val dirs = Bukkit.getWorldContainer().listFiles { f -> f.isDirectory && f.name.startsWith(prefix) } ?: return
        if (dirs.isEmpty()) return
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            for (d in dirs) {
                if (Bukkit.getWorld(d.name) == null && d.deleteRecursively()) plugin.logger.info("Removed leftover replay world ${d.name}")
            }
        })
    }

    private fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(dest, entry.name).canonicalFile
                // zip-slip guard: an entry must stay inside the destination folder
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Blocked zip entry outside target: ${entry.name}")
                }
                if (entry.isDirectory) out.mkdirs()
                else {
                    out.parentFile.mkdirs()
                    out.outputStream().buffered().use { zin.copyTo(it) }
                }
            }
        }
    }
}
