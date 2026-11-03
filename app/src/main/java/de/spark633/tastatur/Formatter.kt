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
        "sarcasm" to "sPoNgEbOb",
        "small" to "Klein",
        "glitch" to "Glitch",
    )

    /**
     * Abwechselnd klein und groß ("SpongeBob-Schrift"): "sPoNgEbOb". [start] zählt die bisher
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
        "small" -> mapLetters(text) { SMALL[it]?.code }
        "glitch" -> glitch(text)
        "upside" -> upsideDown(text)
        "mirror" -> mirror(text)
        "plain" -> plain(text)
        "binary" -> binary(text)
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

    // --- Spaß-Stile ---

    /** Kleiner (hochgestellter) Text: ʰᵃˡˡᵒ. Fehlende Zeichen bleiben normal. */
    private val SMALL: Map<Char, Char> = buildMap {
        "abcdefghijklmnoprstuvwxyz".zip("ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ").forEach { (a, b) -> put(a, b) }
        "ABDEGHIJKLMNOPRTUVW".zip("ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ").forEach { (a, b) -> put(a, b) }
        "CFQSXYZ".forEach { c -> SMALL_LOWER[c.lowercaseChar()]?.let { put(c, it) } }
        "0123456789+-=()".zip("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾").forEach { (a, b) -> put(a, b) }
    }
    private val SMALL_LOWER: Map<Char, Char>
        get() = "abcdefghijklmnoprstuvwxyz".zip("ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻ").toMap()

    /** Akzente normaler Buchstaben (à á â ã ä å ç …), die "Normal" behält. */
    private const val KEEP_ACCENTS = "\u0300\u0301\u0302\u0303\u0308\u030A\u0327"

    /** Glitch: jedes Zeichen bekommt zufällige Striche darüber und darunter. */
    private const val GLITCH_ABOVE = "\u0304\u0306\u0307\u030B\u030C\u0310\u0311\u0312\u033D\u0346\u034A\u034B\u034C\u0350\u0351\u0357\u035B"
    private const val GLITCH_BELOW = "\u0316\u0317\u0318\u0319\u031C\u031D\u031E\u031F\u0320\u0324\u0325\u0326\u0329\u032A\u032B\u032C\u032D\u032E\u0330\u0331\u0332\u0333\u0339\u033A\u033B\u0347\u0348\u0349\u034D\u034E\u0353"
    private const val GLITCH_THROUGH = "\u0334\u0335\u0336\u0337\u0338"

    private fun glitch(text: String): String {
        val r = java.util.Random()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c.isWhitespace()) continue
            sb.append(GLITCH_THROUGH[r.nextInt(GLITCH_THROUGH.length)])
            repeat(1 + r.nextInt(2)) { sb.append(GLITCH_ABOVE[r.nextInt(GLITCH_ABOVE.length)]) }
            repeat(1 + r.nextInt(2)) { sb.append(GLITCH_BELOW[r.nextInt(GLITCH_BELOW.length)]) }
        }
        return sb.toString()
    }

    /** Zeichen samt ihren Akzenten (ä = a + ¨) zusammenhalten, damit Umdrehen nichts zerreißt. */
    private fun clusters(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            var end = i + Character.charCount(cp)
            while (end < text.length && Character.getType(text.codePointAt(end)) == Character.NON_SPACING_MARK.toInt()) {
                end += Character.charCount(text.codePointAt(end))
            }
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    private val UPSIDE: Map<Char, String> = buildMap {
        val lower = "abcdefghijklmnopqrstuvwxyz".toList().zip(listOf("ɐ", "q", "ɔ", "p", "ǝ", "ɟ", "ƃ", "ɥ", "ᴉ", "ɾ", "ʞ", "l", "ɯ",
            "u", "o", "d", "b", "ɹ", "s", "ʇ", "n", "ʌ", "ʍ", "x", "ʎ", "z"))
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList().zip(listOf("∀", "ꓭ", "Ɔ", "ꓷ", "Ǝ", "Ⅎ", "⅁", "H", "I", "ſ", "ꓘ", "˥", "W",
            "N", "O", "Ԁ", "Ό", "ꓤ", "S", "⊥", "∩", "Λ", "M", "X", "⅄", "Z"))
        val other = "0123456789.,'!?()[]{}<>&_\";".toList().zip(listOf("0", "Ɩ", "ᄅ", "Ɛ", "ㄣ", "ϛ", "9", "ㄥ", "8", "6", "˙", "'", ",",
            "¡", "¿", ")", "(", "]", "[", "}", "{", ">", "<", "⅋", "‾", "„", "؛"))
        (lower + upper + other).forEach { (a, b) -> put(a, b) }
    }

    /** Kopfüber: Text um 180° drehen (Hallo → ollɐH). */
    private fun upsideDown(text: String): String = mapClusters(text, reverse = true) { UPSIDE[it] }

    private val MIRROR: Map<Char, String> = buildMap {
        val lower = "abcdefghijklmnopqrstuvwxyz".toList().zip(listOf("ɒ", "d", "ɔ", "b", "ɘ", "ʇ", "ϱ", "ʜ", "i", "ᒑ", "ʞ",
            "l", "m", "n", "o", "q", "p", "ɿ", "ꙅ", "ƚ", "u", "v", "w", "x", "γ", "z"))
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList().zip(listOf("A", "ᙠ", "Ɔ", "ᗡ", "Ǝ", "ꟻ", "Ꭾ", "H", "I", "Ⴑ", "ꓘ",
            "⅃", "M", "И", "O", "ꟼ", "Ọ", "Я", "Ƨ", "T", "U", "V", "W", "X", "Y", "Ƹ"))
        val other = "()[]{}<>?/\\".toList().zip(listOf(")", "(", "]", "[", "}", "{", ">", "<", "⸮", "\\", "/"))
        (lower + upper + other).forEach { (a, b) -> put(a, b) }
    }

    /** Spiegelschrift: Zeichen spiegeln und von rechts nach links (Hallo → ollɒH). */
    private fun mirror(text: String): String = mapClusters(text, reverse = true) { MIRROR[it] }

    private fun mapClusters(text: String, reverse: Boolean, map: (Char) -> String?): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val parts = clusters(decomposed).let { if (reverse) it.reversed() else it }
        return parts.joinToString("") { cl -> (map(cl[0]) ?: cl[0].toString()) + cl.substring(1) }
            .let { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }

    /**
     * Wieder normaler Text: 𝗳𝗲𝘁𝘁, 𝘬𝘶𝘳𝘴𝘪𝘷, ᵏˡᵉⁱⁿ werden zu normalen Buchstaben, Striche
     * (durch, unter, Glitch) fallen weg, echte Umlaute und Akzente bleiben.
     */
    fun plain(text: String): String {
        val compat = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val decomposed = Normalizer.normalize(compat, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (cl in clusters(decomposed)) {
            val baseLen = Character.charCount(cl.codePointAt(0))
            val base = cl.substring(0, baseLen)
            sb.append(base)
            // Striche (durch, unter, Glitch) weglassen; einen echten Akzent behalten (ä, é)
            val keep = cl.substring(baseLen).firstOrNull { m ->
                m in KEEP_ACCENTS && Normalizer.normalize(base + m, Normalizer.Form.NFC).length == 1
            }
            if (keep != null) sb.append(keep)
        }
        // SpongeBob-Wörter komplett klein: hAlLo → hallo
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
            .replace(Regex("[\\p{L}']+")) { m -> if (isSarcasm(m.value)) m.value.lowercase() else m.value }
    }

    /** Wechselt fast jeder Buchstabe zwischen groß und klein? (hAlLo ja, Hallo/iPhone nein) */
    private fun isSarcasm(word: String): Boolean {
        val letters = word.filter { it.isLetter() && it.lowercaseChar() != it.uppercaseChar() }
        if (letters.length < 3 || letters.none { it.isUpperCase() } || letters.none { it.isLowerCase() }) return false
        val switches = letters.zipWithNext().count { (a, b) -> a.isUpperCase() != b.isUpperCase() }
        return switches >= (letters.length - 1) * 0.75
    }

    /**
     * Binär: Text → 8er-Blöcke (UTF-8, also auch Umlaute und Emojis), z. B. "Hi" → "01001000 01101001".
     * Besteht der Text schon nur aus 0, 1 und Leerzeichen, wird er zurückverwandelt.
     */
    fun binary(text: String): String {
        val compact = text.filter { !it.isWhitespace() }
        if (compact.isNotEmpty() && compact.all { it == '0' || it == '1' } && compact.length % 8 == 0) {
            val bytes = compact.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
            return String(bytes, Charsets.UTF_8)
        }
        return text.toByteArray(Charsets.UTF_8).joinToString(" ") {
            (it.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
    }
}
