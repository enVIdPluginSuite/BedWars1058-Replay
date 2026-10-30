package dev.etran.bedreplay.format

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipException

/*
 * BREP v1 file layout (everything inside one GZIP stream):
 *
 *   "BREP"                       4 bytes magic
 *   varint  formatVersion
 *   header  (ReplayMeta without duration / winner)
 *   repeat:
 *     byte  0x01  frame   : varint tickDelta, varint actionCount, actions...
 *     byte  0x02  footer  : varint durationTicks, long endedAtMillis, string? winnerTeam   (last record)
 *
 * The writer sync-flushes regularly, so a server crash leaves a readable file without a footer.
 * The reader treats a truncated stream as "recording ended here".
 */

private const val MAGIC = 0x42524550 // "BREP"
private const val REC_FRAME = 1
private const val REC_FOOTER = 2

private object T {
    const val SPAWN = 1
    const val REMOVE = 2
    const val MOVE = 3
    const val FLAGS = 4
    const val EQUIP = 5
    const val ANIMATION = 6
    const val VITALS = 7
    const val HOTBAR = 8
    const val BLOCK = 9
    const val CHAT = 10
    const val PICKUP = 11
    const val MARKER = 12
    const val SOUND = 13
    const val TITLE = 14
    const val SCOREBOARD = 15
    const val NAMETAG = 16
    const val INVENTORY = 17
    const val CONTAINER = 18
    const val ENTITY_META = 19
}

/** Positions are stored as 1/32 block fixed point, delta-encoded per entity. */
private const val POS_SCALE = 32.0

private fun quant(v: Double): Int = Math.round(v * POS_SCALE).toInt()
private fun angleByte(deg: Float): Int = Math.round(deg * 256f / 360f) and 0xFF
private fun angleDeg(b: Int): Float = (b and 0xFF) * 360f / 256f

/** Per-entity last-known quantised state; writer and reader mutate it identically. */
private class EntityTrack {
    private val states = HashMap<Int, IntArray>() // qx, qy, qz, yawByte, pitchByte
    fun get(id: Int): IntArray = states.getOrPut(id) { IntArray(5) }
    fun remove(id: Int) { states.remove(id) }
    var lastBlock = IntArray(3)
}

private class Out(val o: DataOutputStream) {
    fun byte(v: Int) = o.writeByte(v)
    fun bool(v: Boolean) = o.writeByte(if (v) 1 else 0)
    fun long(v: Long) = o.writeLong(v)
    fun float(v: Float) = o.writeFloat(v)

    fun varInt(value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            o.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        o.writeByte(v)
    }

    fun zigzag(v: Int) = varInt((v shl 1) xor (v shr 31))

    fun string(s: String?) {
        if (s == null) { varInt(0); return }
        val b = s.toByteArray(Charsets.UTF_8)
        varInt(b.size + 1)
        o.write(b)
    }
}

private class In(val i: DataInputStream) {
    fun byte(): Int = i.readUnsignedByte()
    fun bool(): Boolean = i.readUnsignedByte() != 0
    fun long(): Long = i.readLong()
    fun float(): Float = i.readFloat()

    fun varInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = i.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 35) throw IOException("VarInt too long")
        }
    }

    fun zigzag(): Int {
        val v = varInt()
        return (v ushr 1) xor -(v and 1)
    }

    fun string(): String? {
        val n = varInt()
        if (n == 0) return null
        val b = ByteArray(n - 1)
        i.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}

// ---------------------------------------------------------------- items / meta

private fun Out.item(it: ItemSnapshot?) {
    if (it == null) { bool(false); return }
    bool(true)
    string(it.material)
    varInt(it.amount)
    varInt(it.durability)
    string(it.displayName)
    varInt(it.enchantments.size)
    for ((k, v) in it.enchantments) { string(k); varInt(v) }
    zigzag(it.color)
}

private fun In.item(): ItemSnapshot? {
    if (!bool()) return null
    val material = string() ?: "AIR"
    val amount = varInt()
    val durability = varInt()
    val name = string()
    val n = varInt()
    val ench = LinkedHashMap<String, Int>()
    repeat(n) { ench[string() ?: ""] = varInt() }
    val color = zigzag()
    return ItemSnapshot(material, amount, durability, name, ench, color)
}

private fun Out.meta(m: ReplayMeta) {
    string(m.id); string(m.arena); string(m.mapName); string(m.group); string(m.mcVersion)
    long(m.startedAtMillis)
    varInt(m.players.size)
    for (p in m.players) {
        varInt(p.entityId); string(p.uuid); string(p.name); string(p.team)
        zigzag(p.teamColor); string(p.skinValue); string(p.skinSignature)
    }
}

private fun In.meta(version: Int): ReplayMeta {
    val id = string() ?: ""
    val arena = string() ?: ""
    val map = string() ?: ""
    val group = string()
    val mc = string() ?: ""
    val started = long()
    val n = varInt()
    val players = ArrayList<PlayerInfo>(n)
    repeat(n) {
        players += PlayerInfo(varInt(), string() ?: "", string() ?: "", string(), zigzag(), string(), string())
    }
    return ReplayMeta(id, arena, map, group, mc, started, players, formatVersion = version)
}

// ---------------------------------------------------------------- actions

private fun Out.action(a: Action, track: EntityTrack) {
    when (a) {
        is Action.Spawn -> {
            byte(T.SPAWN); varInt(a.id); string(a.kind)
            val s = track.get(a.id)
            s[0] = quant(a.x); s[1] = quant(a.y); s[2] = quant(a.z)
            s[3] = angleByte(a.yaw); s[4] = angleByte(a.pitch)
            zigzag(s[0]); zigzag(s[1]); zigzag(s[2]); byte(s[3]); byte(s[4])
            item(a.item); string(a.extra)
        }
        is Action.Remove -> { byte(T.REMOVE); varInt(a.id); track.remove(a.id) }
        is Action.Move -> {
            byte(T.MOVE); varInt(a.id)
            val s = track.get(a.id)
            val qx = quant(a.x); val qy = quant(a.y); val qz = quant(a.z)
            val yaw = angleByte(a.yaw); val pitch = angleByte(a.pitch)
            val hasPos = qx != s[0] || qy != s[1] || qz != s[2]
            val hasRot = yaw != s[3] || pitch != s[4]
            byte((if (a.onGround) 1 else 0) or (if (hasPos) 2 else 0) or (if (hasRot) 4 else 0))
            if (hasPos) {
                zigzag(qx - s[0]); zigzag(qy - s[1]); zigzag(qz - s[2])
                s[0] = qx; s[1] = qy; s[2] = qz
            }
            if (hasRot) { byte(yaw); byte(pitch); s[3] = yaw; s[4] = pitch }
        }
        is Action.Flags -> { byte(T.FLAGS); varInt(a.id); varInt(a.flags) }
        is Action.Equip -> { byte(T.EQUIP); varInt(a.id); byte(a.slot); item(a.item) }
        is Action.Animation -> { byte(T.ANIMATION); varInt(a.id); byte(a.anim) }
        is Action.Vitals -> {
            byte(T.VITALS); varInt(a.id); float(a.health); float(a.maxHealth); varInt(a.food); varInt(a.level)
        }
        is Action.Hotbar -> {
            byte(T.HOTBAR); varInt(a.id); byte(a.heldSlot)
            for (i in 0 until 9) item(a.items.getOrNull(i))
        }
        is Action.BlockChange -> {
            byte(T.BLOCK)
            val l = track.lastBlock
            zigzag(a.x - l[0]); zigzag(a.y - l[1]); zigzag(a.z - l[2])
            l[0] = a.x; l[1] = a.y; l[2] = a.z
            string(a.block); string(a.previous)
        }
        is Action.Chat -> {
            byte(T.CHAT); string(a.text)
            val r = a.recipients
            if (r == null) varInt(0) else { varInt(r.size + 1); r.forEach { varInt(it) } }
        }
        is Action.Pickup -> { byte(T.PICKUP); varInt(a.itemId); varInt(a.collectorId) }
        is Action.Marker -> { byte(T.MARKER); byte(a.kind); string(a.label); zigzag(a.subjectId) }
        is Action.Sound -> {
            byte(T.SOUND); string(a.name)
            zigzag(quant(a.x)); zigzag(quant(a.y)); zigzag(quant(a.z)); float(a.volume); float(a.pitch)
        }
        is Action.Title -> {
            byte(T.TITLE); byte(a.kind); string(a.text); varInt(a.fadeIn); varInt(a.stay); varInt(a.fadeOut)
            val r = a.recipients
            if (r == null) varInt(0) else { varInt(r.size + 1); r.forEach { varInt(it) } }
        }
        is Action.Scoreboard -> {
            byte(T.SCOREBOARD); varInt(a.id); bool(a.title != null)
            if (a.title != null) { string(a.title); varInt(a.lines.size); a.lines.forEach { string(it) } }
        }
        is Action.Inventory -> { byte(T.INVENTORY); varInt(a.id); varInt(a.items.size); a.items.forEach { item(it) } }
        is Action.Container -> {
            byte(T.CONTAINER); zigzag(a.x); zigzag(a.y); zigzag(a.z); string(a.type); zigzag(a.owner)
            varInt(a.items.size); a.items.forEach { item(it) }
        }
        is Action.EntityMeta -> { byte(T.ENTITY_META); varInt(a.id); string(a.key); string(a.value) }
        is Action.Nametag -> { byte(T.NAMETAG); varInt(a.id); string(a.prefix); string(a.suffix); bool(a.hidden) }
    }
}

private fun In.action(track: EntityTrack): Action {
    return when (val type = byte()) {
        T.SPAWN -> {
            val id = varInt(); val kind = string() ?: "unknown"
            val s = track.get(id)
            s[0] = zigzag(); s[1] = zigzag(); s[2] = zigzag(); s[3] = byte(); s[4] = byte()
            val item = item(); val extra = string()
            Action.Spawn(id, kind, s[0] / POS_SCALE, s[1] / POS_SCALE, s[2] / POS_SCALE, angleDeg(s[3]), angleDeg(s[4]), item, extra)
        }
        T.REMOVE -> { val id = varInt(); track.remove(id); Action.Remove(id) }
        T.MOVE -> {
            val id = varInt(); val s = track.get(id)
            val f = byte()
            if (f and 2 != 0) { s[0] += zigzag(); s[1] += zigzag(); s[2] += zigzag() }
            if (f and 4 != 0) { s[3] = byte(); s[4] = byte() }
            Action.Move(id, s[0] / POS_SCALE, s[1] / POS_SCALE, s[2] / POS_SCALE, angleDeg(s[3]), angleDeg(s[4]), f and 1 != 0)
        }
        T.FLAGS -> Action.Flags(varInt(), varInt())
        T.EQUIP -> Action.Equip(varInt(), byte(), item())
        T.ANIMATION -> Action.Animation(varInt(), byte())
        T.VITALS -> Action.Vitals(varInt(), float(), float(), varInt(), varInt())
        T.HOTBAR -> {
            val id = varInt(); val held = byte()
            Action.Hotbar(id, held, List(9) { item() })
        }
        T.BLOCK -> {
            val l = track.lastBlock
            l[0] += zigzag(); l[1] += zigzag(); l[2] += zigzag()
            Action.BlockChange(l[0], l[1], l[2], string() ?: "AIR", string() ?: "AIR")
        }
        T.CHAT -> {
            val text = string() ?: ""
            val n = varInt()
            Action.Chat(text, if (n == 0) null else IntArray(n - 1) { varInt() })
        }
        T.PICKUP -> Action.Pickup(varInt(), varInt())
        T.MARKER -> Action.Marker(byte(), string() ?: "", zigzag())
        T.SOUND -> Action.Sound(
            string() ?: "", zigzag() / POS_SCALE, zigzag() / POS_SCALE, zigzag() / POS_SCALE, float(), float(),
        )
        T.TITLE -> {
            val kind = byte(); val text = string() ?: ""
            val fi = varInt(); val st = varInt(); val fo = varInt()
            val n = varInt()
            Action.Title(kind, text, fi, st, fo, if (n == 0) null else IntArray(n - 1) { varInt() })
        }
        T.SCOREBOARD -> {
            val id = varInt()
            if (!bool()) Action.Scoreboard(id, null, emptyList())
            else { val title = string() ?: ""; Action.Scoreboard(id, title, List(varInt()) { string() ?: "" }) }
        }
        T.INVENTORY -> { val id = varInt(); Action.Inventory(id, List(varInt()) { item() }) }
        T.CONTAINER -> {
            val x = zigzag(); val y = zigzag(); val z = zigzag(); val type = string() ?: ""; val owner = zigzag()
            Action.Container(x, y, z, type, owner, List(varInt()) { item() })
        }
        T.ENTITY_META -> Action.EntityMeta(varInt(), string() ?: "", string() ?: "")
        T.NAMETAG -> Action.Nametag(varInt(), string() ?: "", string() ?: "", bool())
        else -> throw IOException("Unknown action type $type")
    }
}

// ---------------------------------------------------------------- writer / reader

/** Streaming writer: header up front, frames as they happen, footer on [finish]. */
class ReplayWriter(output: OutputStream, meta: ReplayMeta) : Closeable {
    private val gzip = GZIPOutputStream(output, 16 * 1024, true)
    private val out = Out(DataOutputStream(BufferedOutputStream(gzip, 16 * 1024)))
    private val track = EntityTrack()
    private var lastTick = 0
    private var finished = false

    init {
        out.o.writeInt(MAGIC)
        out.varInt(FORMAT_VERSION)
        out.meta(meta)
    }

    @Synchronized
    fun writeFrame(tick: Int, actions: List<Action>) {
        check(!finished) { "writer already finished" }
        if (actions.isEmpty()) return
        require(tick >= lastTick) { "ticks must not go backwards ($tick < $lastTick)" }
        out.byte(REC_FRAME)
        out.varInt(tick - lastTick)
        out.varInt(actions.size)
        for (a in actions) out.action(a, track)
        lastTick = tick
    }

    /** Pushes buffered data to disk in a form a reader can recover from after a crash. */
    @Synchronized
    fun flush() {
        if (!finished) out.o.flush()
    }

    @Synchronized
    fun finish(footer: ReplayFooter) {
        if (finished) return
        finished = true
        out.byte(REC_FOOTER)
        out.varInt(footer.durationTicks)
        out.long(footer.endedAtMillis)
        out.string(footer.winnerTeam)
        out.o.flush()
        gzip.finish()
        out.o.close()
    }

    @Synchronized
    override fun close() {
        if (!finished) {
            // Not finished cleanly: leave a recoverable (footer-less) file.
            finished = true
            try { out.o.flush(); gzip.finish() } catch (_: IOException) {}
            try { out.o.close() } catch (_: IOException) {}
        }
    }
}

object ReplayReader {
    /** Reads a whole replay. Truncated streams (crash) yield everything that was complete. */
    fun read(input: InputStream): Replay {
        val data = DataInputStream(java.io.BufferedInputStream(GZIPInputStream(input, 16 * 1024), 16 * 1024))
        val inp = In(data)
        if (data.readInt() != MAGIC) throw IOException("Not a BREP replay file")
        val version = inp.varInt()
        if (version > FORMAT_VERSION) throw IOException("Replay format v$version is newer than supported v$FORMAT_VERSION")
        var meta = inp.meta(version)
        val frames = ArrayList<Frame>()
        val track = EntityTrack()
        var tick = 0
        var footer: ReplayFooter? = null

        try {
            while (true) {
                when (inp.byte()) {
                    REC_FRAME -> {
                        val delta = inp.varInt()
                        val count = inp.varInt()
                        val actions = ArrayList<Action>(count)
                        repeat(count) { actions += inp.action(track) }
                        tick += delta
                        frames += Frame(tick, actions)
                    }
                    REC_FOOTER -> {
                        footer = ReplayFooter(inp.varInt(), inp.long(), inp.string())
                        break
                    }
                    else -> throw IOException("Corrupt replay: unknown record type")
                }
            }
        } catch (_: EOFException) {
            // truncated recording, keep what we have
        } catch (_: ZipException) {
            // truncated / damaged gzip trailer, keep what we have
        }

        meta = if (footer != null) {
            meta.copy(durationTicks = footer.durationTicks, endedAtMillis = footer.endedAtMillis, winnerTeam = footer.winnerTeam)
        } else {
            meta.copy(durationTicks = frames.lastOrNull()?.tick ?: 0)
        }
        return Replay(meta, frames)
    }

    /** Reads only the header (cheap); duration and winner are not available without the footer. */
    fun readHeader(input: InputStream): ReplayMeta {
        val data = DataInputStream(java.io.BufferedInputStream(GZIPInputStream(input, 16 * 1024), 16 * 1024))
        val inp = In(data)
        if (data.readInt() != MAGIC) throw IOException("Not a BREP replay file")
        return inp.meta(inp.varInt())
    }
}
