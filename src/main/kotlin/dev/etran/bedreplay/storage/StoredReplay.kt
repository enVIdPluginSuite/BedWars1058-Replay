package dev.etran.bedreplay.storage

import dev.etran.bedreplay.format.PlayerInfo
import dev.etran.bedreplay.format.ReplayMeta
import java.util.UUID

/** Index entry for a saved replay: everything the menu needs without opening the replay itself. */
data class StoredReplay(
    val id: String,
    val arena: String,
    val mapName: String,
    val group: String?,
    val mcVersion: String,
    val startedAtMillis: Long,
    val durationTicks: Int,
    val winnerTeam: String?,
    val players: List<PlayerInfo>,
    val sizeBytes: Long,
    /** Epoch millis after which the replay is deleted, or 0 = never. */
    val expiresAtMillis: Long,
) {
    val keepForever: Boolean get() = expiresAtMillis == 0L
    val durationSeconds: Int get() = durationTicks / 20

    fun hasParticipant(uuid: UUID): Boolean {
        val s = uuid.toString()
        return players.any { it.uuid.equals(s, ignoreCase = true) }
    }

    fun daysLeft(now: Long = System.currentTimeMillis()): Long? =
        if (keepForever) null else ((expiresAtMillis - now).coerceAtLeast(0) + 86_399_999L) / 86_400_000L

    companion object {
        fun from(meta: ReplayMeta, sizeBytes: Long, expiresAtMillis: Long) = StoredReplay(
            meta.id, meta.arena, meta.mapName, meta.group, meta.mcVersion, meta.startedAtMillis,
            meta.durationTicks, meta.winnerTeam, meta.players, sizeBytes, expiresAtMillis,
        )
    }
}

/** Compact text encoding of the player list, shared by the file and SQL backends. */
object PlayerIndex {
    fun encode(players: List<PlayerInfo>): String = players.joinToString("\n") {
        listOf(it.entityId, it.uuid, it.name, it.team ?: "", it.teamColor).joinToString("\t")
    }

    fun decode(text: String?): List<PlayerInfo> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            PlayerInfo(
                f[0].toIntOrNull() ?: return@mapNotNull null, f[1], f[2],
                f[3].ifEmpty { null }, f[4].toIntOrNull() ?: -1,
            )
        }.toList()
    }
}
