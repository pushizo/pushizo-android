package com.pushizo.sdk

/** In-app renk kuralları (saf mantık; ARGB tamsayı). */
internal object HsInAppStyle {
    private val HEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    const val LIGHT_TEXT = 0xFF18181B.toInt()
    const val DARK_BACKGROUND = 0xFF18181B.toInt()
    const val DARK_TEXT = 0xFFFAFAFA.toInt()
    const val LIGHT_BUTTON = 0xFF2563EB.toInt()
    const val DARK_BUTTON = 0xFF3B82F6.toInt()

    /** CSS biçimli renk: `#RGB`, `#RRGGBB`, `#RRGGBBAA` (alfa sonda) → ARGB; geçersizse null. */
    fun parseColor(value: String?): Int? {
        val v = value?.trim() ?: return null
        if (!HEX.matches(v)) return null
        val hex = v.substring(1).let { if (it.length == 3) it.map { c -> "$c$c" }.joinToString("") else it }
        val rgb = hex.substring(0, 6).toLong(16)
        val alpha = if (hex.length == 8) hex.substring(6, 8).toLong(16) else 0xFF
        return ((alpha shl 24) or rgb).toInt()
    }

    /** Arka plana okunur metin rengi (YIQ parlaklığı ≥ 150 → koyu metin). */
    fun contrastingText(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val yiq = (r * 299 + g * 587 + b * 114) / 1000
        return if (yiq >= 150) LIGHT_TEXT else DARK_TEXT
    }

    /** Mesaj renkleri: verilmeyen değer sistem temasına (açık/koyu) ya da arka plana göre seçilir. */
    data class Colors(val background: Int, val text: Int, val buttonBackground: Int, val buttonText: Int)

    fun resolve(backgroundColor: String?, textColor: String?, dark: Boolean): Colors {
        val background = parseColor(backgroundColor) ?: if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND
        val text = parseColor(textColor) ?: if (backgroundColor != null && parseColor(backgroundColor) != null) contrastingText(background) else if (dark) DARK_TEXT else LIGHT_TEXT
        val button = if (dark) DARK_BUTTON else LIGHT_BUTTON
        return Colors(background, text, button, contrastingText(button))
    }

    fun buttonColors(button: HsInAppBlock.Button, base: Colors): Pair<Int, Int> {
        val bg = parseColor(button.backgroundColor) ?: base.buttonBackground
        val fg = parseColor(button.textColor) ?: contrastingText(bg)
        return bg to fg
    }

    /** Test ve günlük için `#AARRGGBB`. */
    fun format(color: Int): String = "#%08X".format(color)
}
