package dev.etran.bedreplay.storage

import dev.etran.bedreplay.format.ReplayMeta
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Local-file storage: `<id>.brep` (the replay) plus `<id>.meta` (small index, editable without
 * rewriting the replay, which is how retention changes are persisted).
 */
class FileReplayStorage(private val dir: File, private val log: Logger) : ReplayStorage {
    private fun replayFile(id: String) = File(dir, "$id.brep")
    private fun metaFile(id: String) = File(dir, "$id.meta")

    override fun init() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("Cannot create ${dir.path}")
    }

    @Synchronized
    override fun save(spool: File, meta: ReplayMeta, expiresAtMillis: Long): StoredReplay {
        requireSafeId(meta.id)
        val target = replayFile(meta.id)
        Files.move(spool.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        val stored = StoredReplay.from(meta, target.length(), expiresAtMillis)
        writeMeta(stored)
        return stored
    }

    @Synchronized
    override fun list(): List<StoredReplay> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".meta") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                readMeta(f)?.takeIf { replayFile(it.id).isFile }
            } catch (e: Exception) {
                log.log(Level.WARNING, "Skipping unreadable replay index ${f.name}", e)
                null
            }
        }
    }

    override fun open(id: String): InputStream? {
        if (!isSafeId(id)) return null
        val f = replayFile(id)
        return if (f.isFile) FileInputStream(f) else null
    }

    @Synchronized
    override fun delete(id: String): Boolean {
        if (!isSafeId(id)) return false
        val a = replayFile(id).delete()
        val b = metaFile(id).delete()
        return a || b
    }

    @Synchronized
    override fun setExpiry(id: String, expiresAtMillis: Long): Boolean {
        if (!isSafeId(id)) return false
        val f = metaFile(id)
        if (!f.isFile) return false
        val current = readMeta(f) ?: return false
        writeMeta(current.copy(expiresAtMillis = expiresAtMillis))
        return true
    }

    override fun close() {}

    // ------------------------------------------------------------------ index files

    private fun writeMeta(s: StoredReplay) {
        val p = Properties()
        p["id"] = s.id
        p["arena"] = s.arena
        p["map"] = s.mapName
        p["group"] = s.group ?: ""
        p["mc"] = s.mcVersion
        p["started"] = s.startedAtMillis.toString()
        p["ticks"] = s.durationTicks.toString()
        p["winner"] = s.winnerTeam ?: ""
        p["size"] = s.sizeBytes.toString()
        p["expires"] = s.expiresAtMillis.toString()
        p["players"] = PlayerIndex.encode(s.players)

        val tmp = File(dir, "${s.id}.meta.tmp")
        tmp.writer(StandardCharsets.UTF_8).use { p.store(it, "BedReplay index") }
        Files.move(tmp.toPath(), metaFile(s.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun readMeta(f: File): StoredReplay? {
        val p = Properties()
        f.reader(StandardCharsets.UTF_8).use { p.load(it) }
        val id = p.getProperty("id") ?: return null
        if (!isSafeId(id)) return null
        return StoredReplay(
            id = id,
            arena = p.getProperty("arena", ""),
            mapName = p.getProperty("map", ""),
            group = p.getProperty("group")?.ifEmpty { null },
            mcVersion = p.getProperty("mc", ""),
            startedAtMillis = p.getProperty("started")?.toLongOrNull() ?: 0,
            durationTicks = p.getProperty("ticks")?.toIntOrNull() ?: 0,
            winnerTeam = p.getProperty("winner")?.ifEmpty { null },
            players = PlayerIndex.decode(p.getProperty("players")),
            sizeBytes = p.getProperty("size")?.toLongOrNull() ?: 0,
            expiresAtMillis = p.getProperty("expires")?.toLongOrNull() ?: 0,
        )
    }

    companion object {
        /** Ids become file names, so only allow a conservative character set (no path tricks). */
        fun isSafeId(id: String) = id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        fun requireSafeId(id: String) = require(isSafeId(id)) { "Unsafe replay id: $id" }
    }
}
