package dev.etran.bedreplay.util

/** The 16 chat colours, to turn a team's RGB colour into a `§` code. */
object ChatPalette {
    private val PALETTE = listOf(
        '0' to 0x000000, '1' to 0x0000AA, '2' to 0x00AA00, '3' to 0x00AAAA, '4' to 0xAA0000, '5' to 0xAA00AA,
        '6' to 0xFFAA00, '7' to 0xAAAAAA, '8' to 0x555555, '9' to 0x5555FF, 'a' to 0x55FF55, 'b' to 0x55FFFF,
        'c' to 0xFF5555, 'd' to 0xFF55FF, 'e' to 0xFFFF55, 'f' to 0xFFFFFF,
    )

    fun nearest(rgb: Int): Char {
        var best = 'f'
        var bestDist = Int.MAX_VALUE
        for ((code, c) in PALETTE) {
            val dr = ((rgb shr 16) and 0xFF) - ((c shr 16) and 0xFF)
            val dg = ((rgb shr 8) and 0xFF) - ((c shr 8) and 0xFF)
            val db = (rgb and 0xFF) - (c and 0xFF)
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) { bestDist = d; best = code }
        }
        return best
    }

    fun code(rgb: Int): String = if (rgb < 0) "" else "§" + nearest(rgb)
}
