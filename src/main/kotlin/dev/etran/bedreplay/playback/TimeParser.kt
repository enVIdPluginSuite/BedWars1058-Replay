package dev.etran.bedreplay.playback

/**
 * Parses what a viewer types for "skip to a time". Pure logic so it can be unit-tested.
 *
 * Accepted: `3:45`, `1:02:03` (absolute), `90` or `90s` (absolute seconds), `+30` / `-30`
 * (relative to now), `50%` (percentage of the replay).
 */
object TimeParser {
    /** Returns the target in seconds (clamped to the replay), or null if the text is not a time. */
    fun parse(input: String, currentSeconds: Int, durationSeconds: Int): Int? {
        val text = input.trim().lowercase().removeSuffix("s").trim()
        if (text.isEmpty()) return null
        val result: Int = when {
            text.endsWith("%") -> {
                val pct = text.dropLast(1).trim().toDoubleOrNull() ?: return null
                if (pct < 0 || pct > 100) return null
                (durationSeconds * pct / 100.0).toInt()
            }
            text.startsWith("+") || text.startsWith("-") -> {
                val sign = if (text[0] == '-') -1 else 1
                val delta = clock(text.substring(1).trim()) ?: return null
                currentSeconds + sign * delta
            }
            else -> clock(text) ?: return null
        }
        return result.coerceIn(0, durationSeconds.coerceAtLeast(0))
    }

    /** "m:ss", "h:mm:ss" or plain seconds. */
    private fun clock(s: String): Int? {
        if (s.isEmpty()) return null
        val parts = s.split(':')
        if (parts.size > 3) return null
        var total = 0L
        for (p in parts) {
            val n = p.trim().toLongOrNull() ?: return null
            if (n < 0) return null
            total = total * 60 + n
        }
        return if (total > Int.MAX_VALUE) null else total.toInt()
    }
}
