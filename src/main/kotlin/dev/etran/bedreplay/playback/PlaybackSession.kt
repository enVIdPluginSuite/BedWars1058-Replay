package dev.etran.bedreplay.playback

import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.gui.Items
import dev.etran.bedreplay.record.RecordingManager
import dev.etran.bedreplay.storage.StoredReplay
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.io.File

/**
 * One person's view of a [ReplayRoom]: who they follow, which chat and sidebar they see, their camera and hotbar.
 * The host controls the timeline; guests can look around, follow players and use the menu.
 */
class PlaybackSession(
    private val plugin: Plugin,
    val config: PluginConfig,
    val viewer: Player,
    val room: ReplayRoom,
    private val stateDir: File,
) {
    val hud = HudRenderer(plugin, viewer)
    private var saved: ViewerState? = null

    @Volatile var ended = false
    /** Replay id of the player this viewer is following, or null for the whole-match view. */
    var following: Int? = null
        private set
    var cameraMode = false
        private set
    private var slotBeforeCamera = 0
    /** True while the viewer is typing a time into chat. */
    var awaitingTime = false

    val isHost: Boolean get() = room.host === this
    val stored: StoredReplay get() = room.stored
    val world get() = room.world
    val cursor: Int get() = room.cursor
    val durationTicks: Int get() = room.durationTicks
    val paused: Boolean get() = room.paused
    val speed: Double get() = room.speed
    val markers: List<Pair<Int, Action.Marker>> get() = room.markers
    val roster: List<PlayerInfo> get() = room.roster

    // ------------------------------------------------------------------ entering and leaving

    fun enter(at: Location) {
        val file = ViewerState.fileFor(stateDir, viewer.uniqueId)
        saved = ViewerState.capture(viewer).also { it.save(file) }
        viewer.inventory.clear()
        viewer.inventory.armorContents = arrayOfNulls<ItemStack>(4)
        viewer.gameMode = GameMode.ADVENTURE
        viewer.allowFlight = true
        viewer.foodLevel = 20
        viewer.health = viewer.maxHealth
        viewer.teleport(at)
        // Changing world can reset flight (world-gamemode / multiworld plugins), so set it again afterwards.
        ensureFlight(force = true)
        giveControls()
    }

    /** Keeps the viewer able to fly around; other plugins may reset this when the world or game mode changes. */
    fun ensureFlight(force: Boolean) {
        try {
            if (viewer.gameMode != GameMode.ADVENTURE) viewer.gameMode = GameMode.ADVENTURE
            if (!viewer.allowFlight) viewer.allowFlight = true
            if (force && viewer.allowFlight) viewer.isFlying = true
        } catch (_: Throwable) {
        }
    }

    /** Puts the viewer back exactly as they were. */
    fun restore(dir: File) {
        if (!viewer.isOnline) return
        val state = saved ?: return
        val back = state.location()
            ?: Bukkit.getWorld(config.returnWorld)?.spawnLocation
            ?: Bukkit.getWorlds().first { it.uid != room.world.uid }.spawnLocation
        viewer.teleport(back)
        state.apply(viewer)
        ViewerState.fileFor(dir, viewer.uniqueId).delete()
    }

    /** Leaves the replay. For the host this also hands the room to someone else, or closes it. */
    fun stop(restore: Boolean = true) = room.leave(this, restore)

    fun onJoinedRoom() {
        showBoard()
        afterSeek()
    }

    /** Called after the room rebuilt its entities: re-point the camera and sidebar. */
    fun afterSeek() {
        showBoard()
        if (cameraMode) following?.let { room.attachCamera(this, room.entity(it)) }
    }

    // ------------------------------------------------------------------ what this viewer sees

    fun sees(recipients: IntArray?): Boolean {
        val f = following
        return if (f == null) recipients == null else (recipients == null || recipients.contains(f))
    }

    fun deliverChat(a: Action.Chat) {
        if (sees(a.recipients)) viewer.sendMessage(a.text)
    }

    fun playSound(a: Action.Sound) {
        var name = a.name
        // 1.8 knows sounds as "random.pop"; the namespace prefix only exists on 1.9+.
        if (!ServerCompat.atLeast(1, 9)) name = name.removePrefix("minecraft:")
        try {
            viewer.playSound(Location(room.world, a.x, a.y, a.z), name, a.volume, a.pitch)
        } catch (_: Throwable) {
        }
    }

    /** Whose scoreboard this viewer sees: the followed player's, otherwise the first player who had one. */
    fun boardOwner(): Int? =
        following ?: room.boards.entries.firstOrNull { it.value.title != null }?.key ?: room.boards.keys.minOrNull()

    fun showBoard() {
        val b = boardOwner()?.let { room.boards[it] }
        if (b == null) hud.sidebar(null, emptyList()) else hud.sidebar(b.title, b.lines)
    }

    // ------------------------------------------------------------------ timeline controls (host only)

    private fun hostOnly(): Boolean {
        if (isHost) return true
        viewer.sendMessage(config.msg("host-only"))
        return false
    }

    fun seek(target: Int) { if (hostOnly()) room.seek(target) }
    fun skipSeconds(seconds: Int) { if (hostOnly()) room.skipSeconds(seconds) }

    fun jumpToSeconds(seconds: Int) {
        if (!hostOnly()) return
        room.seek(seconds * 20)
        viewer.sendMessage(config.msg("jumped", "time" to RecordingManager.formatTime(seconds)))
    }

    fun togglePause() {
        if (!hostOnly()) return
        room.togglePause()
        viewer.sendMessage(config.msg(if (room.paused) "paused" else "resumed"))
    }

    fun faster() = change(+1)
    fun slower() = change(-1)

    private fun change(delta: Int) {
        if (!hostOnly()) return
        room.changeSpeed(delta)
        viewer.sendMessage(config.msg("speed", "speed" to room.speedLabel()))
    }

    // ------------------------------------------------------------------ hotbar

    fun giveControls() {
        val inv = viewer.inventory
        for (i in 0..8) inv.setItem(i, null)
        if (isHost) {
            val skip = config.skipSeconds
            val label = room.speedLabel()
            inv.setItem(0, Items.make(Items.SLOWER, "§c« Slower §7(${label}x)", listOf("§7Click to slow playback down")))
            inv.setItem(1, Items.make(Items.ARROW, "§eBack ${skip}s", listOf("§7Jump backwards")))
            inv.setItem(2, if (room.paused) Items.make(Items.PLAY, "§aPlay", listOf("§7Resume playback"))
                else Items.make(Items.PAUSE, "§ePause", listOf("§7Pause playback")))
            inv.setItem(3, Items.make(Items.ARROW, "§eForward ${skip}s", listOf("§7Jump forwards")))
            inv.setItem(4, Items.make(Items.FASTER, "§aFaster » §7(${label}x)", listOf("§7Click to speed playback up")))
            inv.setItem(5, Items.make(Items.CLOCK, "§bSkip to time", listOf("§7Left-click: type a time", "§7Right-click: pick a moment")))
        }
        inv.setItem(8, Items.make(Items.NETHER_STAR, "§bReplay menu", listOf(
            "§7Teleport to a player, match events,", "§7watch yourself, sharing, leave the replay",
        )))
        @Suppress("DEPRECATION") viewer.updateInventory()
    }

    // ------------------------------------------------------------------ players

    fun isAlive(rid: Int) = room.isAlive(rid)
    fun entity(rid: Int): Ent? = room.entity(rid)
    fun inventoryOf(rid: Int) = room.inventoryOf(rid)
    fun enderChestOf(rid: Int) = room.enderChestOf(rid)
    fun containerNear(x: Int, y: Int, z: Int) = room.containerNear(x, y, z)
    fun playerName(rid: Int) = room.playerName(rid)

    /** Other people watching this replay (not the replay's recorded players). */
    val otherViewers: List<PlaybackSession> get() = room.members.values.filter { it !== this }

    /** Replay id of the viewer's own player, if they took part in the match. */
    val selfId: Int? get() = roster.firstOrNull { it.uuid.equals(viewer.uniqueId.toString(), ignoreCase = true) }?.entityId

    fun teleportTo(rid: Int, camera: Boolean) {
        val e = room.entity(rid)
        val name = room.players[rid]?.name ?: e?.customName ?: e?.kind?.lowercase()?.replace('_', ' ') ?: "entity"
        setCameraHand(camera)
        if (e == null) {
            // Dead or waiting to respawn: follow anyway; the camera attaches as soon as they appear.
            following = rid
            cameraMode = camera
            showBoard()
            viewer.sendMessage(config.msg("following-waiting", "player" to name))
            return
        }
        viewer.teleport(Location(room.world, e.x, e.y + 0.1, e.z, e.yaw, e.pitch))
        following = rid
        cameraMode = camera
        room.attachCamera(this, if (camera) e else null)
        if (camera) room.renderers.showHeld(viewer, e)
        showBoard()
        viewer.sendMessage(config.msg("following", "player" to name))
        if (camera) viewer.sendMessage(config.msg("spectate-hint"))
    }

    /** Teleport next to another person watching the replay. */
    fun teleportToViewer(other: PlaybackSession) {
        if (other.ended || !other.viewer.isOnline) return
        if (cameraMode) stopFollowing()
        viewer.teleport(other.viewer.location)
    }

    /** In first person the viewer's selected slot must be an empty one (slot 7) so the hand shows the followed entity's item. */
    private fun setCameraHand(on: Boolean) {
        try {
            if (on && !cameraMode) {
                slotBeforeCamera = viewer.inventory.heldItemSlot
                viewer.inventory.heldItemSlot = CAMERA_SLOT
            } else if (!on && cameraMode) {
                viewer.inventory.heldItemSlot = slotBeforeCamera
                @Suppress("DEPRECATION") viewer.updateInventory()
            }
        } catch (_: Throwable) {
        }
    }

    fun stopFollowing() {
        setCameraHand(false)
        if (cameraMode) room.attachCamera(this, null)
        cameraMode = false
        following = null
        showBoard()
        viewer.sendMessage(config.msg("stopped-following"))
    }

    /** While in first-person view the viewer's real position must stay near the target so chunks keep loading. */
    fun keepNearFollowed() {
        val e = following?.let { room.entity(it) } ?: return
        val loc = viewer.location
        val dx = loc.x - e.x; val dz = loc.z - e.z
        if (dx * dx + dz * dz > 24 * 24) viewer.teleport(Location(room.world, e.x, e.y + 0.1, e.z, loc.yaw, loc.pitch))
    }
}
