package dev.etran.bedreplay.record

import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState
import dev.etran.bedreplay.util.ServerCompat
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.World

/**
 * Canonical block representation in replay files: the namespaced string PacketEvents uses for a
 * block state (e.g. "minecraft:red_wool", "minecraft:oak_stairs[facing=east,half=bottom]").
 *
 * PacketEvents maps every server version's block ids onto the same modern names, so a replay
 * recorded on 1.8 can be applied on 1.21 and vice versa.
 */
object BlockCodec {
    const val AIR = "minecraft:air"

    /** Block-state string for a global block id as sent in packets on this server version. */
    fun fromGlobalId(id: Int): String? = try {
        WrappedBlockState.getByGlobalId(id).toString()
    } catch (_: Throwable) {
        null
    }

    private val toBukkitBlockData = try {
        SpigotConversionUtil::class.java.methods.firstOrNull { it.name == "toBukkitBlockData" }
    } catch (_: Throwable) { null }

    private val setBlockData = try {
        val data = Class.forName("org.bukkit.block.data.BlockData")
        org.bukkit.block.Block::class.java.getMethod("setBlockData", data, Boolean::class.javaPrimitiveType)
    } catch (_: Throwable) { null }

    /** Applies a recorded block state to a world without triggering physics. Unknown blocks are skipped. */
    fun apply(world: World, x: Int, y: Int, z: Int, state: String) {
        try {
            val wrapped = WrappedBlockState.getByString(state) ?: return
            val block = world.getBlockAt(x, y, z)
            if (ServerCompat.modern) {
                val data = toBukkitBlockData?.invoke(null, wrapped) ?: return
                setBlockData?.invoke(block, data, false)
            } else {
                val md = SpigotConversionUtil.toBukkitMaterialData(wrapped)
                @Suppress("DEPRECATION")
                block.setTypeIdAndData(md.itemTypeId, md.data, false)
            }
        } catch (_: Throwable) {
            // a block that does not exist on this version: keep whatever the template has there
        }
    }

    private val getBlockData = try { org.bukkit.block.Block::class.java.getMethod("getBlockData") } catch (_: Throwable) { null }
    private val fromBukkitBlockData = try {
        SpigotConversionUtil::class.java.methods.firstOrNull { it.name == "fromBukkitBlockData" }
    } catch (_: Throwable) { null }

    /** The block currently at a position, in canonical form, or null if it cannot be encoded. */
    fun current(world: World, x: Int, y: Int, z: Int): String? = try {
        val block = world.getBlockAt(x, y, z)
        if (ServerCompat.modern) {
            fromBukkitBlockData!!.invoke(null, getBlockData!!.invoke(block)).toString()
        } else {
            @Suppress("DEPRECATION")
            SpigotConversionUtil.fromBukkitMaterialData(org.bukkit.material.MaterialData(block.type, block.data)).toString()
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Material (and, on pre-1.13 servers, legacy data value) behind a canonical block-state string.
     * Used by exporters that have to talk in plain materials.
     */
    fun material(state: String): Pair<org.bukkit.Material, Int>? = try {
        val wrapped = WrappedBlockState.getByString(state) ?: return null
        if (ServerCompat.modern) {
            val name = state.removePrefix("minecraft:").substringBefore('[').uppercase()
            org.bukkit.Material.matchMaterial(name)?.let { it to 0 }
        } else {
            val md = SpigotConversionUtil.toBukkitMaterialData(wrapped)
            md.itemType to md.data.toInt()
        }
    } catch (_: Throwable) {
        null
    }
}
