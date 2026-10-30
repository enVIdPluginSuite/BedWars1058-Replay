package dev.etran.bedreplay.command

import dev.etran.bedreplay.BedReplayPlugin
import dev.etran.bedreplay.export.AdvancedReplayExporter
import dev.etran.bedreplay.gui.ActiveReplaysMenu
import dev.etran.bedreplay.gui.ReplayMenu
import dev.etran.bedreplay.gui.ReplayServices
import dev.etran.bedreplay.playback.PlaybackController
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** /replay [stop|reload|play <id>|export <id> [world]] */
class ReplayCommand(
    private val plugin: BedReplayPlugin,
    private val menu: () -> ReplayMenu,
    private val playback: PlaybackController,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, cmd: Command, label: String, args: Array<out String>): Boolean {
        val cfg = plugin.settings
        if (cmd.name.equals("replaysjoin", true) || cmd.name.equals("replaytransfer", true)) {
            return sharingCommand(sender, cmd.name.lowercase(), args)
        }
        val sub = args.firstOrNull()?.lowercase()
        if (sub == "reload") {
            if (!sender.hasPermission("bedreplay.admin")) { sender.sendMessage(cfg.msg("no-permission")); return true }
            plugin.reloadSettings()
            sender.sendMessage(plugin.settings.msg("reloaded"))
            return true
        }
        if (sub == "export") { export(sender, args); return true }
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }
        if (!sender.hasPermission("bedreplay.use")) { sender.sendMessage(cfg.msg("no-permission")); return true }
        when (sub) {
            "stop", "leave" -> playback.stop(sender)
            "play" -> {
                val id = args.getOrNull(1)
                val r = id?.let { plugin.repository.get(it) }
                val saved = ReplayServices.settings.get(sender.uniqueId).savedSnapshot()
                if (r == null || !(sender.hasPermission("bedreplay.admin") || r.hasParticipant(sender.uniqueId) || r.id in saved)) {
                    sender.sendMessage(cfg.msg("not-found"))
                } else playback.start(sender, r)
            }
            else -> {
                if (playback.isWatching(sender)) { sender.sendMessage(cfg.msg("no-permission")); return true }
                menu().open(sender)
            }
        }
        return true
    }

    private fun sharingCommand(sender: CommandSender, name: String, args: Array<out String>): Boolean {
        val cfg = plugin.settings
        if (sender !is Player) { sender.sendMessage("Only players can use this command."); return true }
        if (!sender.hasPermission("bedreplay.use")) { sender.sendMessage(cfg.msg("no-permission")); return true }
        if (name == "replaysjoin") {
            val who = args.firstOrNull()
            if (who == null) { ActiveReplaysMenu.open(sender); return true }
            val room = playback.roomOf(who)
            if (room == null) sender.sendMessage(cfg.msg("join-not-found")) else playback.join(sender, room)
        } else {
            val target = args.firstOrNull()
            if (target == null) sender.sendMessage(cfg.msg("transfer-usage")) else playback.transfer(sender, target)
        }
        return true
    }

    private val exporter = AdvancedReplayExporter()

    /** /replay export <id> [world] : writes an AdvancedReplay-compatible copy. Admin only. */
    private fun export(sender: CommandSender, args: Array<out String>) {
        val cfg = plugin.settings
        if (!sender.hasPermission("bedreplay.admin")) { sender.sendMessage(cfg.msg("no-permission")); return }
        if (exporter.plugin() == null) { sender.sendMessage(cfg.msg("export-no-advancedreplay")); return }
        val stored = args.getOrNull(1)?.let { plugin.repository.get(it) }
        if (stored == null) { sender.sendMessage(cfg.msg("not-found")); return }
        val world = args.getOrNull(2) ?: stored.arena
        sender.sendMessage(cfg.msg("export-started", "id" to stored.id))
        plugin.repository.loadAsync(stored.id) { replay ->
            if (replay == null) { sender.sendMessage(cfg.msg("load-failed")); return@loadAsync }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                val message = try {
                    val r = exporter.export(replay, stored.id, world)
                    val skipped = if (r.skipped.isEmpty()) "" else cfg.msg("export-skipped", "list" to r.skipped.entries.joinToString { "${it.value} ${it.key}" }, prefix = false)
                    cfg.msg("export-done", "name" to r.name, "world" to world) + skipped
                } catch (t: Throwable) {
                    plugin.logger.log(java.util.logging.Level.WARNING, "AdvancedReplay export failed", t)
                    cfg.msg("export-failed", "reason" to (t.message ?: t.javaClass.simpleName))
                }
                Bukkit.getScheduler().runTask(plugin, Runnable { sender.sendMessage(message) })
            })
        }
    }

    override fun onTabComplete(sender: CommandSender, cmd: Command, alias: String, args: Array<out String>): List<String> {
        if (cmd.name.equals("replaysjoin", true) && args.size == 1) {
            return playback.rooms().map { it.host.viewer.name }.filter { it.startsWith(args[0], true) }
        }
        if (cmd.name.equals("replaytransfer", true) && args.size == 1) {
            val s = (sender as? Player)?.let { playback.session(it) } ?: return emptyList()
            return s.otherViewers.map { it.viewer.name }.filter { it.startsWith(args[0], true) }
        }
        if (args.size == 1) {
            val base = mutableListOf("stop")
            if (sender.hasPermission("bedreplay.admin")) { base += "reload"; base += "export" }
            return base.filter { it.startsWith(args[0], true) }
        }
        if (args.size == 2 && args[0].equals("export", true) && sender.hasPermission("bedreplay.admin")) {
            return plugin.repository.snapshot().map { it.id }.filter { it.startsWith(args[1], true) }.take(30)
        }
        return emptyList()
    }
}
