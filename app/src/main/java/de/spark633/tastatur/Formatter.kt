package de.spark633.tastatur

import java.text.Normalizer

/**
 * Schreibstile aus Unicode-Zeichen. Sie funktionieren in jeder App, weil es
 * eigene Zeichen sind und keine Formatierung der App.
 */
object Formatter {

    val LABELS = mapOf(
        "bold" to "Fett",
        "italic" to "Kursiv",
        "strike" to "Durchgestrichen",
        "underline" to "Unterstrichen",
        "sarcasm" to "sArKaSmUs",
    )

    /**
     * Abwechselnd klein und groß: "sArKaSmUs". [start] zählt die bisher
     * geschriebenen Buchstaben, damit es über mehrere Tasten weiterläuft.
     */
    fun sarcasm(text: String, start: Int = 0): String {
        var n = start
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetter()) {
                sb.append(if (n % 2 == 0) c.lowercaseChar() else c.uppercaseChar())
                n++
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    fun apply(style: String, text: String): String = when (style) {
        "sarcasm" -> sarcasm(text)
        "bold" -> mapLetters(text, ::bold)
        "italic" -> mapLetters(text, ::italic)
        "strike" -> combine(text, '̶')
        "underline" -> combine(text, '̲')
        else -> text
    }

    /** Jedes Zeichen bekommt ein Kombinationszeichen (Strich durch oder unter). */
    private fun combine(text: String, mark: Char): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            sb.appendCodePoint(cp)
            if (cp != '\n'.code) sb.append(mark)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** Buchstaben ersetzen; Umlaute werden zerlegt (ä = a + ¨), damit sie erhalten bleiben. */
    private fun mapLetters(text: String, map: (Char) -> Int?): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in decomposed) {
            val cp = map(c)
            if (cp != null) sb.appendCodePoint(cp) else sb.append(c)
        }
        return sb.toString()
    }

    // Serifenlose Varianten: sehen auf dem Handy wie normale Schrift aus
    private fun bold(c: Char): Int? = when (c) {
        in 'A'..'Z' -> 0x1D5D4 + (c - 'A')
        in 'a'..'z' -> 0x1D5EE + (c - 'a')
        in '0'..'9' -> 0x1D7EC + (c - '0')
        else -> null
    }

    private fun italic(c: Char): Int? = when (c) {
        in 'A'..'Z' -> 0x1D608 + (c - 'A')
        in 'a'..'z' -> 0x1D622 + (c - 'a')
        else -> null
    }
}
