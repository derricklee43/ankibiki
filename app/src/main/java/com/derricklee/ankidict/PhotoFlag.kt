package com.derricklee.ankidict

/**
 * v11 photo flagger palette (user 2026-11-15: "Fixed palette for now, eventually … a color
 * selector, so make sure the fixed palette is not too tightly coupled").
 *
 * The DB column (scraped_photos.flag_color) stores an OPAQUE STRING and nothing else about
 * the feature interprets storage: this object only RESOLVES a stored string into something
 * renderable. Adding a color picker later means storing "#RRGGBB" strings — zero migration,
 * zero semantic change. Unknown strings still render (as hex when parseable, else neutral
 * gray), so a value written by a future build never crashes a current one.
 */
object PhotoFlagPalette {

    /** Palette entries in chip order. [key] is what gets stored in flag_color. */
    data class Entry(val key: String, val labelJa: String, val argb: Int)

    val entries: List<Entry> = listOf(
        Entry(KEY_RED, "赤", 0xFFE53935.toInt()),
        Entry(KEY_YELLOW, "黄", 0xFFFDD835.toInt()),
        Entry(KEY_GREEN, "緑", 0xFF43A047.toInt()),
        Entry(KEY_BLUE, "青", 0xFF1E88E5.toInt()),
        Entry(KEY_PURPLE, "紫", 0xFF8E24AA.toInt()),
    )

    const val KEY_RED = "red"
    const val KEY_YELLOW = "yellow"
    const val KEY_GREEN = "green"
    const val KEY_BLUE = "blue"
    const val KEY_PURPLE = "purple"

    /** Fallback dot color for values the palette does not know (e.g. an unrecognized string). */
    val UNKNOWN_GRAY: Int = 0xFF9E9E9E.toInt()

    /** Resolve any stored flag string to an ARGB color for rendering. null input = no flag. */
    fun resolveColor(stored: String?): Int? {
        if (stored == null || stored.isBlank()) return null
        entryFor(stored)?.let { return it.argb }
        return parseHex(stored) ?: UNKNOWN_GRAY
    }

    /** Human label for a stored flag: palette name, or the raw string (hex/future values). */
    fun displayName(stored: String?): String {
        if (stored == null || stored.isBlank()) return ""
        return entryFor(stored)?.labelJa ?: stored
    }

    /** true when the value is one of the fixed palette keys (vs an ad-hoc hex or unknown). */
    fun isPaletteKey(stored: String?): Boolean = entryFor(stored) != null

    private fun entryFor(stored: String?): Entry? =
        if (stored == null) null else entries.firstOrNull { it.key.equals(stored, ignoreCase = true) }

    /** Accepts #RGB and #RRGGBB (opaque, alpha forced full); anything else returns null. */
    internal fun parseHex(stored: String): Int? {
        val s = stored.trim()
        if (!s.startsWith("#")) return null
        val hex = s.removePrefix("#")
        val digits = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex
            else -> return null
        }
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val rgb = digits.toLong(16)
        return (0xFF000000L or rgb).toInt()
    }
}
