package de.spark633.tastatur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Eigene Wörter (gelernt oder von Hand hinzugefügt) und gesperrte Vorschläge.
 * Gilt für alle Sprachen. Eine gemeinsame Instanz für Tastatur und Einstellungen.
 */
class UserDictionary private constructor(context: Context) {

    private class Word(var text: String, var count: Int, var manual: Boolean)

    private val sp = context.getSharedPreferences("eigene_woerter", Context.MODE_PRIVATE)
    private val words = LinkedHashMap<String, Word>() // Schlüssel: kleingeschrieben
    private val blocked = LinkedHashSet<String>() // kleingeschrieben

    init {
        load()
    }

    /** Wörter, die als bekannt gelten (von Hand hinzugefügt oder oft genug getippt). */
    fun knownWords(): List<String> =
        words.values.filter { it.manual || it.count >= LEARN_AFTER }.map { it.text }.sortedBy { it.lowercase() }

    fun isKnown(word: String): Boolean {
        val w = words[word.lowercase()] ?: return false
        return w.manual || w.count >= LEARN_AFTER
    }

    /** Ein unbekanntes Wort wurde getippt und nicht korrigiert. */
    /**
     * Ein unbekanntes Wort wurde benutzt. Die Schreibweise wird sinnvoll gewählt:
     * am Satzanfang nur wegen der Großschreibung groß → klein; deutsche Nomen-Endungen
     * → groß; bewusst groß mitten im Satz (Namen), USA, iPhone → wie getippt.
     */
    fun noteTyped(word: String, atSentenceStart: Boolean = false) {
        val key = word.lowercase()
        val w = words[key]
        val deliberate = deliberateCase(word, atSentenceStart)
        if (w == null) {
            words[key] = Word(deliberate ?: autoCase(word), 1, false)
        } else {
            w.count++
            // Klein getippt ändert eine bewusste Schreibweise nicht
            if (!w.manual && deliberate != null) w.text = deliberate
        }
        save()
    }

    /** Bewusst gewählte Schreibweise, oder null wenn sie nichts aussagt. */
    private fun deliberateCase(word: String, atSentenceStart: Boolean): String? = when {
        word.length > 1 && word == word.uppercase() && word != word.lowercase() -> word // USA
        word.drop(1).any { it.isUpperCase() } -> word // iPhone
        word[0].isUpperCase() && !atSentenceStart -> word // Name mitten im Satz
        else -> null
    }

    /**
     * Korrektur abgelehnt: zählt wie einmal benutzt (nicht sofort gelernt,
     * damit Tippfehler nicht aus Versehen ins Wörterbuch kommen).
     */
    fun learnNow(word: String, atSentenceStart: Boolean = false) {
        blocked.remove(word.lowercase())
        noteTyped(word, atSentenceStart)
    }

    fun add(word: String) {
        val t = word.trim()
        if (t.isEmpty()) return
        words[t.lowercase()] = Word(t, LEARN_AFTER, true)
        blocked.remove(t.lowercase())
        save()
    }

    fun remove(word: String) {
        words.remove(word.lowercase())
        save()
    }

    fun rename(old: String, new: String) {
        remove(old)
        add(new)
    }

    fun isBlocked(word: String) = blocked.contains(word.lowercase())

    fun blockedWords(): List<String> = blocked.sorted()

    /** Vorschlag nicht mehr anzeigen. Eigene Wörter werden dabei gelöscht. */
    fun block(word: String) {
        val key = word.lowercase()
        words.remove(key)
        blocked.add(key)
        save()
    }

    fun unblock(word: String) {
        blocked.remove(word.lowercase())
        save()
    }

    /** Für die Sicherung: eigene Wörter und gesperrte Vorschläge. */
    fun export(): JSONObject {
        val arr = JSONArray()
        for (w in words.values) arr.put(JSONObject().put("t", w.text).put("c", w.count).put("m", w.manual))
        return JSONObject().put("woerter", arr).put("gesperrt", JSONArray(blocked.toList()))
    }

    /** Aus einer Sicherung übernehmen (ersetzt die aktuelle Liste). */
    fun import(o: JSONObject) {
        words.clear()
        blocked.clear()
        val arr = o.optJSONArray("woerter") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val t = w.getString("t")
            words[t.lowercase()] = Word(t, w.optInt("c"), w.optBoolean("m"))
        }
        val b = o.optJSONArray("gesperrt") ?: JSONArray()
        for (i in 0 until b.length()) blocked.add(b.getString(i))
        save()
    }

    private fun load() {
        try {
            val arr = JSONArray(sp.getString("woerter", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getString("t")
                words[t.lowercase()] = Word(t, o.optInt("c"), o.optBoolean("m"))
            }
            val b = JSONArray(sp.getString("gesperrt", "[]"))
            for (i in 0 until b.length()) blocked.add(b.getString(i))
        } catch (_: Exception) {
        }
        // Einmalig: bisher gelernte Wörter in sinnvolle Schreibweise bringen
        if (!sp.getBoolean("schreibweise_v2", false)) {
            for (w in words.values) {
                if (w.manual || w.text.drop(1).any { it.isUpperCase() }) continue
                w.text = autoCase(w.text)
            }
            sp.edit().putBoolean("schreibweise_v2", true).apply()
            save()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (w in words.values) arr.put(JSONObject().put("t", w.text).put("c", w.count).put("m", w.manual))
        val b = JSONArray()
        blocked.forEach { b.put(it) }
        sp.edit().putString("woerter", arr.toString()).putString("gesperrt", b.toString()).apply()
    }

    companion object {
        private val NOUN_ENDINGS = listOf("ung", "heit", "keit", "schaft", "chen", "lein", "tion", "ität", "ismus", "nis", "tum")

        /** Klein, außer bei typischen deutschen Nomen-Endungen (Vereinbarung, Freundschaft …). */
        fun autoCase(word: String): String {
            val lower = word.lowercase()
            return if (lower.length >= 5 && NOUN_ENDINGS.any { lower.endsWith(it) })
                lower.replaceFirstChar { it.uppercaseChar() } else lower
        }

        /** So oft muss ein unbekanntes Wort getippt werden, bis es gelernt ist. */
        const val LEARN_AFTER = 5

        @Volatile
        private var instance: UserDictionary? = null

        fun get(context: Context): UserDictionary =
            instance ?: synchronized(this) {
                instance ?: UserDictionary(context.applicationContext).also { instance = it }
            }
    }
}
