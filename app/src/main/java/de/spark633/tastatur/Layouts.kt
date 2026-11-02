package de.spark633.tastatur

/** Was eine Taste tut. */
enum class Action {
    CHAR, SHIFT, DELETE, ENTER, SPACE,
    TO_LETTERS, TO_SYMBOLS, TO_SYMBOLS2, TO_EMOTICONS,
    PAGE_PREV, PAGE_NEXT,
    TO_CLIPBOARD, CLIP_CLEAR,
}

/**
 * Eine Taste.
 * @param text   was beim Antippen eingefügt wird
 * @param up     Zeichen beim Wischen nach oben
 * @param down   Zeichen beim Wischen nach unten
 * @param longPress Auswahl beim langen Drücken
 * @param width  Breite in Einheiten (eine Reihe hat 10 Einheiten)
 * @param cased  true = Buchstabe, wird mit Shift großgeschrieben
 */
data class Key(
    val label: String,
    val action: Action = Action.CHAR,
    val text: String = label,
    val up: String? = null,
    val down: String? = null,
    val longPress: List<String> = emptyList(),
    val width: Float = 1f,
    val cased: Boolean = false,
) {
    val isFunction: Boolean get() = action != Action.CHAR && action != Action.SPACE
}

enum class Language(val code: String, val displayName: String) {
    DE("de", "Deutsch"),
    EN("en", "English"),
    NL("nl", "Nederlands"),
}

object Layouts {

    /**
     * Nur die gängigsten Sonderbuchstaben je Sprache. Taste ohne Eintrag:
     * Langes Drücken liefert das kleine Zeichen der Taste (z. B. q → 1).
     */
    private fun accents(lang: Language): Map<Char, List<String>> = when (lang) {
        Language.DE -> mapOf('a' to listOf("ä"), 'o' to listOf("ö"), 'u' to listOf("ü"), 's' to listOf("ß"))
        Language.NL -> mapOf('e' to listOf("é", "ë"), 'i' to listOf("ï"), 'o' to listOf("ó", "ö"), 'u' to listOf("ü"))
        Language.EN -> emptyMap()
    }

    private fun letter(c: Char, lang: Language, up: String? = null, down: String? = null) = Key(
        label = c.toString(),
        text = c.toString(),
        up = up,
        down = down,
        longPress = accents(lang)[c] ?: listOfNotNull(up ?: down),
        cased = true,
    )

    private fun bottomRow(modeKey: Key) = listOf(
        modeKey,
        Key("^.^", Action.TO_EMOTICONS),
        Key(",", down = "!", longPress = listOf(",", "!", ";")),
        Key(" ", Action.SPACE, width = 4f),
        Key(".", down = "?", longPress = listOf(".", "?", "!", "…", ":", ";", "-", "'", "\"")),
        Key("↵", Action.ENTER, width = 1.5f),
    )

    private val shiftKey = Key("⇧", Action.SHIFT, width = 1.5f)
    private val deleteKey = Key("⌫", Action.DELETE, width = 1.5f)

    fun letters(qwertz: Boolean, lang: Language): List<List<Key>> {
        val r1 = (if (qwertz) "qwertzuiop" else "qwertyuiop")
            .mapIndexed { i, c -> letter(c, lang, up = "1234567890"[i].toString()) }
        val r2 = "asdfghjkl".toList()
            .zip(listOf("@", "#", "&", "*", "-", "+", "=", "(", ")")) { c, s -> letter(c, lang, up = s) }
        // Untere Buchstabenreihe: Sonderzeichen per Wischen nach UNTEN
        val r3 = listOf(shiftKey) +
            (if (qwertz) "yxcvbnm" else "zxcvbnm").toList()
                .zip(listOf("_", "€", "\"", "'", ":", ";", "/")) { c, s -> letter(c, lang, down = s) } +
            deleteKey
        return listOf(r1, r2, r3, bottomRow(Key("123", Action.TO_SYMBOLS, width = 1.5f)))
    }

    private fun chars(s: String) = s.split(" ").map { Key(it) }

    fun symbols(): List<List<Key>> = listOf(
        chars("1 2 3 4 5 6 7 8 9 0"),
        chars("@ # € _ & - + ( ) /"),
        listOf(Key("=\\<", Action.TO_SYMBOLS2, width = 1.5f)) + chars("* \" ' : ; ! ?") + deleteKey,
        bottomRow(Key("ABC", Action.TO_LETTERS, width = 1.5f)),
    )

    fun symbols2(): List<List<Key>> = listOf(
        chars("~ ` | • √ π ÷ × § ¶"),
        chars("£ ¥ $ ¢ ^ ° = { } \\"),
        listOf(Key("?123", Action.TO_SYMBOLS, width = 1.5f)) + chars("% © ® ™ ✓ [ ]") + deleteKey,
        bottomRow(Key("ABC", Action.TO_LETTERS, width = 1.5f)),
    )

    val EMOTICONS = listOf(
        "^.^", "^^", "^_^", "^-^",
        ":)", ":D", ";)", ":P",
        "xD", ":(", ":'(", "<3",
        ">.<", "-.-", "o.O", ":3",
        "(^_^)", "(>_<)", "(^_^;)", "uwu",
        "¯\\_(ツ)_/¯", "(╯°□°)╯︵ ┻━┻", "( ͡° ͜ʖ ͡°)", "owo",
    )
    const val EMOTICONS_PER_PAGE = 12

    val emoticonPages: Int get() = (EMOTICONS.size + EMOTICONS_PER_PAGE - 1) / EMOTICONS_PER_PAGE

    /** Untere Reihe der Zwischenablage-Ansicht; darüber liegt die Liste. */
    fun clipboard(): List<List<Key>> = listOf(
        emptyList(), emptyList(), emptyList(),
        listOf(
            Key("ABC", Action.TO_LETTERS, width = 2.5f),
            Key("Leeren", Action.CLIP_CLEAR, width = 5f),
            Key("⌫", Action.DELETE, width = 2.5f),
        ),
    )

    fun emoticons(page: Int): List<List<Key>> {
        val items = EMOTICONS.drop(page * EMOTICONS_PER_PAGE).take(EMOTICONS_PER_PAGE)
        val rows = items.chunked(4).map { row -> row.map { Key(it, width = 2.5f) } }.toMutableList()
        while (rows.size < 3) rows.add(emptyList())
        rows.add(
            listOf(
                Key("ABC", Action.TO_LETTERS, width = 2f),
                Key("‹", Action.PAGE_PREV, width = 1.5f),
                Key(" ", Action.SPACE, width = 3f),
                Key("›", Action.PAGE_NEXT, width = 1.5f),
                Key("⌫", Action.DELETE, width = 2f),
            )
        )
        return rows
    }
}
