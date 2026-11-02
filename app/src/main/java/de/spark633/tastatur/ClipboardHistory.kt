package de.spark633.tastatur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verlauf der kopierten Texte. Bleibt nur auf dem Handy gespeichert.
 * Angeheftete Einträge bleiben dauerhaft, die übrigen werden auf
 * [MAX_UNPINNED] begrenzt.
 */
class ClipboardHistory private constructor(context: Context) {

    class Entry(val text: String, var pinned: Boolean, var time: Long)

    private val sp = context.getSharedPreferences("zwischenablage", Context.MODE_PRIVATE)
    private val items = ArrayList<Entry>()

    init {
        load()
    }

    /** Angeheftete zuerst, danach die neuesten. */
    val entries: List<Entry>
        get() = items.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.time })

    fun add(raw: String) {
        val text = raw.take(MAX_LENGTH)
        if (text.isBlank()) return
        val existing = items.firstOrNull { it.text == text }
        if (existing != null) {
            existing.time = System.currentTimeMillis()
        } else {
            items.add(Entry(text, false, System.currentTimeMillis()))
        }
        trim()
        save()
    }

    fun togglePin(e: Entry) {
        e.pinned = !e.pinned
        trim()
        save()
    }

    fun remove(e: Entry) {
        items.remove(e)
        save()
    }

    /** Löscht alles außer den angehefteten Einträgen. */
    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        save()
    }

    private fun trim() {
        val unpinned = items.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size > MAX_UNPINNED) items.removeAll(unpinned.drop(MAX_UNPINNED).toSet())
    }

    private fun load() {
        items.clear()
        try {
            val arr = JSONArray(sp.getString("eintraege", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Entry(o.getString("t"), o.optBoolean("p"), o.optLong("z")))
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (e in items) arr.put(JSONObject().put("t", e.text).put("p", e.pinned).put("z", e.time))
        sp.edit().putString("eintraege", arr.toString()).apply()
    }

    companion object {
        @Volatile
        private var instance: ClipboardHistory? = null

        fun get(context: Context): ClipboardHistory =
            instance ?: synchronized(this) {
                instance ?: ClipboardHistory(context.applicationContext).also { instance = it }
            }

        const val MAX_UNPINNED = 30
        const val MAX_LENGTH = 5000
    }
}
