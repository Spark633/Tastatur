package de.spark633.tastatur

import android.content.Context
import org.json.JSONObject

/**
 * Lernt aus deinem Schreiben, welches Wort auf welches folgt ("ich bin" → "gleich").
 * Bleibt nur auf dem Handy. "^" steht für den Satzanfang.
 */
class NextWordModel private constructor(context: Context) {

    private val sp = context.getSharedPreferences("vorhersage", Context.MODE_PRIVATE)

    /** Schlüssel "sprache|vorheriges Wort" → (nächstes Wort → Anzahl). */
    private val pairs = LinkedHashMap<String, HashMap<String, Int>>(256, 0.75f, true)
    private var unsaved = 0

    init {
        load()
    }

    fun record(lang: Language, previous: String, word: String) {
        if (word.isBlank() || word.length > 40) return
        val key = "${lang.code}|${previous.lowercase()}"
        val next = pairs.getOrPut(key) { HashMap() }
        next[word] = (next[word] ?: 0) + 1
        if (next.size > MAX_NEXT) {
            // Seltenste Folgewörter vergessen
            next.entries.sortedBy { it.value }.take(next.size - MAX_NEXT).forEach { next.remove(it.key) }
        }
        if (pairs.size > MAX_KEYS) pairs.remove(pairs.keys.first()) // am längsten unbenutzt
        if (++unsaved >= 15) save()
    }

    /** Die wahrscheinlichsten nächsten Wörter, bestes zuerst. */
    fun predict(lang: Language, previous: String, n: Int = 3): List<String> {
        val next = pairs["${lang.code}|${previous.lowercase()}"] ?: return emptyList()
        return next.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    fun clear() {
        pairs.clear()
        sp.edit().clear().apply()
        unsaved = 0
    }

    fun save() {
        if (unsaved == 0) return
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, JSONObject(v as Map<*, *>))
        sp.edit().putString("paare", o.toString()).apply()
        unsaved = 0
    }

    /** Für die Sicherung. */
    fun export(): JSONObject {
        save()
        return JSONObject(sp.getString("paare", "{}") ?: "{}")
    }

    /** Aus einer Sicherung übernehmen (ersetzt das Gelernte). */
    fun import(o: JSONObject) {
        pairs.clear()
        sp.edit().putString("paare", o.toString()).apply()
        load()
        unsaved = 0
    }

    private fun load() {
        try {
            val o = JSONObject(sp.getString("paare", "{}") ?: "{}")
            for (k in o.keys()) {
                val inner = o.getJSONObject(k)
                val m = HashMap<String, Int>()
                for (w in inner.keys()) m[w] = inner.getInt(w)
                pairs[k] = m
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val MAX_KEYS = 4000
        private const val MAX_NEXT = 12

        @Volatile
        private var instance: NextWordModel? = null

        fun get(context: Context): NextWordModel =
            instance ?: synchronized(this) {
                instance ?: NextWordModel(context.applicationContext).also { instance = it }
            }
    }
}
