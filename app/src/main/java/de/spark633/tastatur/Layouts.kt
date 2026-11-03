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
        // Umlaute; beim e zusätzlich é
        Language.DE -> mapOf(
            'a' to listOf("ä"), 'e' to listOf("é"), 'o' to listOf("ö"), 'u' to listOf("ü"), 's' to listOf("ß"),
        )
        Language.NL -> mapOf(
            'e' to listOf("é", "ë"), 'i' to listOf("ï"), 'o' to listOf("ó", "ö"), 'u' to listOf("ü"),
        )
        Language.EN -> mapOf('e' to listOf("é"))
    }

    private fun letter(c: Char, lang: Language, up: String? = null, down: String? = null) = Key(
        label = c.toString(),
        text = c.toString(),
        up = up,
        down = down,
        // Umlaute/Akzente und zusätzlich das Wisch-Zeichen (u → ü 7); x: alle Währungen
        longPress = if (c == 'x') listOf("€", "$", "£", "¥", "¢")
        else ((accents(lang)[c] ?: emptyList()) + listOfNotNull(up ?: down)).distinct(),
        cased = true,
    )

    private fun bottomRow(modeKey: Key) = listOf(
        modeKey,
        Key("^.^", Action.TO_EMOTICONS),
        Key(",", down = ";", longPress = listOf(";")),
        Key(" ", Action.SPACE, width = 4.5f),
        Key(".", up = "!", down = "?", longPress = listOf("!", ",", ".", "?")),
        Key("↵", Action.ENTER, width = 1.5f),
    )

    private val shiftKey = Key("⇧", Action.SHIFT, width = 1.5f)
    private val deleteKey = Key("⌫", Action.DELETE, width = 1.5f)

    fun letters(qwertz: Boolean, lang: Language): List<List<Key>> {
        val r1 = (if (qwertz) "qwertzuiop" else "qwertyuiop")
            .mapIndexed { i, c -> letter(c, lang, up = "1234567890"[i].toString()) }
        val r2 = "asdfghjkl".toList()
            .zip(listOf("@", "#", "&", "*", "'", "+", "=", "(", ")")) { c, s -> letter(c, lang, up = s) }
        // Untere Buchstabenreihe: Sonderzeichen per Wischen nach UNTEN
        val r3 = listOf(shiftKey) +
            (if (qwertz) "yxcvbnm" else "zxcvbnm").toList()
                .zip(listOf("_", "€", "-", "\"", ":", ";", "/")) { c, s -> letter(c, lang, down = s) } +
            deleteKey
        return listOf(r1, r2, r3, bottomRow(Key("123", Action.TO_SYMBOLS, width = 1f)))
    }

    /** Extras beim langen Drücken (oder seitlich ziehen) auf den Symbolseiten. */
    private val SYMBOL_EXTRAS = mapOf(
        "1" to listOf("¹", "½", "¼"),
        "2" to listOf("²"),
        "3" to listOf("³", "¾"),
        "0" to listOf("°"),
        "€" to listOf("£", "$", "¥", "¢"),
        "-" to listOf("–", "—"),
        "\"" to listOf("„", "“", "”", "«", "»"),
        "'" to listOf("‚", "‘", "’", "‹", "›"),
        "!" to listOf("¡"),
        "*" to listOf("•", "×", "†", "‡", "⸸"),
        "+" to listOf("±"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "✓" to listOf("✔", "✗", "✘"),
        "/" to listOf("÷", "\\"),
        "%" to listOf("‰"),
        "=" to listOf("≠", "≈"),
        "©" to listOf("®", "™"),
        "°" to listOf("℃", "℉"),
        "<" to listOf("≤"),
        ">" to listOf("≥"),
        "?" to listOf("¿"),
    )

    private fun chars(s: String) = s.split(" ").map { Key(it, longPress = SYMBOL_EXTRAS[it] ?: emptyList()) }

    fun symbols(): List<List<Key>> = listOf(
        chars("1 2 3 4 5 6 7 8 9 0"),
        chars("@ # € _ & - + ( ) /"),
        listOf(Key("=\\<", Action.TO_SYMBOLS2, width = 1.5f)) + chars("* \" ' : ; ! ?") + deleteKey,
        bottomRow(Key("ABC", Action.TO_LETTERS, width = 1f)),
    )

    fun symbols2(): List<List<Key>> = listOf(
        chars("~ ` | • √ π ÷ × § ¶"),
        // Klammern paarweise nebeneinander, ^ gleich daneben
        chars("© ✓ [ ] { } < > ^ °"),
        listOf(Key("?123", Action.TO_SYMBOLS, width = 1.5f)) + chars("£ ¥ $ ¢ % = \\") + deleteKey,
        bottomRow(Key("ABC", Action.TO_LETTERS, width = 1f)),
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
        listOf(Key("💾  Profile", Action.MENU, text = "profiles", width = 5f),
            Key("↕  Größe anpassen", Action.MENU, text = "resize", width = 5f)),
        listOf(Key("🌍  Sprachen", Action.MENU, text = "languages", width = 5f),
            Key("🎨  Aussehen", Action.MENU, text = "design", width = 5f)),
        listOf(Key("🔍  Suche", Action.MENU, text = "search", width = 5f),
            Key("✎  Formatieren", Action.MENU, text = "format", width = 5f)),
    )

    /** Formatieren: Listen, Unicode-Stile (überall sichtbar) und Telegram-/Discord-Zeichen. */
    /** Ziffernblock für reine Zahlenfelder (z. B. Codes in der Authenticator-App). */
    enum class NumpadKind { NUMBER, PHONE, DATETIME }

    fun numpad(kind: NumpadKind): List<List<Key>> {
        fun digit(d: String) = Key(d, width = 2.5f)
        val (side1, side2, extra) = when (kind) {
            NumpadKind.NUMBER -> Triple(Key("-", width = 2.5f), Key(",", longPress = listOf("."), width = 2.5f), Key(".", width = 2.5f))
            NumpadKind.PHONE -> Triple(Key("+", width = 2.5f), Key("*", longPress = listOf("(", ")", "/", "-"), width = 2.5f), Key("#", width = 2.5f))
            NumpadKind.DATETIME -> Triple(Key(":", width = 2.5f), Key("/", longPress = listOf("-", "."), width = 2.5f), Key(".", width = 2.5f))
        }
        return listOf(
            listOf(digit("1"), digit("2"), digit("3"), side1),
            listOf(digit("4"), digit("5"), digit("6"), side2),
            listOf(digit("7"), digit("8"), digit("9"), Key("⌫", Action.DELETE, width = 2.5f)),
            listOf(Key("ABC", Action.TO_LETTERS, width = 2.5f), digit("0"), extra, Key("↵", Action.ENTER, width = 2.5f)),
        )
    }

    const val FORMAT_PAGES = 2

    /** Formatieren, Seite 1: Listen, Unicode-Stile, Telegram/Discord. Seite 2: Spaß-Stile (sPoNgEbOb …). */
    fun format(page: Int): List<List<Key>> {
        val bottom = listOf(
            Key("‹", Action.PAGE_PREV, width = 2.5f),
            Key("${page + 1} / $FORMAT_PAGES", Action.SPACE, width = 2.5f),
            Key("›", Action.PAGE_NEXT, width = 2.5f),
            Key("⌫", Action.DELETE, width = 2.5f),
        )
        return if (page == 0) listOf(
            // Listen oben in voller Breite, dann die Unicode-Stile, darunter zentriert der Rest
            listOf(Key("•  Aufzählung", Action.FORMAT, text = "bullet", width = 5f),
                Key("1.  Nummeriert", Action.FORMAT, text = "number", width = 5f)),
            listOf(Key("𝗙𝗲𝘁𝘁", Action.FORMAT, text = "bold", width = 2.5f),
                Key("𝘒𝘶𝘳𝘴𝘪𝘷", Action.FORMAT, text = "italic", width = 2.5f),
                Key("D̶u̶r̶c̶h̶", Action.FORMAT, text = "strike", width = 2.5f),
                Key("U̲n̲t̲e̲r̲", Action.FORMAT, text = "underline", width = 2.5f)),
            // Spoiler (Telegram/Discord) und alles wieder normal machen
            listOf(Key("||Spoiler||", Action.FORMAT, text = "md_spoiler", width = 3.5f),
                Key("Aa  Normal", Action.FORMAT, text = "plain", width = 3.5f)),
            bottom,
        ) else listOf(
            listOf(Key("sPoNgEbOb", Action.FORMAT, text = "sarcasm", width = 5f),
                Key("ᵏˡᵉⁱⁿ", Action.FORMAT, text = "small", width = 5f)),
            listOf(Key("G̷l̶i̷t̵c̶h̷", Action.FORMAT, text = "glitch", width = 3.4f),
                Key("↕  Kopfüber", Action.FORMAT, text = "upside", width = 3.3f),
                Key("⇄  Spiegel", Action.FORMAT, text = "mirror", width = 3.3f)),
            listOf(Key("01  Binär", Action.FORMAT, text = "binary", width = 3.5f)),
            bottom,
        )
    }

    /** Untere Reihe der Zwischenablage-Ansicht; darüber liegt die Liste. */
    fun clipboard(): List<List<Key>> = listOf(
        emptyList(), emptyList(), emptyList(),
        listOf(
            Key("Leeren", Action.CLIP_CLEAR, width = 7f),
            Key("⌫", Action.DELETE, width = 3f),
        ),
    )

    fun emoticons(page: Int, list: List<String>): List<List<Key>> {
        val items = list.drop(page * EMOTICONS_PER_PAGE).take(EMOTICONS_PER_PAGE)
        val rows = items.chunked(4).map { row -> row.map { Key(it, width = 2.5f) } }.toMutableList()
        while (rows.size < 3) rows.add(emptyList())
        rows.add(
            listOf(
                Key("‹", Action.PAGE_PREV, width = 2f),
                Key(" ", Action.SPACE, width = 4f),
                Key("›", Action.PAGE_NEXT, width = 2f),
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
