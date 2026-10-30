package dev.etran.bedreplay.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeParserTest {
    private fun p(s: String, now: Int = 100, dur: Int = 600) = TimeParser.parse(s, now, dur)

    @Test
    fun absoluteClockTimes() {
        assertEquals(225, p("3:45"))
        assertEquals(3723, p("1:02:03", dur = 10_000))
        assertEquals(90, p("90"))
        assertEquals(90, p("90s"))
        assertEquals(5, p("0:05"))
    }

    @Test
    fun relativeTimes() {
        assertEquals(130, p("+30", now = 100))
        assertEquals(70, p("-30", now = 100))
        assertEquals(0, p("-500", now = 100)) // clamped at the start
        assertEquals(600, p("+9999", now = 100)) // clamped at the end
        assertEquals(160, p("+1:00", now = 100))
    }

    @Test
    fun percentages() {
        assertEquals(300, p("50%"))
        assertEquals(0, p("0%"))
        assertEquals(600, p("100%"))
        assertNull(p("101%"))
    }

    @Test
    fun clampsAbsoluteToDuration() {
        assertEquals(600, p("99:59"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(p(""))
        assertNull(p("abc"))
        assertNull(p("1:2:3:4"))
        assertNull(p("1::2"))
        assertNull(p("-"))
        assertNull(p("12:xx"))
        assertNull(p("--5"))
    }

    @Test
    fun toleratesWhitespaceAndCase() {
        assertEquals(225, p("  3:45  "))
        assertEquals(130, p(" + 30 S"))
    }
}
