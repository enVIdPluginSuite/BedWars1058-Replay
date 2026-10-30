package dev.etran.bedreplay.settings

import dev.etran.bedreplay.config.PluginConfig
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Everything a player has chosen, remembered across sessions and restarts. Playback speed is deliberately not
 * part of it: every replay starts at the configured default speed.
 */
class PlayerSettings(val uuid: UUID) {
    /** Others may join replays this player is hosting. */
    @Volatile var allowJoin = false
    /** When joining is on: true = only [whitelist], false = anyone. */
    @Volatile var whitelistOnly = false
    /** Guests who were not in the match may add the replay to their own list. */
    @Volatile var guestSave = false
    /** uuid -> last known name, so offline players can still be listed and removed. */
    val whitelist = LinkedHashMap<UUID, String>()
    /** Replays this player saved from someone else's session (they were not in those matches). */
    val savedReplays = LinkedHashSet<String>()

    @Synchronized fun isWhitelisted(id: UUID) = whitelist.containsKey(id)
    @Synchronized fun addWhitelist(id: UUID, name: String) { whitelist[id] = name }
    @Synchronized fun removeWhitelist(id: UUID) { whitelist.remove(id) }
    @Synchronized fun whitelistSnapshot(): List<Pair<UUID, String>> = whitelist.map { it.key to it.value }
    @Synchronized fun addSaved(id: String) { savedReplays += id }
    @Synchronized fun savedSnapshot(): Set<String> = LinkedHashSet(savedReplays)

    /** May [guest] join a replay hosted by this player? (Admins are let in by the caller.) */
    fun mayJoin(guest: UUID): Boolean = allowJoin && (!whitelistOnly || isWhitelisted(guest))
}

class PlayerSettingsStore(private val plugin: Plugin, private val config: () -> PluginConfig) {
    private val dir = File(plugin.dataFolder, "playerdata")
    private val cache = ConcurrentHashMap<UUID, PlayerSettings>()

    fun get(uuid: UUID): PlayerSettings = cache.computeIfAbsent(uuid) { load(it) }

    private fun fileFor(uuid: UUID) = File(dir, "$uuid.yml")

    private fun load(uuid: UUID): PlayerSettings {
        val s = PlayerSettings(uuid)
        val c = config()
        s.allowJoin = c.sharingDefaultAllowJoin
        s.whitelistOnly = c.sharingDefaultWhitelistOnly
        s.guestSave = c.sharingDefaultGuestSave
        val f = fileFor(uuid)
        if (!f.isFile) return s
        try {
            val y = YamlConfiguration.loadConfiguration(f)
            s.allowJoin = y.getBoolean("allow-join", s.allowJoin)
            s.whitelistOnly = y.getBoolean("whitelist-only", s.whitelistOnly)
            s.guestSave = y.getBoolean("guest-save", s.guestSave)
            y.getConfigurationSection("whitelist")?.let { sec ->
                for (key in sec.getKeys(false)) runCatching { s.addWhitelist(UUID.fromString(key), sec.getString(key) ?: key) }
            }
            y.getStringList("saved-replays").forEach { s.addSaved(it) }
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not read ${f.name}; using defaults", t)
        }
        return s
    }

    private fun serialize(s: PlayerSettings): String {
        val y = YamlConfiguration()
        y.set("allow-join", s.allowJoin)
        y.set("whitelist-only", s.whitelistOnly)
        y.set("guest-save", s.guestSave)
        for ((id, name) in s.whitelistSnapshot()) y.set("whitelist.$id", name)
        y.set("saved-replays", s.savedSnapshot().toList())
        return y.saveToString()
    }

    /** Writes in the background; the snapshot is taken here so later changes cannot tear the file. */
    fun save(s: PlayerSettings) {
        val text = serialize(s)
        val target = fileFor(s.uuid)
        Bukkit_async { write(target, text) }
    }

    private fun Bukkit_async(block: () -> Unit) {
        if (plugin.isEnabled) plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable { block() }) else block()
    }

    private fun write(target: File, text: String) {
        try {
            target.parentFile.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(target)) { target.writeText(text); tmp.delete() }
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not save ${target.name}", t)
        }
    }

    /** Synchronous write of everything loaded, used on shutdown. */
    fun flushAll() {
        for (s in cache.values) write(fileFor(s.uuid), serialize(s))
    }
}
