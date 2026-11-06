package de.spark633.tastatur

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/** Gespeicherte Einstellungs-Profile (z. B. "Dunkel mit Pony", "Hell schlicht"). */
class Profiles(private val context: Context) {

    private val sp = context.getSharedPreferences("profile", Context.MODE_PRIVATE)

    fun names(): List<String> = sp.all.keys.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.sorted()

    /** Aktuelle Einstellungen (inkl. Hintergrundbild aus dem Verlauf) unter einem Namen speichern. */
    fun save(name: String) {
        sp.edit().putString(PREFIX + name, Prefs(context).snapshot().toString()).apply()
    }

    fun load(name: String): Boolean {
        val raw = sp.getString(PREFIX + name, null) ?: return false
        return try {
            Prefs(context).restore(JSONObject(raw))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun delete(name: String) = sp.edit().remove(PREFIX + name).apply()

    /** Hintergrundbilder, die ein Profil benutzt (werden beim Aufräumen nicht gelöscht). */
    fun usedBackgrounds(): Set<String> = sp.all.values.mapNotNull { v ->
        try {
            JSONObject(v as String).optJSONObject("bg_file")?.optString("v")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }.toSet()

    companion object {
        private const val PREFIX = "p_"
    }
}

/** Sicherung als Datei: Einstellungen, Profile, eigene Wörter, gelernte Wortfolgen, Emoticons und gewählte Hintergrundbilder. */
object Backup {

    /** Gewählte Bilder (Dateinamen) samt ihren Werten in die Sicherung legen. */
    private fun putImages(context: Context, o: JSONObject, images: List<String>) {
        Prefs(context).rememberImageSettings()
        val arr = org.json.JSONArray()
        val prefs = Prefs(context)
        for (name in images) {
            val f = Background.file(context, name)
            if (!f.exists()) continue
            val e = JSONObject().put("name", name).put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            prefs.imageSettingsRaw(name)?.let { e.put("werte", it) }
            arr.put(e)
        }
        o.put("bilder", arr)
        o.put("aktiv", prefs.bgFile.takeIf { it in images }.orEmpty())
    }

    /**
     * Bilder aus einer Sicherung übernehmen. Gibt alte → neue Namen zurück
     * (ältere Sicherungen mit nur einem "bild" werden auch gelesen).
     */
    private fun readImages(context: Context, o: JSONObject): Pair<Map<String, String>, String?> {
        val map = LinkedHashMap<String, String>()
        val prefs = Prefs(context)
        o.optJSONArray("bilder")?.let { arr ->
            // Älteste zuerst, damit das aktive/neueste am Ende vorne im Verlauf steht
            for (i in arr.length() - 1 downTo 0) {
                val e = arr.getJSONObject(i)
                val old = e.getString("name")
                val name = Background.importNamed(context, old, Base64.decode(e.getString("data"), Base64.NO_WRAP))
                e.optString("werte").takeIf { it.isNotEmpty() }?.let { prefs.setImageSettingsRaw(name, it) }
                map[old] = name
            }
            val active = o.optString("aktiv").takeIf { it.isNotEmpty() }?.let { map[it] }
            return map to active
        }
        if (o.has("bild")) {
            val name = Background.importBytes(context, Base64.decode(o.getString("bild"), Base64.NO_WRAP))
            return map to name
        }
        return map to null
    }

    fun create(context: Context, images: List<String> = listOfNotNull(Prefs(context).bgFile.takeIf { it.isNotEmpty() })): String {
        val o = JSONObject()
        o.put("format", "tastatur-sicherung")
        o.put("version", 1)
        o.put("erstellt", stamp())
        o.put("einstellungen", Prefs(context).snapshot())
        o.put("eigene_woerter", UserDictionary.get(context).export())
        o.put("wortfolgen", NextWordModel.get(context).export())
        val profiles = JSONObject()
        val p = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
        for ((k, v) in p.all) if (v is String) profiles.put(k, v)
        o.put("profile", profiles)
        putImages(context, o, images)
        return o.toString()
    }

    /** Datum der Sicherung (steht zusätzlich in der Datei). */
    private fun stamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMANY).format(java.util.Date())

    /** Einstellungen, die zum Aussehen der Tastatur gehören (Design-Sicherung). */
    private val DESIGN_KEYS = listOf(
        "theme", "key_color", "key_text_color", "key_opacity", "corner_radius", "text_scale", "show_hints",
        "bg_dim", "bg_zoom", "bg_position", "bg_position_x", "row_height", "bottom_padding",
    )

    /** Nur das Design: Farben, Tasten, Größe und das aktuelle Hintergrundbild. */
    fun createDesign(context: Context, images: List<String> = listOfNotNull(Prefs(context).bgFile.takeIf { it.isNotEmpty() })): String {
        val all = Prefs(context).snapshot()
        val design = JSONObject()
        for (k in DESIGN_KEYS) all.optJSONObject(k)?.let { design.put(k, it) }
        val o = JSONObject()
        o.put("format", "tastatur-design")
        o.put("version", 1)
        o.put("erstellt", stamp())
        o.put("design", design)
        putImages(context, o, images)
        return o.toString()
    }

    /** Design übernehmen; alle anderen Einstellungen bleiben, wie sie sind. true = übernommen. */
    fun restoreDesign(context: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        require(o.optString("format") == "tastatur-design")
        val design = o.getJSONObject("design")
        val prefs = Prefs(context)
        val (_, active) = readImages(context, o)
        if (active != null) Background.select(context, active) else Background.remove(context)
        prefs.applyValues(design) // nach dem Bild, damit die Werte aus dem Design gelten
        prefs.rememberImageSettings()
        prefs.bgVersion = if (active != null) System.currentTimeMillis() else 0L
        Background.trimHistory(context)
        true
    } catch (_: Exception) {
        false
    }

    /** true = Sicherung übernommen. */
    fun restore(context: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        require(o.optString("format") == "tastatur-sicherung")
        val settings = o.getJSONObject("einstellungen")
        // Bilder aus der Sicherung in den Verlauf legen und auf das aktive zeigen
        val (renamed, active) = readImages(context, o)
        if (active != null) {
            settings.put("bg_file", JSONObject().put("t", "s").put("v", active))
        } else {
            settings.remove("bg_file")
        }
        // Profile auf umbenannte Bilder umstellen
        if (renamed.any { it.key != it.value }) o.optJSONObject("profile")?.let { prof ->
            for (k in prof.keys()) {
                val p = JSONObject(prof.getString(k))
                val bg = p.optJSONObject("bg_file")?.optString("v")
                if (bg != null && renamed[bg] != null && renamed[bg] != bg) {
                    p.put("bg_file", JSONObject().put("t", "s").put("v", renamed[bg]))
                    prof.put(k, p.toString())
                }
            }
        }
        o.optJSONObject("eigene_woerter")?.let { UserDictionary.get(context).import(it) }
        o.optJSONObject("wortfolgen")?.let { NextWordModel.get(context).import(it) }
        o.optJSONObject("profile")?.let { prof ->
            val ed = context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            for (k in prof.keys()) ed.putString(k, prof.getString(k))
            ed.apply()
        }
        Prefs(context).restore(settings)
        Background.trimHistory(context)
        true
    } catch (_: Exception) {
        false
    }
}
