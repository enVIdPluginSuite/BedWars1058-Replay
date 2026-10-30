package dev.etran.bedreplay.storage

import dev.etran.bedreplay.format.ReplayMeta
import java.io.File
import java.io.InputStream

/**
 * Where finished replays live. Implementations are blocking; [ReplayRepository] makes sure they
 * are only called off the main thread (except during shutdown).
 *
 * A recording is always first spooled to a local file while the match runs (so a crash keeps what
 * was recorded); [save] then takes ownership of that spool file.
 */
interface ReplayStorage {
    fun init()

    /** Takes ownership of [spool] (moves or deletes it) and indexes the replay. */
    fun save(spool: File, meta: ReplayMeta, expiresAtMillis: Long): StoredReplay

    fun list(): List<StoredReplay>

    fun open(id: String): InputStream?

    fun delete(id: String): Boolean

    fun setExpiry(id: String, expiresAtMillis: Long): Boolean

    fun close()
}
