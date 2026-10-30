package dev.etran.bedreplay

import com.github.retrooper.packetevents.PacketEvents
import dev.etran.bedreplay.bedwars.BedWarsBridge
import dev.etran.bedreplay.command.ReplayCommand
import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.config.StorageType
import dev.etran.bedreplay.gui.MenuListener
import dev.etran.bedreplay.gui.ReplayMenu
import dev.etran.bedreplay.gui.ReplayServices
import dev.etran.bedreplay.settings.PlayerSettingsStore
import dev.etran.bedreplay.playback.PlaybackController
import dev.etran.bedreplay.playback.PlaybackWorlds
import dev.etran.bedreplay.record.PacketCapture
import dev.etran.bedreplay.record.RecordingManager
import dev.etran.bedreplay.storage.FileReplayStorage
import dev.etran.bedreplay.storage.ReplayRepository
import dev.etran.bedreplay.storage.ReplayStorage
import dev.etran.bedreplay.storage.SqlReplayStorage
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File

class BedReplayPlugin : JavaPlugin() {
    @Volatile
    lateinit var settings: PluginConfig
        private set
    lateinit var repository: ReplayRepository
        private set

    private var recorder: RecordingManager? = null
    private var playback: PlaybackController? = null
    private var spectate: dev.etran.bedreplay.playback.SpectateListener? = null
    private var playerSettingsStore: PlayerSettingsStore? = null
    private var capture: PacketCapture? = null
    private var cleanupTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        settings = PluginConfig(config)

        val bridge = BedWarsBridge(this)
        dev.etran.bedreplay.playback.LobbyBoardGuard.bridge = bridge
        if (!bridge.detect()) {
            logger.severe("BedWars1058 (or BedWars2023) was not found; BedReplay cannot record or play matches.")
            server.pluginManager.disablePlugin(this)
            return
        }

        val storage: ReplayStorage = try {
            when (settings.storageType) {
                StorageType.MYSQL -> SqlReplayStorage(settings.mysql)
                StorageType.FILE -> FileReplayStorage(File(dataFolder, "replays"), logger)
            }
        } catch (t: Throwable) {
            logger.warning("Could not set up ${settings.storageType} storage (${t.message}); using local files instead.")
            FileReplayStorage(File(dataFolder, "replays"), logger)
        }
        repository = ReplayRepository(this, storage) { settings }
        try {
            repository.init()
        } catch (t: Throwable) {
            logger.severe("Replay storage failed to start: ${t.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        PlaybackWorlds.cleanupLeftovers(this, settings.worldPrefix)

        val rec = RecordingManager(this, { settings }, repository, bridge)
        bridge.callbacks = rec
        bridge.register()
        rec.start()
        recorder = rec

        val pc = PacketCapture(rec, logger, settings.recordChat, settings.recordBlocks, settings.recordSounds, settings.recordTitles, settings.recordScoreboards)
        PacketEvents.getAPI().eventManager.registerListener(pc)
        capture = pc

        val playerSettings = PlayerSettingsStore(this) { settings }
        playerSettingsStore = playerSettings
        val play = PlaybackController(this, { settings }, repository, bridge, playerSettings)
        playback = play
        ReplayServices.controller = play
        ReplayServices.settings = playerSettings
        ReplayServices.repo = repository
        val menu = ReplayMenu({ settings }, repository, play)
        server.pluginManager.registerEvents(play, this)
        spectate = dev.etran.bedreplay.playback.SpectateListener(this, play)
        PacketEvents.getAPI().eventManager.registerListener(spectate!!)
        server.pluginManager.registerEvents(MenuListener(), this)

        val cmd = ReplayCommand(this, { menu }, play)
        for (name in listOf("replay", "replaysjoin", "replaytransfer")) {
            getCommand(name)?.apply { setExecutor(cmd); tabCompleter = cmd }
        }

        scheduleCleanup()
        logger.info("BedWars1058-Replay enabled (${settings.storageType.name.lowercase()} storage).")
    }

    private fun scheduleCleanup() {
        cleanupTask?.cancel()
        val period = settings.cleanupIntervalMinutes * 60L * 20L
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, Runnable { repository.cleanupExpired() }, 200L, period)
    }

    /** Re-reads config.yml. Storage type changes need a restart. */
    fun reloadSettings() {
        reloadConfig()
        settings = PluginConfig(config)
        scheduleCleanup()
    }

    override fun onDisable() {
        cleanupTask?.cancel()
        // Viewers first (so nobody loses their inventory), then recordings (saved synchronously).
        playback?.shutdown()
        playerSettingsStore?.flushAll()
        spectate?.let { runCatching { PacketEvents.getAPI().eventManager.unregisterListener(it) } }
        capture?.let { runCatching { PacketEvents.getAPI().eventManager.unregisterListener(it) } }
        recorder?.shutdown()
        dev.etran.bedreplay.record.ScoreboardRegistry.clear()
        if (::repository.isInitialized) repository.close()
    }
}
