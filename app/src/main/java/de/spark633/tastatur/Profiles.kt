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

/** Sicherung als Datei: Einstellungen, Profile, eigene Wörter, gelernte Wortfolgen, Emoticons und Hintergrundbild. */
object Backup {

    fun create(context: Context): String {
        val o = JSONObject()
        o.put("format", "tastatur-sicherung")
        o.put("version", 1)
        o.put("einstellungen", Prefs(context).snapshot())
        o.put("eigene_woerter", UserDictionary.get(context).export())
        o.put("wortfolgen", NextWordModel.get(context).export())
        val profiles = JSONObject()
        val p = context.getSharedPreferences("profile", Context.MODE_PRIVATE)
        for ((k, v) in p.all) if (v is String) profiles.put(k, v)
        o.put("profile", profiles)
        Background.current(context)?.let {
            o.put("bild", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
        }
        return o.toString()
    }

    /** Einstellungen, die zum Aussehen der Tastatur gehören (Design-Sicherung). */
    private val DESIGN_KEYS = listOf(
        "theme", "key_color", "key_text_color", "key_opacity", "corner_radius", "text_scale", "show_hints",
        "bg_dim", "bg_zoom", "bg_position", "bg_position_x", "row_height", "bottom_padding",
    )

    /** Nur das Design: Farben, Tasten, Größe und das aktuelle Hintergrundbild. */
    fun createDesign(context: Context): String {
        val all = Prefs(context).snapshot()
        val design = JSONObject()
        for (k in DESIGN_KEYS) all.optJSONObject(k)?.let { design.put(k, it) }
        val o = JSONObject()
        o.put("format", "tastatur-design")
        o.put("version", 1)
        o.put("design", design)
        Background.current(context)?.let {
            o.put("bild", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
        }
        return o.toString()
    }

    /** Design übernehmen; alle anderen Einstellungen bleiben, wie sie sind. true = übernommen. */
    fun restoreDesign(context: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        require(o.optString("format") == "tastatur-design")
        val design = o.getJSONObject("design")
        val prefs = Prefs(context)
        if (o.has("bild")) {
            val name = Background.importBytes(context, Base64.decode(o.getString("bild"), Base64.NO_WRAP))
            Background.select(context, name)
        } else {
            Background.remove(context)
        }
        prefs.applyValues(design) // nach dem Bild, damit seine Werte gelten
        prefs.rememberImageSettings()
        prefs.bgVersion = System.currentTimeMillis().takeIf { o.has("bild") } ?: 0L
        true
    } catch (_: Exception) {
        false
    }

    /** true = Sicherung übernommen. */
    fun restore(context: Context, json: String): Boolean = try {
        val o = JSONObject(json)
        require(o.optString("format") == "tastatur-sicherung")
        val settings = o.getJSONObject("einstellungen")
        // Bild aus der Sicherung in den Verlauf legen und im Profil darauf zeigen
        if (o.has("bild")) {
            val name = Background.importBytes(context, Base64.decode(o.getString("bild"), Base64.NO_WRAP))
            settings.put("bg_file", JSONObject().put("t", "s").put("v", name))
        } else {
            settings.remove("bg_file")
        }
        o.optJSONObject("eigene_woerter")?.let { UserDictionary.get(context).import(it) }
        o.optJSONObject("wortfolgen")?.let { NextWordModel.get(context).import(it) }
        o.optJSONObject("profile")?.let { prof ->
            val ed = context.getSharedPreferences("profile", Context.MODE_PRIVATE).edit()
            for (k in prof.keys()) ed.putString(k, prof.getString(k))
            ed.apply()
        }
        Prefs(context).restore(settings)
        true
    } catch (_: Exception) {
        false
    }
}
