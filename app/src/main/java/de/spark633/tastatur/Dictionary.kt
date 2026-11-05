package de.spark633.tastatur

import android.content.Context
import kotlin.math.abs
import kotlin.math.min

/**
 * Wörterbuch einer Sprache mit Häufigkeiten. Liefert Vorschläge
 * (Vervollständigung) und Korrekturen (Tippfehler).
 */
class Dictionary private constructor(
    private val words: Array<String>, // nach Häufigkeit sortiert
    private val freqs: IntArray,
) {
    private val lower = Array(words.size) { words[it].lowercase() }
    private val exact = HashMap<String, Int>(words.size * 2)
    private val sortedIdx: IntArray
    private val buckets: Map<Char, IntArray>

    init {
        for (i in words.indices) exact.putIfAbsent(words[i], i)
        sortedIdx = words.indices.sortedWith { a, b -> lower[a].compareTo(lower[b]) }.toIntArray()
        // Für die Fehlersuche nur die häufigeren Wörter, gruppiert nach erstem Buchstaben
        val limit = min(words.size, FUZZY_LIMIT)
        buckets = (0 until limit).groupBy { base(lower[it][0]) }.mapValues { it.value.toIntArray() }
    }

    /** Steht das Wort (in passender Schreibweise) im Wörterbuch? */
    fun contains(typed: String): Boolean {
        if (exact.containsKey(typed)) return true
        val l = typed.lowercase()
        // "Und" am Satzanfang oder "UND" in Großbuchstaben
        if (typed != l && exact.containsKey(l)) return true
        // "USA", "EU": Großbuchstaben-Wort, dessen gemischte Schreibweise bekannt ist
        if (typed.length > 1 && typed == typed.uppercase()) {
            val cap = l.replaceFirstChar { it.uppercaseChar() }
            if (exact.containsKey(cap)) return true
        }
        return false
    }

    class Suggestions(
        val typed: String,
        /** Ersatz beim Leerzeichen, oder null wenn das Wort bleiben soll. */
        val correction: String?,
        /** Anzeige: links, Mitte (fett), rechts. Leere Einträge = frei. */
        val slots: List<String>,
    )

    /**
     * @param extra   eigene Wörter (zählen als bekannt und werden bevorzugt vorgeschlagen)
     * @param blocked gesperrte Vorschläge, kleingeschrieben
     */
    fun suggest(
        typed: String,
        neighbors: Map<Char, Set<Char>>,
        known: (String) -> Boolean,
        extra: List<String> = emptyList(),
        blocked: Set<String> = emptySet(),
    ): Suggestions {
        val ok = { w: String -> !blocked.contains(w.lowercase()) }
        // Für die Seiten nur gängige Wörter, eigene Wörter immer; keine Wortreste wie "ich-ich"
        val ownLower = extra.map { it.lowercase() }.toHashSet()
        val side = { w: String ->
            ok(w) && !w.equals(typed, ignoreCase = true) && (('-' !in w) || ('-' in typed)) &&
                (ownLower.contains(w.lowercase()) || freqOf(w) >= SIDE_MIN_FREQ)
        }
        // "ware" ist ein Wort, aber "wäre" ist viel häufiger: Umlaut einsetzen
        if (!known(typed)) {
            umlautVariant(typed)?.takeIf(ok)?.let { v ->
                return Suggestions(typed, v, listOf("\"$typed\"", v, ""))
            }
        }
        val valid = contains(typed) || known(typed)
        val completions = completions(typed, 3, extra).filter(ok)
        if (valid) {
            // Links die beste Vervollständigung, rechts das ähnlichste andere Wort (Tippfehler-Alternative)
            // Nicht viel seltener als das getippte Wort (sonst "ich" → "ick", "mir" → "Miranda")
            val minFreq = freqOf(typed) - 50
            val common = { w: String -> ownLower.contains(w.lowercase()) || freqOf(w) >= minFreq }
            val comp = completions.filter(side).filter(common)
            val near = fuzzy(typed, neighbors, 3, extra).map { it.first }.filter(side).filter(common)
            val left = comp.firstOrNull() ?: near.getOrNull(1)
            val right = near.firstOrNull { it != left } ?: comp.firstOrNull { it != left }
            return Suggestions(typed, null, listOf(left ?: "", typed, right ?: ""))
        }
        // Zusammengesetztes Nomen ("Wortfolgen" = Wort + Folgen): richtig, nicht korrigieren
        if (isCompound(typed)) {
            val cap = typed.replaceFirstChar { it.uppercaseChar() }
            val fixed = if (typed[0].isLowerCase()) cap else null
            val others = completions.filter(side)
            return if (fixed != null) Suggestions(typed, fixed, listOf("\"$typed\"", fixed, others.getOrElse(0) { "" }))
            else Suggestions(typed, null, listOf(others.getOrElse(0) { "" }, typed, others.getOrElse(1) { "" }))
        }
        // Nur Groß-/Kleinschreibung falsch ("zeile" → "Zeile"): das hat Vorrang vor ähnlichen Wörtern
        dictForm(typed)?.let { form ->
            val fixed = applyCase(form, typed)
            if (fixed != typed && ok(fixed)) {
                val others = completions.filter { !it.equals(typed, ignoreCase = true) }
                return Suggestions(typed, fixed, listOf("\"$typed\"", fixed, others.getOrElse(0) { "" }))
            }
        }
        val corrections = fuzzy(typed, neighbors, 4, extra).filter { ok(it.first) }
        // Leerzeichen vergessen? "machdas" → "mach das"
        val split = splitWords(typed)?.takeIf(ok)
        // Angefangenes Wort ("Verab" → Verabredung): nur bei sehr wahrscheinlichem
        // Tippfehler korrigieren, sonst die Vervollständigungen zeigen.
        val bestCost = corrections.firstOrNull()?.second ?: Float.MAX_VALUE
        // Gibt es Wörter, die so anfangen, wird nur bei eindeutigen Fehlern (Umlaut, Groß/klein) korrigiert
        // (seltene Vervollständigungen zählen nicht: "hane" → habe)
        var best = if (completions.any(side) && bestCost >= 0.5f) null else corrections.firstOrNull()?.first
        // Getrennte Wörter schlagen eine unsichere Korrektur
        if (split != null && (best == null || bestCost >= 1f)) best = split
        if (best != null) {
            val rest = (listOfNotNull(split) + corrections.map { it.first } + completions)
                .filter { it != best && side(it) }.distinct()
            return Suggestions(typed, best, listOf("\"$typed\"", best, rest.getOrElse(0) { "" }))
        }
        // Unbekannt und nichts Ähnliches: Wort bleibt, Vervollständigungen daneben
        val others = completions.filter(side)
        return Suggestions(typed, null, listOf(others.getOrElse(0) { "" }, typed, others.getOrElse(1) { "" }))
    }

    /**
     * Zwei zusammengeklebte Wörter erkennen ("machdas", "ichbin"). Beide Teile müssen
     * bekannt und recht häufig sein, sonst lieber nichts vorschlagen.
     */
    private fun splitWords(typed: String): String? {
        if (typed.length < 5) return null
        var best: String? = null
        var bestScore = SPLIT_MIN_FREQ - 1
        for (i in 2..typed.length - 2) {
            val a = typed.substring(0, i)
            val b = typed.substring(i)
            if (!contains(a)) continue
            val bForm = dictForm(b) ?: continue
            val score = minOf(freqOf(a), freqOf(bForm))
            if (score > bestScore) {
                bestScore = score
                best = "$a $bForm"
            }
        }
        return best
    }

    /** Ist das ein Nomen im Wörterbuch (großgeschriebene Form vorhanden)? */
    private fun isNoun(w: String): Boolean {
        if (w.length < 3) return false
        val cap = w.lowercase().replaceFirstChar { it.uppercaseChar() }
        return exact[cap]?.let { freqs[it] >= COMPOUND_MIN_FREQ } == true
    }

    /** Nomen oder seine Mehrzahl (Folgen, Hunde, Kinder, Autos), auch wenn nur die Einzahl bekannt ist. */
    private fun isNounOrPlural(w: String): Boolean =
        isNoun(w) || listOf("n", "en", "e", "er", "s").any { e -> w.endsWith(e) && isNoun(w.dropLast(e.length)) }

    /**
     * Deutsches zusammengesetztes Nomen aus zwei bekannten Nomen, auch mit Fugen-s:
     * Wortfolgen (Wort + Folgen), Haustür, Arbeitsplatz (Arbeit + s + Platz).
     */
    private fun isCompound(typed: String): Boolean {
        if (typed.length < 7 || typed.any { !it.isLetter() }) return false
        for (i in 3..typed.length - 3) {
            val a = typed.substring(0, i)
            val b = typed.substring(i)
            if (!isNounOrPlural(b)) continue
            if (isNoun(a)) return true
            if (a.endsWith("s") && isNoun(a.dropLast(1))) return true
        }
        return false
    }

    /** Schreibweise eines Wortes im Wörterbuch ("hund" → "Hund"), oder null. */
    private fun dictForm(w: String): String? {
        if (exact.containsKey(w)) return w
        val lower = w.lowercase()
        if (exact.containsKey(lower)) return lower
        val cap = lower.replaceFirstChar { it.uppercaseChar() }
        return if (exact.containsKey(cap)) cap else null
    }

    private fun freqOf(word: String): Int {
        val i = exact[word] ?: exact[word.lowercase()] ?: return -1
        return freqs[i]
    }

    /**
     * Umlaut vergessen? Ersetzt bis zu zwei a/o/u durch ä/ö/ü. Ergebnis nur, wenn
     * die Variante deutlich häufiger ist als das Getippte ("horen" → "hören").
     * Bei schon/schön oder gedruckt/gedrückt bleibt das Wort stehen.
     */
    private fun umlautVariant(typed: String): String? {
        if (typed.any { it in "äöüÄÖÜ" }) return null
        val positions = typed.indices.filter { typed[it].lowercaseChar() in "aou" }.take(6)
        if (positions.isEmpty()) return null
        val own = if (contains(typed)) freqOf(typed) else -1
        var best: String? = null
        var bestFreq = own + UMLAUT_MARGIN
        val combos = positions.map { listOf(it) } +
            positions.flatMapIndexed { i, a -> positions.drop(i + 1).map { b -> listOf(a, b) } }
        for (combo in combos) {
            val chars = typed.toCharArray()
            for (p in combo) chars[p] = UMLAUT[chars[p]] ?: chars[p]
            val v = String(chars)
            if (!contains(v)) continue
            val f = freqOf(v)
            if (f >= bestFreq) {
                best = v
                bestFreq = f
            }
        }
        return best
    }

    /** Wörter, die mit dem Getippten beginnen: eigene zuerst, dann die häufigsten. */
    private fun completions(typed: String, n: Int, extra: List<String>): List<String> {
        val p = typed.lowercase()
        val own = extra.filter { it.length > p.length && it.lowercase().startsWith(p) }
            .map { applyCase(it, typed) }
        var lo = 0
        var hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[sortedIdx[mid]] < p) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>()
        var i = lo
        while (i < sortedIdx.size && lower[sortedIdx[i]].startsWith(p)) {
            val idx = sortedIdx[i]
            if (lower[idx].length > p.length) found.add(idx)
            i++
        }
        // Index = Häufigkeitsrang, kleiner ist häufiger; Reserve für gesperrte
        return (own + found.sorted().take(n + 5).map { applyCase(words[it], typed) }).distinct()
    }

    /** Ähnliche Wörter (Tippfehler), bestes zuerst. */
    private fun fuzzy(typed: String, neighbors: Map<Char, Set<Char>>, n: Int, extra: List<String>): List<Pair<String, Float>> {
        val t = typed.lowercase()
        val maxCost = maxCost(t.length)
        val first = base(t[0])
        val starts = LinkedHashSet<Char>().apply {
            add(first)
            neighbors[t[0]]?.forEach { add(base(it)) }
        }
        val scored = ArrayList<Triple<String, Float, Float>>() // Wort, Kosten, Punkte
        for (b in starts) {
            val bucket = buckets[b] ?: continue
            for (idx in bucket) {
                val w = lower[idx]
                if (abs(w.length - t.length) > maxCost) continue
                val cost = distance(t, w, maxCost, neighbors)
                if (cost <= maxCost) scored.add(Triple(words[idx], cost, freqs[idx] - cost * COST_WEIGHT))
            }
        }
        // Eigene Wörter zählen wie sehr häufige Wörter
        for (e in extra) {
            val w = e.lowercase()
            if (abs(w.length - t.length) > maxCost) continue
            val cost = distance(t, w, maxCost, neighbors)
            if (cost <= maxCost) scored.add(Triple(e, cost, OWN_WORD_FREQ - cost * COST_WEIGHT))
        }
        return scored.sortedByDescending { it.third }.distinctBy { it.first }.take(n + 5)
            .map { applyCase(it.first, typed) to it.second }
    }

    private fun maxCost(len: Int) = when {
        len <= 2 -> 0.25f // nur Groß-/Kleinschreibung und Umlaute
        len <= 4 -> 1.0f
        len <= 7 -> 1.5f
        else -> 2.0f
    }

    /**
     * Gewichtete Editierdistanz: Nachbartaste 0,5, Umlaut 0,2,
     * vertauschte Buchstaben 0,6, doppelter Buchstabe 0,5, sonst 1.
     */
    private fun distance(a: String, b: String, max: Float, nb: Map<Char, Set<Char>>): Float {
        val n = a.length
        val m = b.length
        var prev2 = FloatArray(m + 1)
        var prev = FloatArray(m + 1) { it.toFloat() }
        var cur = FloatArray(m + 1)
        for (i in 1..n) {
            cur[0] = i.toFloat()
            var rowMin = cur[0]
            for (j in 1..m) {
                val ca = a[i - 1]
                val cb = b[j - 1]
                val sub = prev[j - 1] + subCost(ca, cb, nb)
                // Zeichen zu viel getippt (doppelt gedrückt ist günstiger)
                val ins = prev[j] + if (i > 1 && a[i - 2] == ca) 0.5f else 1f
                val del = cur[j - 1] + 1f
                var v = min(sub, min(ins, del))
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = min(v, prev2[j - 2] + 0.6f)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return Float.MAX_VALUE
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    private fun subCost(a: Char, b: Char, nb: Map<Char, Set<Char>>): Float = when {
        a == b -> 0f
        base(a) == base(b) -> 0.2f
        nb[a]?.contains(b) == true -> 0.5f
        else -> 1f
    }

    companion object {
        private const val FUZZY_LIMIT = 100_000
        // Ähnlichkeit zählt mehr als Häufigkeit (toppen → tippen statt Typen)
        private const val COST_WEIGHT = 80f
        // Seitenvorschläge nur mit gängigen Wörtern (keine seltenen Namen wie Miranda, Nurmi)
        private const val SIDE_MIN_FREQ = 100
        private const val OWN_WORD_FREQ = 200f
        private const val UMLAUT_MARGIN = 40
        private const val SPLIT_MIN_FREQ = 110
        private const val COMPOUND_MIN_FREQ = 80
        private val UMLAUT = mapOf('a' to 'ä', 'o' to 'ö', 'u' to 'ü', 'A' to 'Ä', 'O' to 'Ö', 'U' to 'Ü')

        fun load(context: Context, lang: Language): Dictionary =
            context.assets.open("dict/${lang.code}.txt").bufferedReader().useLines { fromLines(it) }

        /** Zeilen im Format "Wort<Tab>Häufigkeit", häufigste zuerst. */
        fun fromLines(lines: Sequence<String>): Dictionary {
            val words = ArrayList<String>(150_000)
            val freqs = ArrayList<Int>(150_000)
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                words.add(line.substring(0, tab))
                freqs.add(line.substring(tab + 1).toIntOrNull() ?: 0)
            }
            return Dictionary(words.toTypedArray(), freqs.toIntArray())
        }

        /** Deutsche Abkürzungen, die mit Punkten geschrieben werden. */
        val DOT_ABBREVIATIONS_DE = mapOf(
            "zb" to "z.B.",
            "oä" to "o.Ä.",
            "uä" to "u.Ä.",
            "idr" to "i.d.R.",
            "dh" to "d.h.",
            "ua" to "u.a.",
            "uu" to "u.U.",
            "zt" to "z.T.",
            "va" to "v.a.",
            "uvm" to "u.v.m.",
            "mmn" to "m.M.n.",
            "bzw" to "bzw.",
            "ggf" to "ggf.",
            "usw" to "usw.",
            "evtl" to "evtl.",
            "bzgl" to "bzgl.",
            "ca" to "ca.",
            "inkl" to "inkl.",
            "zzgl" to "zzgl.",
            "nr" to "Nr.",
            "etc" to "etc.",
        )

        /** Buchstabe ohne Akzent: ä → a, é → e, ß → s. */
        fun base(c: Char): Char = when (c) {
            'ä', 'á', 'à', 'â', 'å' -> 'a'
            'ö', 'ó', 'ò', 'ô', 'ø' -> 'o'
            'ü', 'ú', 'ù', 'û' -> 'u'
            'é', 'è', 'ê', 'ë' -> 'e'
            'í', 'ì', 'î', 'ï' -> 'i'
            'ß' -> 's'
            'ç' -> 'c'
            'ñ' -> 'n'
            'ÿ', 'ý' -> 'y'
            else -> c
        }

        /** Schreibweise an das Getippte anpassen (Satzanfang, Großbuchstaben). */
        fun applyCase(word: String, typed: String): String = when {
            typed.length > 1 && typed == typed.uppercase() && typed != typed.lowercase() -> word.uppercase()
            typed.isNotEmpty() && typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }
    }
}
