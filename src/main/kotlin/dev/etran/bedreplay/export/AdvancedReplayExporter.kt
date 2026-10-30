package dev.etran.bedreplay.export

import dev.etran.bedreplay.format.Action
import dev.etran.bedreplay.format.Anim
import dev.etran.bedreplay.format.Flag
import dev.etran.bedreplay.format.ItemSnapshot
import dev.etran.bedreplay.format.Replay
import dev.etran.bedreplay.format.Slot
import dev.etran.bedreplay.record.BlockCodec
import dev.etran.bedreplay.util.ServerCompat
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

class ExportResult(val file: File, val name: String, val written: Int, val skipped: Map<String, Int>)

/**
 * Writes a replay in the format AdvancedReplay loads (`plugins/AdvancedReplay/replays/<name>.replay`).
 *
 * AdvancedReplay saves a gzip-compressed Java-serialized `ReplayData`. To stay compatible without copying
 * its code, every object is created *through AdvancedReplay's own classes*, found via its plugin class
 * loader, so the file always matches the installed version. AdvancedReplay therefore has to be installed.
 *
 * What AdvancedReplay can represent is a subset of what BedReplay records: players (spawn, movement,
 * equipment, sneaking, burning, blocking, gliding, swings, hurt), blocks, dropped items, TNT and other
 * entities, and global chat. Sounds, titles, scoreboards, name tags, per-player chat and vitals have no
 * equivalent and are left out (reported in [ExportResult.skipped]).
 */
class AdvancedReplayExporter {
    fun plugin(): Plugin? = Bukkit.getPluginManager().getPlugin("AdvancedReplay")

    fun export(replay: Replay, replayId: String, worldName: String): ExportResult {
        val ar = plugin() ?: error("AdvancedReplay is not installed")
        val loader = ar.javaClass.classLoader
        val b = Builder(loader, worldName)

        val meta = replay.meta
        val names = meta.players.associate { it.entityId to it.name }
        val infoById = meta.players.associateBy { it.entityId }
        val alive = LinkedHashSet<Int>()
        val equip = HashMap<Int, Array<ItemSnapshot?>>()
        val flags = HashMap<Int, Int>()
        val deathTick = HashMap<Int, Int>()
        val entityKinds = HashMap<Int, String>()
        val entityPos = HashMap<Int, DoubleArray>()
        val blockShadow = HashMap<Long, String>()
        val skipped = LinkedHashMap<String, Int>()
        fun skip(what: String) { skipped[what] = (skipped[what] ?: 0) + 1 }

        fun carrier(): String? = alive.firstOrNull()?.let { names[it] }

        for (frame in replay.frames) {
            val tick = frame.tick
            val invDirty = LinkedHashSet<Int>()
            for (a in frame.actions) when (a) {
                is Action.Spawn -> if (a.kind == "player") {
                    val info = infoById[a.id] ?: continue
                    alive += a.id
                    equip.getOrPut(a.id) { arrayOfNulls(6) }
                    flags[a.id] = 0
                    val sig = if (info.skinValue != null) b.signature(info.name, info.skinValue, info.skinSignature ?: "") else null
                    b.add(tick, "SPAWN", info.name, b.spawn(UUID.fromString(info.uuid), a.x, a.y, a.z, a.yaw, a.pitch, sig))
                    invDirty += a.id
                } else {
                    val who = carrier() ?: continue
                    entityKinds[a.id] = a.kind
                    entityPos[a.id] = doubleArrayOf(a.x, a.y, a.z)
                    when (a.kind) {
                        "item" -> {
                            val stack = ServerCompat.toItemStack(a.item)
                            if (stack == null) { skip("items"); continue }
                            b.add(tick, "PACKET", who, b.entityItem(0, a.id, b.itemData(stack), b.loc(a.x, a.y, a.z), b.loc(0.0, 0.0, 0.0)))
                        }
                        "tnt" -> b.add(tick, "PACKET", who, b.tnt(a.id, b.loc(a.x, a.y, a.z)))
                        else -> {
                            val type = b.entityType(a.kind)
                            if (type == null) skip("entities of unsupported types") else
                                b.add(tick, "PACKET", who, b.entity(0, a.id, b.loc(a.x, a.y, a.z, a.yaw, a.pitch), type))
                        }
                    }
                }
                is Action.Remove -> {
                    val info = infoById[a.id]
                    if (info != null) {
                        if (alive.remove(a.id)) {
                            val died = (deathTick[a.id] ?: -1000) >= tick - 30
                            b.add(tick, if (died) "DEATH" else "DESPAWN", info.name, null)
                        }
                    } else {
                        val who = carrier() ?: continue
                        val kind = entityKinds.remove(a.id) ?: continue
                        val pos = entityPos.remove(a.id) ?: doubleArrayOf(0.0, 0.0, 0.0)
                        when (kind) {
                            "item" -> b.add(tick, "PACKET", who, b.entityItem(1, a.id, b.air(), b.loc(pos[0], pos[1], pos[2]), b.loc(0.0, 0.0, 0.0)))
                            "tnt" -> b.add(tick, "PACKET", who, b.destroy(a.id))
                            else -> b.entityType(kind)?.let { b.add(tick, "PACKET", who, b.entity(1, a.id, b.loc(pos[0], pos[1], pos[2]), it)) }
                        }
                    }
                }
                is Action.Move -> {
                    val info = infoById[a.id]
                    if (info != null) {
                        if (a.id in alive) b.add(tick, "PACKET", info.name, b.moving(a.x, a.y, a.z, a.pitch, a.yaw))
                    } else {
                        val who = carrier() ?: continue
                        val kind = entityKinds[a.id] ?: continue
                        entityPos[a.id] = doubleArrayOf(a.x, a.y, a.z)
                        if (kind != "item") b.add(tick, "PACKET", who, b.entityMoving(a.id, a.x, a.y, a.z, a.pitch, a.yaw))
                    }
                }
                is Action.Flags -> {
                    val info = infoById[a.id] ?: continue
                    if (a.id !in alive) continue
                    val old = flags[a.id] ?: 0
                    flags[a.id] = a.flags
                    val diff = old xor a.flags
                    if (diff and Flag.SNEAKING != 0) {
                        b.add(tick, "PACKET", info.name, b.sneak(a.flags and Flag.SNEAKING != 0))
                    }
                    if (diff and (Flag.BURNING or Flag.BLOCKING or Flag.GLIDING) != 0) {
                        b.add(tick, "PACKET", info.name,
                            b.metadata(a.flags and Flag.BURNING != 0, a.flags and Flag.BLOCKING != 0, a.flags and Flag.GLIDING != 0))
                    }
                    if (a.flags and Flag.SPRINTING != 0 || diff and Flag.SPRINTING != 0) skip("sprint state changes")
                }
                is Action.Equip -> if (a.id in alive) {
                    equip.getOrPut(a.id) { arrayOfNulls(6) }[a.slot] = a.item
                    invDirty += a.id
                }
                is Action.Animation -> {
                    val info = infoById[a.id] ?: continue
                    if (a.id !in alive) continue
                    when (a.anim) {
                        Anim.SWING_MAIN -> b.add(tick, "PACKET", info.name, b.animation(0))
                        Anim.SWING_OFF -> b.add(tick, "PACKET", info.name, b.animation(3))
                        Anim.HURT -> b.add(tick, "PACKET", info.name, b.animation(1))
                        Anim.CRIT -> b.add(tick, "PACKET", info.name, b.animation(4))
                        Anim.MAGIC_CRIT -> b.add(tick, "PACKET", info.name, b.animation(5))
                        Anim.DEATH -> deathTick[a.id] = tick
                    }
                }
                is Action.BlockChange -> {
                    val who = carrier() ?: continue
                    val key = (a.x.toLong() shl 40) xor (a.z.toLong() shl 12) xor a.y.toLong()
                    val before = blockShadow[key] ?: BlockCodec.AIR
                    blockShadow[key] = a.block
                    val beforeItem = b.blockItem(before)
                    val afterItem = b.blockItem(a.block)
                    if (beforeItem == null || afterItem == null) { skip("blocks without an item form"); continue }
                    b.add(tick, "PACKET", who, b.blockChange(b.loc(a.x.toDouble(), a.y.toDouble(), a.z.toDouble()), beforeItem, afterItem))
                }
                is Action.Chat -> {
                    if (a.recipients == null) b.add(tick, "MESSAGE", carrier() ?: meta.players.first().name, b.chat(a.text))
                    else skip("chat lines seen by only some players")
                }
                is Action.Sound -> skip("sounds")
                is Action.Title -> skip("titles / action bars")
                is Action.Scoreboard -> skip("scoreboard updates")
                is Action.Nametag -> skip("name tag changes")
                is Action.Inventory -> skip("full inventory snapshots")
                is Action.Container -> skip("chest / container contents")
                is Action.EntityMeta -> {}
                is Action.Vitals, is Action.Hotbar, is Action.Pickup, is Action.Marker -> {}
            }
            for (id in invDirty) {
                val info = infoById[id] ?: continue
                val e = equip[id] ?: continue
                b.add(tick, "PACKET", info.name, b.inventory(e[Slot.HELMET], e[Slot.CHESTPLATE], e[Slot.LEGGINGS], e[Slot.BOOTS], e[Slot.MAIN_HAND], e[Slot.OFF_HAND]))
            }
        }

        val dir = File(ar.dataFolder, "replays").also { it.mkdirs() }
        val name = "br-" + replayId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val file = File(dir, "$name.replay")
        val data = b.finish(meta.durationTicks, "BedWars1058-Replay")
        ObjectOutputStream(GZIPOutputStream(FileOutputStream(file))).use { it.writeObject(data) }
        return ExportResult(file, name, b.count, skipped)
    }

    /** All the reflective plumbing: AdvancedReplay's classes, resolved from its own class loader. */
    private class Builder(private val loader: ClassLoader, private val world: String) {
        private val root = "me.jumper251.replay.replaysystem"
        private fun cls(name: String): Class<*> = Class.forName(name, true, loader)
        private val dataCls = cls("$root.data.ReplayData")
        private val actionCls = cls("$root.data.ActionData")
        private val typeCls = cls("$root.data.ActionType")
        private val locCls = cls("$root.data.types.LocationData")
        private val itemCls = cls("$root.data.types.ItemData")
        private val stackCls = cls("$root.data.types.SerializableItemStack")
        private val playerActionCls: Class<*> by lazy { cls("com.comphenix.protocol.wrappers.EnumWrappers\$PlayerAction") }
        private val types = HashMap<String, Any>()
        private val data = dataCls.getConstructor().newInstance()
        @Suppress("UNCHECKED_CAST")
        private val actions = dataCls.getMethod("getActions").invoke(data) as HashMap<Int, MutableList<Any>>
        private val watchers = HashMap<String, Any>()
        var count = 0
            private set

        @Suppress("UNCHECKED_CAST")
        private fun enum(c: Class<*>, name: String): Any = java.lang.Enum.valueOf(c as Class<out Enum<*>>, name)

        private fun wrap(c: Class<*>): Class<*> = when (c) {
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            else -> c
        }

        private fun create(c: Class<*>, vararg args: Any?): Any {
            val ctor = c.constructors.firstOrNull { k ->
                k.parameterCount == args.size && k.parameterTypes.indices.all { i ->
                    val a = args[i]
                    if (a == null) !k.parameterTypes[i].isPrimitive else wrap(k.parameterTypes[i]).isInstance(a)
                }
            } ?: error("AdvancedReplay ${c.simpleName} has no constructor taking ${args.size} such arguments (unsupported AdvancedReplay version?)")
            return ctor.newInstance(*args)
        }

        private fun t(n: String) = cls("$root.data.types.$n")

        fun add(tick: Int, type: String, name: String, packet: Any?) {
            val action = create(actionCls, tick, enum(typeCls, type), name, packet)
            actions.getOrPut(tick) { ArrayList() } += action
            count++
            if (!watchers.containsKey(name)) {
                watchers[name] = create(cls("$root.recording.PlayerWatcher"), name)
            }
        }

        fun loc(x: Double, y: Double, z: Double, yaw: Float = 0f, pitch: Float = 0f): Any {
            val l = create(locCls, x, y, z, world)
            locCls.getMethod("setYaw", Float::class.javaPrimitiveType).invoke(l, yaw)
            locCls.getMethod("setPitch", Float::class.javaPrimitiveType).invoke(l, pitch)
            return l
        }

        fun signature(name: String, value: String, sig: String): Any = create(t("SignatureData"), name, value, sig)
        fun spawn(uuid: UUID, x: Double, y: Double, z: Double, yaw: Float, pitch: Float, sig: Any?): Any =
            create(t("SpawnData"), uuid, loc(x, y, z, yaw, pitch), sig)
        fun moving(x: Double, y: Double, z: Double, pitch: Float, yaw: Float): Any = create(t("MovingData"), x, y, z, pitch, yaw)
        fun entityMoving(id: Int, x: Double, y: Double, z: Double, pitch: Float, yaw: Float): Any =
            create(t("EntityMovingData"), id, x, y, z, pitch, yaw)
        fun animation(id: Int): Any = create(t("AnimationData"), id)
        fun chat(text: String): Any = create(t("ChatData"), text)
        fun metadata(burning: Boolean, blocking: Boolean, gliding: Boolean): Any = create(t("MetadataUpdate"), burning, blocking, gliding)
        fun sneak(on: Boolean): Any = create(t("EntityActionData"), enum(playerActionCls, if (on) "START_SNEAKING" else "STOP_SNEAKING"))
        fun entity(action: Int, id: Int, loc: Any, type: String): Any = create(t("EntityData"), action, id, loc, type)
        fun entityItem(action: Int, id: Int, item: Any, loc: Any, velocity: Any): Any = create(t("EntityItemData"), action, id, item, loc, velocity)
        fun tnt(id: Int, loc: Any): Any = create(t("TNTSpawnData"), id, loc)
        fun destroy(id: Int): Any = create(t("EntityDestroyData"), id)
        fun blockChange(loc: Any, before: Any, after: Any): Any = create(t("BlockChangeData"), loc, before, after, false, true)

        fun air(): Any = itemCls.getMethod("air").invoke(null)

        fun itemData(stack: org.bukkit.inventory.ItemStack): Any {
            val serial = stackCls.getMethod("fromItemStack", org.bukkit.inventory.ItemStack::class.java).invoke(null, stack)
            return create(itemCls, serial)
        }

        private fun snapshotItem(s: ItemSnapshot?): Any {
            val stack = ServerCompat.toItemStack(s) ?: return air()
            return try { itemData(stack) } catch (_: Throwable) { air() }
        }

        fun inventory(head: ItemSnapshot?, chest: ItemSnapshot?, leg: ItemSnapshot?, boots: ItemSnapshot?, main: ItemSnapshot?, off: ItemSnapshot?): Any =
            create(t("InvData"), snapshotItem(head), snapshotItem(chest), snapshotItem(leg), snapshotItem(boots), snapshotItem(main), snapshotItem(off))

        /** ItemData for a block state: modern servers use the material, old ones the numeric id and data. */
        fun blockItem(state: String): Any? {
            val (mat, dataValue) = BlockCodec.material(state) ?: return null
            if (mat == Material.AIR) return air()
            return try {
                if (ServerCompat.modern) {
                    itemCls.getMethod("fromMaterial", Material::class.java).invoke(null, mat)
                } else {
                    @Suppress("DEPRECATION")
                    create(itemCls, mat.id, dataValue)
                }
            } catch (_: Throwable) {
                null
            }
        }

        fun entityType(kind: String): String? = try {
            org.bukkit.entity.EntityType.valueOf(kind.uppercase()).name
        } catch (_: Throwable) {
            null
        }

        @Suppress("UNCHECKED_CAST")
        fun finish(durationTicks: Int, creator: String): Any {
            dataCls.getMethod("setDuration", Int::class.javaPrimitiveType).invoke(data, durationTicks)
            dataCls.getMethod("setCreator", String::class.java).invoke(data, creator)
            dataCls.getMethod("setWatchers", HashMap::class.java).invoke(data, HashMap(watchers))
            try {
                val q = dataCls.getDeclaredField("quality")
                q.isAccessible = true
                if (q.get(data) == null) {
                    q.set(data, enum(cls("$root.recording.optimization.ReplayQuality"), "HIGH"))
                }
            } catch (_: Throwable) {
            }
            return data
        }
    }
}
