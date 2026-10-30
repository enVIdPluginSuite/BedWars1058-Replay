package dev.etran.bedreplay.storage

import dev.etran.bedreplay.config.PluginConfig
import dev.etran.bedreplay.format.Replay
import dev.etran.bedreplay.format.ReplayMeta
import dev.etran.bedreplay.format.ReplayReader
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * Front door to storage: keeps an in-memory index (so menus never touch disk or the database),
 * runs all blocking storage work off the main thread, and owns retention rules.
 */
class ReplayRepository(
    private val plugin: Plugin,
    private val storage: ReplayStorage,
    private val config: () -> PluginConfig,
) {
    @Volatile
    private var index: List<StoredReplay> = emptyList()

    /** Replays currently being watched; retention never deletes these mid-playback. */
    private val inUse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun init() {
        storage.init()
        index = storage.list()
    }

    fun close() = storage.close()

    // ------------------------------------------------------------------ index access

    fun snapshot(): List<StoredReplay> = index

    fun get(id: String): StoredReplay? = index.firstOrNull { it.id == id }

    /**
     * What a player may see in the menu: replays they took part in, or everything for admins.
     * Always de-duplicated by id and newest first.
     */
    fun visibleTo(uuid: UUID, admin: Boolean): List<StoredReplay> =
        index.asSequence()
            .filter { admin || it.hasParticipant(uuid) }
            .distinctBy { it.id }
            .sortedByDescending { it.startedAtMillis }
            .toList()

    fun refreshAsync(after: (() -> Unit)? = null) {
        async {
            val fresh = storage.list()
            index = fresh
            if (after != null) sync(after)
        }
    }

    // ------------------------------------------------------------------ retention

    fun defaultExpiry(now: Long = System.currentTimeMillis()): Long {
        val days = config().retentionDefaultDays
        return if (days == 0) 0L else now + days * DAY
    }

    /** Hard upper bound for an expiry, relative to the match start. 0 = no cap. */
    private fun capFor(r: StoredReplay): Long {
        val max = config().maxTotalDays
        return if (max == 0) Long.MAX_VALUE else r.startedAtMillis + max * DAY
    }

    fun extendedExpiry(r: StoredReplay, now: Long = System.currentTimeMillis()): Long {
        val base = maxOf(r.expiresAtMillis, now)
        return minOf(base + config().extendStepDays * DAY, capFor(r)).coerceAtLeast(now)
    }

    fun unkeepExpiry(now: Long = System.currentTimeMillis()): Long {
        val days = config().retentionDefaultDays.takeIf { it > 0 } ?: config().extendStepDays
        return now + days * DAY
    }

    fun cleanupExpired() = async {
        val now = System.currentTimeMillis()
        for (r in index) {
            if (r.expiresAtMillis == 0L || r.expiresAtMillis > now || r.id in inUse) continue
            runCatching { storage.delete(r.id) }
                .onSuccess { plugin.logger.info("Deleted expired replay ${r.id}") }
                .onFailure { plugin.logger.log(Level.WARNING, "Could not delete expired replay ${r.id}", it) }
        }
        index = storage.list()
    }

    // ------------------------------------------------------------------ mutations

    /** Blocking save; used on the recording thread and during server shutdown. */
    fun saveBlocking(spool: File, meta: ReplayMeta): StoredReplay {
        val stored = storage.save(spool, meta, defaultExpiry())
        index = index.filterNot { it.id == stored.id } + stored
        return stored
    }

    fun saveAsync(spool: File, meta: ReplayMeta, done: (StoredReplay?) -> Unit) = async {
        val stored = try {
            saveBlocking(spool, meta)
        } catch (t: Throwable) {
            plugin.logger.log(Level.SEVERE, "Could not save replay ${meta.id}; the spool file was kept at ${spool.path}", t)
            null
        }
        sync { done(stored) }
    }

    fun deleteAsync(id: String, done: (Boolean) -> Unit) = async {
        val ok = runCatching { storage.delete(id) }.getOrDefault(false)
        if (ok) index = index.filterNot { it.id == id }
        sync { done(ok) }
    }

    fun setExpiryAsync(id: String, expiresAt: Long, done: (StoredReplay?) -> Unit) = async {
        val ok = runCatching { storage.setExpiry(id, expiresAt) }.getOrDefault(false)
        var updated: StoredReplay? = null
        if (ok) {
            index = index.map { if (it.id == id) it.copy(expiresAtMillis = expiresAt).also { u -> updated = u } else it }
        }
        sync { done(updated) }
    }

    fun loadAsync(id: String, done: (Replay?) -> Unit) = async {
        val replay = try {
            storage.open(id)?.use { ReplayReader.read(it) }
        } catch (t: Throwable) {
            plugin.logger.log(Level.WARNING, "Could not read replay $id", t)
            null
        }
        sync { done(replay) }
    }

    fun acquire(id: String) { inUse += id }
    fun release(id: String) { inUse -= id }

    // ------------------------------------------------------------------ threading helpers

    private fun async(block: () -> Unit) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                try { block() } catch (t: Throwable) { plugin.logger.log(Level.WARNING, "Replay storage task failed", t) }
            })
        } catch (_: IllegalStateException) {
            block() // scheduler unavailable (shutting down): run inline
        }
    }

    private fun sync(block: () -> Unit) {
        try {
            Bukkit.getScheduler().runTask(plugin, Runnable(block))
        } catch (_: IllegalStateException) {
            block()
        }
    }

    companion object {
        const val DAY = 86_400_000L
    }
}
