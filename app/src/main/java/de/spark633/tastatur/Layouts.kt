package de.spark633.tastatur

/** Was eine Taste tut. */
enum class Action {
    CHAR, SHIFT, DELETE, ENTER, SPACE,
    TO_LETTERS, TO_SYMBOLS, TO_SYMBOLS2, TO_EMOTICONS,
    PAGE_PREV, PAGE_NEXT,
    TO_CLIPBOARD, CLIP_CLEAR,
    MENU, FORMAT,
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
        // Umlaute/Akzente und zusätzlich das Wisch-Zeichen (u → ü 7)
        longPress = ((accents(lang)[c] ?: emptyList()) + listOfNotNull(up ?: down)).distinct(),
        cased = true,
    )

    private fun bottomRow(modeKey: Key) = listOf(
        modeKey,
        Key("^.^", Action.TO_EMOTICONS),
        Key(",", down = "!", longPress = listOf(",", "!", ";")),
        Key(" ", Action.SPACE, width = 4f),
        Key(".", up = "!", down = "?", longPress = listOf("!", "?")),
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

    fun emoticonPages(list: List<String>): Int = maxOf(1, (list.size + EMOTICONS_PER_PAGE - 1) / EMOTICONS_PER_PAGE)

    /** Menü hinter dem Zahnrad. text = Bereich der Einstellungen. */
    fun menu(): List<List<Key>> = listOf(
        listOf(Key("⚙  Allgemein", Action.MENU, text = "general", width = 5f),
            Key("📖  Wörterbuch", Action.MENU, text = "dictionary", width = 5f)),
        listOf(Key("📋  Zwischenablage", Action.MENU, text = "clipboard", width = 5f),
            Key("↕  Größe anpassen", Action.MENU, text = "resize", width = 5f)),
        listOf(Key("🌍  Sprachen", Action.MENU, text = "languages", width = 5f),
            Key("🎨  Aussehen", Action.MENU, text = "design", width = 5f)),
        listOf(Key("ABC", Action.TO_LETTERS, width = 3f),
            Key("🔍  Suche", Action.MENU, text = "search", width = 3.5f),
            Key("✎  Formatieren", Action.MENU, text = "format", width = 3.5f)),
    )

    /** Formatieren: Listen, Unicode-Stile (überall sichtbar) und WhatsApp-Zeichen. */
    fun format(): List<List<Key>> = listOf(
        listOf(Key("•  Aufzählung", Action.FORMAT, text = "bullet", width = 5f),
            Key("1.  Nummeriert", Action.FORMAT, text = "number", width = 5f)),
        listOf(Key("𝗙𝗲𝘁𝘁", Action.FORMAT, text = "bold", width = 2f),
            Key("𝘒𝘶𝘳𝘴𝘪𝘷", Action.FORMAT, text = "italic", width = 2f),
            Key("D̶u̶r̶c̶h̶", Action.FORMAT, text = "strike", width = 2f),
            Key("U̲n̲t̲e̲r̲", Action.FORMAT, text = "underline", width = 2f),
            Key("sArKaSmUs", Action.FORMAT, text = "sarcasm", width = 2f)),
        listOf(Key("*fett*", Action.FORMAT, text = "wa_bold", width = 2.5f),
            Key("_kursiv_", Action.FORMAT, text = "wa_italic", width = 2.5f),
            Key("~durch~", Action.FORMAT, text = "wa_strike", width = 2.5f),
            Key("```mono```", Action.FORMAT, text = "wa_mono", width = 2.5f)),
        listOf(Key("ABC", Action.TO_LETTERS, width = 3f),
            Key(" ", Action.SPACE, width = 4f),
            Key("⌫", Action.DELETE, width = 3f)),
    )

    /** Untere Reihe der Zwischenablage-Ansicht; darüber liegt die Liste. */
    fun clipboard(): List<List<Key>> = listOf(
        emptyList(), emptyList(), emptyList(),
        listOf(
            Key("ABC", Action.TO_LETTERS, width = 2.5f),
            Key("Leeren", Action.CLIP_CLEAR, width = 5f),
            Key("⌫", Action.DELETE, width = 2.5f),
        ),
    )

    fun emoticons(page: Int, list: List<String>): List<List<Key>> {
        val items = list.drop(page * EMOTICONS_PER_PAGE).take(EMOTICONS_PER_PAGE)
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

    /** Welche Buchstaben auf der Tastatur nebeneinander liegen (für die Tippfehler-Erkennung). */
    fun neighbors(qwertz: Boolean): Map<Char, Set<Char>> {
        val rows = listOf(
            (if (qwertz) "qwertzuiop" else "qwertyuiop") to 0f,
            "asdfghjkl" to 0.5f,
            (if (qwertz) "yxcvbnm" else "zxcvbnm") to 1.5f,
        )
        val pos = HashMap<Char, Pair<Float, Float>>()
        rows.forEachIndexed { r, (letters, offset) ->
            letters.forEachIndexed { i, c -> pos[c] = (i + offset) to r.toFloat() }
        }
        val map = HashMap<Char, Set<Char>>()
        for ((c, p) in pos) {
            map[c] = pos.filter { (o, q) ->
                o != c && kotlin.math.abs(q.second - p.second) <= 1f && kotlin.math.abs(q.first - p.first) <= 1.01f
            }.keys
        }
        return map
    }
}
