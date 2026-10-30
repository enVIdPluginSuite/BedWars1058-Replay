package dev.etran.bedreplay.bedwars

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Reflection bridge to BedWars1058 (and the BedWars2023 fork, which uses another package root).
 *
 * Why reflection instead of a compile-time dependency: the BedWars1058 API artifact is not
 * published anywhere reachable right now, and a reflective hook keeps working across forks and
 * versions. Every call is wrapped so a changed signature degrades a feature instead of crashing.
 */
internal object Reflect {
    private val cache = ConcurrentHashMap<String, Method?>()

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        // Overloads (e.g. isReSpawning(Player) vs isReSpawning(UUID)) must be told apart by argument type.
        val key = "${target.javaClass.name}#$name/" + args.joinToString(",") { it?.javaClass?.name ?: "null" }
        val m = cache.getOrPut(key) {
            target.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterCount == args.size &&
                    m.parameterTypes.indices.all { i -> args[i] == null || wrap(m.parameterTypes[i]).isInstance(args[i]) }
            }?.also { runCatching { it.isAccessible = true } }
        } ?: return null
        return try {
            m.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    private fun wrap(c: Class<*>): Class<*> = when (c) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        else -> c
    }

    fun str(target: Any?, name: String): String? = call(target, name) as? String

    @Suppress("UNCHECKED_CAST")
    fun <T> list(target: Any?, name: String): List<T> =
        (call(target, name) as? Collection<*>)?.toList() as? List<T> ?: emptyList()
}

class TeamRef(val handle: Any) {
    val name: String get() = Reflect.str(handle, "getName") ?: "?"
    val bedDestroyed: Boolean get() = Reflect.call(handle, "isBedDestroyed") as? Boolean ?: false
    val members: List<Player> get() = Reflect.list(handle, "getMembers")

    /** Location of the team's bed (its head block), or null if the plugin does not expose one. */
    val bed: org.bukkit.Location? get() = Reflect.call(handle, "getBed") as? org.bukkit.Location

    /** Team colour as 0xRRGGBB, or -1 if it cannot be determined. */
    val rgb: Int
        get() {
            val color = Reflect.call(handle, "getColor") ?: return -1
            // TeamColor.getColor(TeamColor) -> org.bukkit.Color in BedWars1058
            runCatching {
                val m = color.javaClass.methods.firstOrNull { it.name == "getColor" && it.parameterCount == 1 }
                val c = m?.invoke(null, color)
                if (c is org.bukkit.Color) return c.asRGB()
            }
            return NAMED[color.toString().uppercase()] ?: -1
        }

    private companion object {
        val NAMED = mapOf(
            "RED" to 0xFF5555, "BLUE" to 0x5555FF, "GREEN" to 0x55FF55, "YELLOW" to 0xFFFF55,
            "AQUA" to 0x55FFFF, "WHITE" to 0xFFFFFF, "PINK" to 0xFF55FF, "GRAY" to 0xAAAAAA,
            "ORANGE" to 0xFFAA00, "PURPLE" to 0xAA00AA, "CYAN" to 0x00AAAA, "LIME" to 0x55FF55,
        )
    }
}

/** Thin handle on a BedWars arena object. Two refs to the same arena are equal. */
class ArenaRef(val handle: Any) {
    val name: String get() = Reflect.str(handle, "getArenaName") ?: "?"
    val worldName: String get() = Reflect.str(handle, "getWorldName") ?: name
    val group: String? get() = Reflect.str(handle, "getGroup")
    val displayName: String get() = Reflect.str(handle, "getDisplayName") ?: name
    val status: String get() = Reflect.call(handle, "getStatus")?.toString() ?: ""
    val world: World? get() = Reflect.call(handle, "getWorld") as? World
    val players: List<Player> get() = Reflect.list(handle, "getPlayers")
    val spectators: List<Player> get() = Reflect.list(handle, "getSpectators")
    val teams: List<TeamRef> get() = Reflect.list<Any>(handle, "getTeams").map(::TeamRef)

    fun teamOf(p: Player): TeamRef? = Reflect.call(handle, "getTeam", p)?.let(::TeamRef)

    /** Dead players waiting to respawn stay in [players] but are hidden and parked elsewhere. */
    fun isRespawning(p: Player): Boolean = Reflect.call(handle, "isReSpawning", p) as? Boolean ?: false

    override fun equals(other: Any?) = other is ArenaRef && other.handle === handle
    override fun hashCode() = System.identityHashCode(handle)
}

interface BedWarsCallbacks {
    fun onStateChange(arena: ArenaRef, newState: String) {}
    fun onGameEnd(arena: ArenaRef, winnerTeam: String?) {}
    fun onKill(arena: ArenaRef, victim: Player, killer: Player?, finalKill: Boolean, message: String?) {}
    fun onBedBreak(arena: ArenaRef, breaker: Player, victimTeam: String) {}
    fun onTeamEliminated(arena: ArenaRef, team: String) {}
    fun onRespawn(arena: ArenaRef, player: Player) {}
    fun onLeave(arena: ArenaRef, player: Player) {}
    fun onArenaDisabled(arenaName: String) {}
}

class BedWarsBridge(private val plugin: Plugin) {
    /** Set before [register]; the recorder needs the bridge and the bridge reports to the recorder. */
    lateinit var callbacks: BedWarsCallbacks

    private val listener = object : Listener {}
    private var apiPackage: String? = null
    private var loader: ClassLoader? = null

    /** True if a supported BedWars plugin is installed and its API classes are loadable. */
    fun detect(): Boolean {
        for ((pluginName, pkg) in CANDIDATES) {
            val p = Bukkit.getPluginManager().getPlugin(pluginName) ?: continue
            val cl = p.javaClass.classLoader
            if (runCatching { Class.forName("$pkg.arena.IArena", false, cl) }.isSuccess) {
                apiPackage = pkg
                loader = cl
                return true
            }
        }
        return false
    }

    fun register() {
        val pkg = apiPackage ?: return
        fun on(clazz: String, handler: (Any) -> Unit) {
            val cls = try {
                @Suppress("UNCHECKED_CAST")
                Class.forName("$pkg.events.$clazz", false, loader) as Class<out Event>
            } catch (e: Throwable) {
                plugin.logger.warning("BedWars event $clazz not found, related markers disabled")
                return
            }
            Bukkit.getPluginManager().registerEvent(
                cls, listener, EventPriority.MONITOR,
                EventExecutor { _, event ->
                    try { handler(event) } catch (t: Throwable) {
                        plugin.logger.log(Level.WARNING, "Error handling $clazz", t)
                    }
                },
                plugin, true,
            )
        }

        on("gameplay.GameStateChangeEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            callbacks.onStateChange(arena, Reflect.call(e, "getNewState").toString())
        }
        on("gameplay.GameEndEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val winner = Reflect.call(e, "getTeamWinner")?.let { TeamRef(it).name }
            callbacks.onGameEnd(arena, winner)
        }
        on("player.PlayerKillEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val victim = Reflect.call(e, "getVictim") as? Player ?: return@on
            val killer = Reflect.call(e, "getKiller") as? Player
            val cause = Reflect.call(e, "getCause")
            val finalKill = Reflect.call(cause, "isFinalKill") as? Boolean ?: false
            val fn = Reflect.call(e, "getMessage")
            @Suppress("UNCHECKED_CAST")
            val message = try {
                (fn as? java.util.function.Function<Player, String>)?.apply(killer ?: victim)
            } catch (_: Throwable) { null }
            callbacks.onKill(arena, victim, killer, finalKill, message)
        }
        on("player.PlayerBedBreakEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val player = Reflect.call(e, "getPlayer") as? Player ?: return@on
            val victim = Reflect.call(e, "getVictimTeam")?.let { TeamRef(it).name } ?: "?"
            callbacks.onBedBreak(arena, player, victim)
        }
        on("team.TeamEliminatedEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val team = Reflect.call(e, "getTeam")?.let { TeamRef(it).name } ?: return@on
            callbacks.onTeamEliminated(arena, team)
        }
        on("player.PlayerReSpawnEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val player = Reflect.call(e, "getPlayer") as? Player ?: return@on
            callbacks.onRespawn(arena, player)
        }
        on("player.PlayerLeaveArenaEvent") { e ->
            val arena = Reflect.call(e, "getArena")?.let(::ArenaRef) ?: return@on
            val player = Reflect.call(e, "getPlayer") as? Player ?: return@on
            callbacks.onLeave(arena, player)
        }
        on("server.ArenaDisableEvent") { e ->
            callbacks.onArenaDisabled(Reflect.str(e, "getArenaName") ?: return@on)
        }
        on("server.ArenaRestartEvent") { e ->
            callbacks.onArenaDisabled(Reflect.str(e, "getArenaName") ?: return@on)
        }
    }

    private fun arenaUtil(): Any? {
        val pkg = apiPackage ?: return null
        val svc = runCatching { Class.forName("$pkg.BedWars", false, loader) }.getOrNull() ?: return null
        val reg = Bukkit.getServicesManager().getRegistration(svc) ?: return null
        return Reflect.call(reg.provider, "getArenaUtil")
    }

    fun arenaOf(player: Player): ArenaRef? =
        Reflect.call(arenaUtil(), "getArenaByPlayer", player)?.let(::ArenaRef)

    fun arenas(): List<ArenaRef> = Reflect.list<Any>(arenaUtil(), "getArenas").map(::ArenaRef)

    /** Folder BedWars keeps zipped arena templates in (default internal map adapter). */
    fun templateZip(arenaName: String): java.io.File? {
        val owner = CANDIDATES.firstNotNullOfOrNull { Bukkit.getPluginManager().getPlugin(it.first) } ?: return null
        val f = java.io.File(owner.dataFolder, "Cache/$arenaName.zip")
        return if (f.isFile) f else null
    }

    private companion object {
        val CANDIDATES = listOf(
            "BedWars1058" to "com.andrei1058.bedwars.api",
            "BedWars2023" to "com.tomkeuper.bedwars.api",
        )
    }
}
