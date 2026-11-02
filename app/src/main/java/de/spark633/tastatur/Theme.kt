package de.spark633.tastatur

import android.graphics.Color

/** Farbschema der Tastatur. Tastenfarben werden mit der Deckkraft aus den Einstellungen gemischt. */
class Theme(
    val id: String,
    val name: String,
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val popup: Int,
    val light: Boolean = false,
) {
    companion object {
        val ALL = listOf(
            Theme("dunkel", "Dunkel", Color.rgb(0x1E, 0x27, 0x2B), Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xC8, 255, 255, 255), Color.rgb(0x4D, 0xB6, 0xAC), Color.rgb(0x37, 0x47, 0x4F)),
            Theme("schwarz", "Schwarz (AMOLED)", Color.BLACK, Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xB0, 255, 255, 255), Color.rgb(0x4D, 0xB6, 0xAC), Color.rgb(0x26, 0x26, 0x26)),
            Theme("hell", "Hell", Color.rgb(0xE4, 0xE7, 0xEB), Color.WHITE, Color.rgb(0xB8, 0xC2, 0xCC),
                Color.rgb(0x21, 0x21, 0x21), Color.rgb(0x60, 0x66, 0x6E), Color.rgb(0x00, 0x89, 0x7B),
                Color.WHITE, light = true),
            Theme("blau", "Nachtblau", Color.rgb(0x10, 0x2A, 0x43), Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xC0, 255, 255, 255), Color.rgb(0x4F, 0xC3, 0xF7), Color.rgb(0x24, 0x3B, 0x53)),
            Theme("tuerkis", "Türkis", Color.rgb(0x0E, 0x3B, 0x3A), Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xC0, 255, 255, 255), Color.rgb(0x80, 0xCB, 0xC4), Color.rgb(0x1F, 0x52, 0x50)),
            Theme("lila", "Lila", Color.rgb(0x2A, 0x1B, 0x3D), Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xC0, 255, 255, 255), Color.rgb(0xCE, 0x93, 0xD8), Color.rgb(0x3F, 0x2B, 0x56)),
            Theme("rosa", "Rosa", Color.rgb(0x3D, 0x1B, 0x2E), Color.WHITE, Color.WHITE,
                Color.WHITE, Color.argb(0xC0, 255, 255, 255), Color.rgb(0xF4, 0x8F, 0xB1), Color.rgb(0x56, 0x2B, 0x44)),
        )

        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.first()

        /** Auswahl für Tasten- und Schriftfarbe (0 = aus dem Farbschema). */
        val COLOR_CHOICES = listOf(
            "Automatisch (Farbschema)" to 0,
            "⚪ Weiß" to Color.WHITE,
            "⚫ Schwarz" to Color.rgb(0x11, 0x11, 0x11),
            "🩶 Grau" to Color.rgb(0x9E, 0x9E, 0x9E),
            "🩵 Türkis" to Color.rgb(0x4D, 0xB6, 0xAC),
            "🔵 Blau" to Color.rgb(0x42, 0xA5, 0xF5),
            "🟣 Lila" to Color.rgb(0xAB, 0x47, 0xBC),
            "🩷 Rosa" to Color.rgb(0xF4, 0x8F, 0xB1),
            "🔴 Rot" to Color.rgb(0xE5, 0x39, 0x35),
            "🟠 Orange" to Color.rgb(0xFB, 0x8C, 0x00),
            "🟡 Gelb" to Color.rgb(0xFD, 0xD8, 0x35),
            "🟢 Grün" to Color.rgb(0x43, 0xA0, 0x47),
        )

        private fun alpha(color: Int, a: Float) =
            Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        /** Tasten- und Schriftfarben aus Farbschema, Deckkraft und eigener Auswahl. */
        class KeyColors(val key: Int, val function: Int, val text: Int, val hint: Int)

        fun keyColors(t: Theme, prefs: Prefs): KeyColors {
            val op = prefs.keyOpacity.coerceIn(0, 100) / 100f
            val keyAlpha = if (t.light) 0.5f + op * 0.5f else op
            val own = prefs.keyColor
            val key = alpha(if (own != 0) own else t.key, keyAlpha)
            val function = if (own != 0) alpha(own, keyAlpha * 0.6f)
            else alpha(t.functionKey, if (t.light) keyAlpha else keyAlpha * 0.55f)
            val ownText = prefs.keyTextColor
            val text = if (ownText != 0) ownText else t.text
            val hint = if (ownText != 0) alpha(ownText, 0.75f) else t.hint
            return KeyColors(key, function, text, hint)
        }
    }
}
