package dev.etran.bedreplay.format

/**
 * Version-neutral replay data model. Nothing in this package touches Bukkit, so the whole
 * format can be unit-tested and read by external tools.
 *
 * Block / item / entity identifiers are stored as strings (the names the recording server used)
 * and translated at playback time, which is what lets one replay be watched on a different
 * Minecraft version than it was recorded on (best effort across the 1.13 "flattening").
 */

const val FORMAT_VERSION = 2

/** Angles are stored in degrees in the model and quantised to 1/256 of a turn on disk. */
data class ItemSnapshot(
    val material: String,
    val amount: Int = 1,
    /** Legacy durability / data value (wool colour etc. on 1.8-1.12, damage on newer). */
    val durability: Int = 0,
    val displayName: String? = null,
    val enchantments: Map<String, Int> = emptyMap(),
    /** RGB for dyed leather armour, or -1. */
    val color: Int = -1,
)

data class PlayerInfo(
    val entityId: Int,
    val uuid: String,
    val name: String,
    val team: String?,
    /** Team colour as 0xRRGGBB or -1 when unknown. */
    val teamColor: Int = -1,
    val skinValue: String? = null,
    val skinSignature: String? = null,
)

/** Written once at the start of a recording. Duration/winner arrive in [ReplayFooter]. */
data class ReplayMeta(
    val id: String,
    val arena: String,
    val mapName: String,
    val group: String?,
    /** Minecraft version of the recording server, e.g. "1.8.8" or "1.21.4". */
    val mcVersion: String,
    val startedAtMillis: Long,
    val players: List<PlayerInfo>,
    val durationTicks: Int = 0,
    val endedAtMillis: Long = 0,
    val winnerTeam: String? = null,
    val formatVersion: Int = FORMAT_VERSION,
)

data class ReplayFooter(
    val durationTicks: Int,
    val endedAtMillis: Long,
    val winnerTeam: String?,
)

data class Frame(val tick: Int, val actions: List<Action>)

data class Replay(val meta: ReplayMeta, val frames: List<Frame>)

object Anim {
    const val SWING_MAIN = 0
    const val SWING_OFF = 1
    const val HURT = 2
    const val DEATH = 3
    const val CRIT = 4
    const val MAGIC_CRIT = 5
    const val EAT = 6
}

object Flag {
    const val SNEAKING = 1
    const val SPRINTING = 2
    const val INVISIBLE = 4
    const val BURNING = 8
    const val BLOCKING = 16
    const val GLIDING = 32
}

/** Equipment slots, modern ordering. Off-hand simply does not exist on 1.8 playback. */
object Slot {
    const val MAIN_HAND = 0
    const val OFF_HAND = 1
    const val BOOTS = 2
    const val LEGGINGS = 3
    const val CHESTPLATE = 4
    const val HELMET = 5
}

object MarkerKind {
    const val GAME_EVENT = 0
    const val BED_DESTROYED = 1
    const val KILL = 2
    const val FINAL_KILL = 3
    const val TEAM_ELIMINATED = 4
    const val GAME_END = 5
}

sealed class Action {
    /** Entity appears. For players [kind] is "player" and [id] matches a [PlayerInfo.entityId]. */
    data class Spawn(
        val id: Int,
        val kind: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        /** Item stack for dropped items, falling-block material, etc. */
        val item: ItemSnapshot? = null,
        val extra: String? = null,
    ) : Action()

    data class Remove(val id: Int) : Action()

    data class Move(
        val id: Int,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val onGround: Boolean,
    ) : Action()

    data class Flags(val id: Int, val flags: Int) : Action()

    data class Equip(val id: Int, val slot: Int, val item: ItemSnapshot?) : Action()

    data class Animation(val id: Int, val anim: Int) : Action()

    /** Health/food/level shown when a viewer follows a player. */
    data class Vitals(val id: Int, val health: Float, val maxHealth: Float, val food: Int, val level: Int) : Action()

    /** Whole hotbar of a player (9 entries, nulls for empty) for first-person following. */
    data class Hotbar(val id: Int, val heldSlot: Int, val items: List<ItemSnapshot?>) : Action()

    /** [previous] lets playback undo the change when seeking backwards. */
    data class BlockChange(
        val x: Int,
        val y: Int,
        val z: Int,
        val block: String,
        val previous: String,
    ) : Action()

    /**
     * A line of chat/system text as it was shown to players, in legacy (section sign) format.
     * [recipients] are entity ids of players who saw it, or null if everybody did. This is what
     * makes "chat seen by whoever you are spectating" possible.
     */
    data class Chat(val text: String, val recipients: IntArray?) : Action() {
        override fun equals(other: Any?): Boolean =
            other is Chat && text == other.text && recipients.contentEquals(other.recipients)

        override fun hashCode(): Int = 31 * text.hashCode() + (recipients?.contentHashCode() ?: 0)
    }

    data class Pickup(val itemId: Int, val collectorId: Int) : Action()

    /** Timeline marker used by the "skip to event" controls and the kill feed. */
    data class Marker(val kind: Int, val label: String, val subjectId: Int = -1) : Action()

    /** [name] is the sound id as the recording server knew it, e.g. "minecraft:entity.player.hurt". */
    data class Sound(val name: String, val x: Double, val y: Double, val z: Double, val volume: Float, val pitch: Float) : Action()

    /**
     * Title / subtitle / action bar / timings / clear, as shown to [recipients] (null = everyone).
     * [kind] is a [TitleKind]; for TIMES the three numbers are fade-in, stay, fade-out in ticks.
     */
    data class Title(val kind: Int, val text: String, val fadeIn: Int, val stay: Int, val fadeOut: Int, val recipients: IntArray?) : Action() {
        override fun equals(other: Any?): Boolean = other is Title && kind == other.kind && text == other.text &&
            fadeIn == other.fadeIn && stay == other.stay && fadeOut == other.fadeOut && recipients.contentEquals(other.recipients)

        override fun hashCode(): Int = 31 * (31 * kind + text.hashCode()) + (recipients?.contentHashCode() ?: 0)
    }

    /**
     * The sidebar scoreboard [id] (a player's replay id) was looking at. [title] null means no sidebar.
     * Lines are rendered text, top to bottom.
     */
    data class Scoreboard(val id: Int, val title: String?, val lines: List<String>) : Action()

    /** Name tag decoration of a player: team prefix / suffix, and whether the tag is hidden. */
    data class Nametag(val id: Int, val prefix: String, val suffix: String, val hidden: Boolean) : Action()

    /**
     * Whole inventory of player [id] as 41 slots: 0-8 hotbar, 9-35 main inventory, 36-39 boots, leggings,
     * chestplate, helmet, 40 off hand. Written whenever anything in it changes.
     */
    data class Inventory(val id: Int, val items: List<ItemSnapshot?>) : Action()

    /**
     * Contents of a chest, furnace, hopper, ender chest ... at the moment players had it open or closed it.
     * [owner] is the replay id of the player an ender chest belongs to, otherwise -1.
     */
    data class Container(val x: Int, val y: Int, val z: Int, val type: String, val owner: Int, val items: List<ItemSnapshot?>) : Action()

    /** A mob / entity property: "name", "name_visible", "baby", "size", "color" ... */
    data class EntityMeta(val id: Int, val key: String, val value: String) : Action()
}

object TitleKind {
    const val TITLE = 0
    const val SUBTITLE = 1
    const val ACTION_BAR = 2
    const val TIMES = 3
    const val CLEAR = 4
    const val RESET = 5
}
