package dev.etran.bedreplay.config

import org.bukkit.ChatColor
import org.bukkit.configuration.file.FileConfiguration

enum class StorageType { FILE, MYSQL }

class MySqlSettings(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
    val tablePrefix: String,
    val useSsl: Boolean,
)

class PluginConfig(private val c: FileConfiguration) {
    val storageType: StorageType = runCatching {
        StorageType.valueOf(c.getString("storage.type", "FILE")!!.trim().uppercase())
    }.getOrDefault(StorageType.FILE)

    val mysql = MySqlSettings(
        c.getString("storage.mysql.host", "localhost")!!,
        c.getInt("storage.mysql.port", 3306),
        c.getString("storage.mysql.database", "bedreplay")!!,
        c.getString("storage.mysql.user", "root")!!,
        c.getString("storage.mysql.password", "")!!,
        // restrict the prefix so it can be safely used inside table names
        c.getString("storage.mysql.table-prefix", "bedreplay_")!!.filter { it.isLetterOrDigit() || it == '_' },
        c.getBoolean("storage.mysql.use-ssl", false),
    )

    val recordingEnabled = c.getBoolean("recording.enabled", true)
    val minDurationSeconds = c.getInt("recording.min-duration-seconds", 20)
    val postGameSeconds = c.getInt("recording.post-game-seconds", 8)
    val flushIntervalSeconds = c.getInt("recording.flush-interval-seconds", 5).coerceAtLeast(1)
    val recordChat = c.getBoolean("recording.record-chat", true)
    val recordBlocks = c.getBoolean("recording.record-blocks", true)
    val recordEntities = c.getBoolean("recording.record-entities", true)
    val recordSounds = c.getBoolean("recording.record-sounds", true)
    val recordTitles = c.getBoolean("recording.record-titles", true)
    val recordInventories = c.getBoolean("recording.record-inventories", true)
    val recordScoreboards = c.getBoolean("recording.record-scoreboards", true)

    val retentionDefaultDays = c.getInt("retention.default-days", 14).coerceAtLeast(0)
    val allowKeepForever = c.getBoolean("retention.allow-keep-forever", true)
    val allowExtend = c.getBoolean("retention.allow-extend", true)
    val extendStepDays = c.getInt("retention.extend-step-days", 7).coerceAtLeast(1)
    /** 0 means "no cap". */
    val maxTotalDays = c.getInt("retention.max-total-days", 90).coerceAtLeast(0)
    val playersCanDelete = c.getBoolean("retention.players-can-delete", false)
    val cleanupIntervalMinutes = c.getInt("retention.cleanup-interval-minutes", 30).coerceAtLeast(1)

    val maxConcurrentPlayback = c.getInt("playback.max-concurrent", 5).coerceAtLeast(1)
    val worldPrefix = c.getString("playback.world-prefix", "bedreplay_")!!
    val speeds: List<Double> = c.getDoubleList("playback.speeds")
        .filter { it > 0.0 }.ifEmpty { listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0) }.sorted()
    val defaultSpeed = c.getDouble("playback.default-speed", 1.0)
    val skipSeconds = c.getInt("playback.skip-seconds", 10).coerceAtLeast(1)
    val playSounds = c.getBoolean("playback.play-sounds", true)
    /** OFF, NUMBER (below the name), HEARTS (a heart row after the name) or BOTH. */
    val healthDisplay = (c.getString("playback.health-display", "BOTH") ?: "BOTH").uppercase().let { if (it in setOf("OFF", "NUMBER", "HEARTS", "BOTH")) it else "BOTH" }
    /** While spectating, tell the client it is in spectator mode so no hand / item is drawn or swung. */
    val spectateClientMode = c.getBoolean("playback.spectate-client-mode", false)
    val sharingDefaultAllowJoin = c.getBoolean("sharing.default-allow-join", false)
    val sharingDefaultWhitelistOnly = c.getBoolean("sharing.default-whitelist-only", false)
    val sharingDefaultGuestSave = c.getBoolean("sharing.default-guest-save", false)
    val sharingAnnounce = c.getBoolean("sharing.announce-to-lobby", true)
    val sharingMaxViewers = c.getInt("sharing.max-viewers-per-replay", 20).coerceAtLeast(1)
    val returnWorld = c.getString("playback.return-world", "")!!

    val menuTitle = color(c.getString("menu.title", "&8Match Replays")!!)
    val adminMenuTitle = color(c.getString("menu.admin-title", "&8Match Replays &c(admin)")!!)

    private val messages = HashMap<String, String>().also { map ->
        // Messages added by a newer version are missing from an older config.yml on disk, so start from the bundled ones.
        c.defaults?.getConfigurationSection("messages")?.let { d -> d.getKeys(false).forEach { map[it] = d.getString(it) ?: "" } }
        val section = c.getConfigurationSection("messages")
        section?.getKeys(false)?.forEach { map[it] = section.getString(it) ?: "" }
    }

    /** Message by key with {placeholders} filled in, prefixed and colourised. */
    fun msg(key: String, vararg pairs: Pair<String, Any>, prefix: Boolean = true): String {
        var text = messages[key] ?: key
        for ((k, v) in pairs) text = text.replace("{$k}", v.toString())
        return color(if (prefix) (messages["prefix"] ?: "") + text else text)
    }

    companion object {
        fun color(s: String): String = ChatColor.translateAlternateColorCodes('&', s)
    }
}
