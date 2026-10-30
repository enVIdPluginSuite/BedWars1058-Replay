package dev.etran.bedreplay.format

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReplayCodecTest {
    private val meta = ReplayMeta(
        id = "abc123", arena = "bw_solo_1", mapName = "Aquarium", group = "Solo", mcVersion = "1.8.8",
        startedAtMillis = 1_700_000_000_000L,
        players = listOf(
            PlayerInfo(1, "11111111-2222-3333-4444-555555555555", "Alice", "Red", 0xFF5555, "tex", "sig"),
            PlayerInfo(2, "66666666-7777-8888-9999-000000000000", "Bob", "Blue", 0x5555FF),
        ),
    )

    private val sword = ItemSnapshot("IRON_SWORD", 1, 3, "§bSharp", mapOf("DAMAGE_ALL" to 2), 0x112233)

    private fun sampleFrames(): List<Pair<Int, List<Action>>> = listOf(
        0 to listOf(
            Action.Spawn(1, "player", 10.5, 64.0, -20.25, 90f, 10f),
            Action.Spawn(2, "player", -100.0, 70.5, 300.0, 180f, 0f),
            Action.Equip(1, Slot.MAIN_HAND, sword),
            Action.Hotbar(1, 0, listOf(sword, null, ItemSnapshot("WOOL", 16, 14))),
        ),
        1 to listOf(
            Action.Move(1, 10.75, 64.0, -20.25, 91f, 10f, true),
            Action.Move(2, -100.0, 70.5, 300.0, 180f, 0f, false), // no change at all
            Action.Flags(1, Flag.SPRINTING or Flag.SNEAKING),
        ),
        5 to listOf(
            Action.BlockChange(10, 64, -21, "WOOL:14", "AIR"),
            Action.BlockChange(11, 64, -21, "WOOL:14", "AIR"),
            Action.Animation(1, Anim.SWING_MAIN),
            Action.Vitals(1, 17.5f, 20f, 18, 3),
        ),
        40 to listOf(
            Action.Chat("§cBob §7was killed by §aAlice", intArrayOf(1, 2)),
            Action.Chat("§eGlobal announcement", null),
            Action.Marker(MarkerKind.KILL, "Alice killed Bob", 2),
            Action.Pickup(77, 1),
            Action.Sound("random.pop", 1.0, 2.0, 3.0, 1f, 1.5f),
            Action.Title(TitleKind.TITLE, "§c§lBED DESTROYED", 0, 0, 0, intArrayOf(1)),
            Action.Title(TitleKind.TIMES, "", 10, 40, 10, null),
            Action.Scoreboard(1, "§e§lBED WARS", listOf("§fRed §c✔", "", "§7kills: 3")),
            Action.Scoreboard(2, null, emptyList()),
            Action.Nametag(1, "§c[R] ", " §7*", true),
            Action.Inventory(1, List(41) { if (it == 3) sword else null }),
            Action.Container(10, 64, -3, "CHEST", -1, listOf(sword, null, ItemSnapshot("DIAMOND", 5))),
            Action.EntityMeta(1004, "name", "§aGuard"),
            Action.Remove(2),
        ),
    )

    private fun write(finish: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        val w = ReplayWriter(bytes, meta)
        for ((tick, actions) in sampleFrames()) w.writeFrame(tick, actions)
        if (finish) w.finish(ReplayFooter(120, 1_700_000_006_000L, "Red")) else w.flush()
        return bytes.toByteArray()
    }

    @Test
    fun roundTripPreservesEverything() {
        val replay = ReplayReader.read(ByteArrayInputStream(write(finish = true)))
        assertEquals("abc123", replay.meta.id)
        assertEquals(120, replay.meta.durationTicks)
        assertEquals("Red", replay.meta.winnerTeam)
        assertEquals(2, replay.meta.players.size)
        assertEquals("tex", replay.meta.players[0].skinValue)
        assertEquals(0xFF5555, replay.meta.players[0].teamColor)
        assertNull(replay.meta.players[1].skinValue)
        assertEquals(listOf(0, 1, 5, 40), replay.frames.map { it.tick })

        val spawn = replay.frames[0].actions[0] as Action.Spawn
        assertEquals(10.5, spawn.x, 1.0 / 32)
        assertEquals(-20.25, spawn.z, 1.0 / 32)
        assertEquals(90f, spawn.yaw, 360f / 256f)

        val equip = replay.frames[0].actions[2] as Action.Equip
        assertEquals(sword, equip.item)

        val hotbar = replay.frames[0].actions[3] as Action.Hotbar
        assertEquals(9, hotbar.items.size)
        assertEquals("WOOL", hotbar.items[2]?.material)
        assertNull(hotbar.items[1])
    }

    @Test
    fun deltaEncodedMovesDecodeToAbsolutePositions() {
        val replay = ReplayReader.read(ByteArrayInputStream(write(finish = true)))
        val move1 = replay.frames[1].actions[0] as Action.Move
        assertEquals(10.75, move1.x, 1.0 / 32)
        assertEquals(64.0, move1.y, 1.0 / 32)
        assertTrue(move1.onGround)
        // an unchanged move must still decode to the previous absolute position
        val move2 = replay.frames[1].actions[1] as Action.Move
        assertEquals(-100.0, move2.x, 1.0 / 32)
        assertEquals(300.0, move2.z, 1.0 / 32)
        assertEquals(180f, move2.yaw, 360f / 256f)
    }

    @Test
    fun blocksChatAndMarkersSurvive() {
        val replay = ReplayReader.read(ByteArrayInputStream(write(finish = true)))
        val blocks = replay.frames[2].actions.filterIsInstance<Action.BlockChange>()
        assertEquals(listOf(10, 11), blocks.map { it.x })
        assertEquals("WOOL:14", blocks[0].block)
        assertEquals("AIR", blocks[0].previous)

        val chats = replay.frames[3].actions.filterIsInstance<Action.Chat>()
        assertContentEquals(intArrayOf(1, 2), chats[0].recipients)
        assertNull(chats[1].recipients)
        assertEquals("§cBob §7was killed by §aAlice", chats[0].text)

        val marker = replay.frames[3].actions.filterIsInstance<Action.Marker>().single()
        assertEquals(MarkerKind.KILL, marker.kind)
        assertEquals(2, marker.subjectId)
    }

    @Test
    fun truncatedFileStillYieldsCompleteFrames() {
        val full = write(finish = false) // synced but no footer / gzip trailer
        val replay = ReplayReader.read(ByteArrayInputStream(full))
        assertEquals(4, replay.frames.size)
        assertEquals(40, replay.meta.durationTicks) // derived from last frame
        assertNull(replay.meta.winnerTeam)

        // chop bytes off the end: must not throw, must keep a prefix of the frames
        val chopped = full.copyOf(full.size - 7)
        val partial = ReplayReader.read(ByteArrayInputStream(chopped))
        assertTrue(partial.frames.size in 1..4)
        assertNotNull(partial.meta)
    }

    @Test
    fun headerCanBeReadWithoutFrames() {
        val header = ReplayReader.readHeader(ByteArrayInputStream(write(finish = true)))
        assertEquals("Aquarium", header.mapName)
        assertEquals("Alice", header.players[0].name)
    }

    @Test
    fun rejectsGarbageAndBackwardsTicks() {
        assertFailsWith<Exception> { ReplayReader.read(ByteArrayInputStream(ByteArray(32) { 7 })) }
        val w = ReplayWriter(ByteArrayOutputStream(), meta)
        w.writeFrame(10, listOf(Action.Remove(1)))
        assertFailsWith<IllegalArgumentException> { w.writeFrame(5, listOf(Action.Remove(1))) }
    }

    @Test
    fun compressesRepetitiveMovementWell() {
        val out = ByteArrayOutputStream()
        val w = ReplayWriter(out, meta)
        w.writeFrame(0, listOf(Action.Spawn(1, "player", 0.0, 64.0, 0.0, 0f, 0f)))
        for (t in 1..2400) { // two minutes of one player running in a line
            w.writeFrame(t, listOf(Action.Move(1, t * 0.2158, 64.0, 0.0, 90f, 0f, true)))
        }
        w.finish(ReplayFooter(2400, 0, null))
        // 2400 move frames should cost well under 4 bytes each after delta + gzip
        assertTrue(out.size() < 2400 * 4, "size was ${out.size()}")
        val back = ReplayReader.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2401, back.frames.size)
    }
}
