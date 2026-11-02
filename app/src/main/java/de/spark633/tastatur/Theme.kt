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
    }
}
