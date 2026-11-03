package de.spark633.tastatur

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Verlauf der kopierten Texte und Bilder. Bleibt nur auf dem Handy gespeichert.
 * Angeheftete Einträge bleiben dauerhaft, die übrigen werden auf
 * [MAX_UNPINNED] Texte und [MAX_IMAGES] Bilder begrenzt.
 */
class ClipboardHistory private constructor(context: Context) {

    /** Ein Eintrag: Text, oder ein Bild ([image] = Dateiname im Bilder-Ordner). */
    class Entry(val text: String, var pinned: Boolean, var time: Long, val image: String? = null)

    private val sp = context.getSharedPreferences("zwischenablage", Context.MODE_PRIVATE)
    private val imageDir = imageDir(context)
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
        val existing = items.firstOrNull { it.image == null && it.text == text }
        if (existing != null) {
            existing.time = System.currentTimeMillis()
        } else {
            items.add(Entry(text, false, System.currentTimeMillis()))
        }
        trim()
        save()
    }

    /** Ein kopiertes Bild (bereits im Bilder-Ordner gespeichert) aufnehmen. */
    fun addImage(name: String) {
        items.add(Entry("", false, System.currentTimeMillis(), name))
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
        e.image?.let { File(imageDir, it).delete() }
        save()
    }

    /** Löscht alle Bilder außer den angehefteten. */
    fun clearUnpinnedImages() {
        val gone = items.filter { !it.pinned && it.image != null }
        items.removeAll(gone.toSet())
        gone.forEach { e -> e.image?.let { File(imageDir, it).delete() } }
        save()
    }

    /** Löscht alles außer den angehefteten Einträgen. */
    fun clearUnpinned() {
        val gone = items.filter { !it.pinned }
        items.removeAll(gone.toSet())
        gone.forEach { e -> e.image?.let { File(imageDir, it).delete() } }
        save()
    }

    private fun trim() {
        val texts = items.filter { !it.pinned && it.image == null }.sortedByDescending { it.time }
        val images = items.filter { !it.pinned && it.image != null }.sortedByDescending { it.time }
        val gone = texts.drop(MAX_UNPINNED) + images.drop(MAX_IMAGES)
        items.removeAll(gone.toSet())
        gone.forEach { e -> e.image?.let { File(imageDir, it).delete() } }
    }

    private fun load() {
        items.clear()
        try {
            val arr = JSONArray(sp.getString("eintraege", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val image = o.optString("i").ifEmpty { null }
                if (image != null && !File(imageDir, image).exists()) continue
                items.add(Entry(o.getString("t"), o.optBoolean("p"), o.optLong("z"), image))
            }
        } catch (_: Exception) {
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (e in items) {
            val o = JSONObject().put("t", e.text).put("p", e.pinned).put("z", e.time)
            e.image?.let { o.put("i", it) }
            arr.put(o)
        }
        sp.edit().putString("eintraege", arr.toString()).apply()
    }

    companion object {
        @Volatile
        private var instance: ClipboardHistory? = null

        fun get(context: Context): ClipboardHistory =
            instance ?: synchronized(this) {
                instance ?: ClipboardHistory(context.applicationContext).also { instance = it }
            }

        /** Ordner für kopierte Bilder. */
        fun imageDir(context: Context) = File(context.filesDir, "ablage_bilder").apply { mkdirs() }

        const val MAX_UNPINNED = 30
        const val MAX_IMAGES = 10
        const val MAX_LENGTH = 5000
    }
}
