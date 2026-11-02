package de.spark633.tastatur

import android.content.Context

/** Alle Einstellungen der Tastatur. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tastatur", Context.MODE_PRIVATE)

    fun isLanguageEnabled(lang: Language) = sp.getBoolean("lang_${lang.code}", true)
    fun setLanguageEnabled(lang: Language, on: Boolean) = sp.edit().putBoolean("lang_${lang.code}", on).apply()

    /** Mindestens eine Sprache ist immer aktiv. */
    val enabledLanguages: List<Language>
        get() = Language.entries.filter { isLanguageEnabled(it) }.ifEmpty { listOf(Language.DE) }

    fun isQwertz(lang: Language) = sp.getBoolean("qwertz_${lang.code}", true)
    fun setQwertz(lang: Language, on: Boolean) = sp.edit().putBoolean("qwertz_${lang.code}", on).apply()

    var currentLanguage: Language
        get() {
            val code = sp.getString("current_lang", null)
            val enabled = enabledLanguages
            return enabled.firstOrNull { it.code == code } ?: enabled.first()
        }
        set(value) = sp.edit().putString("current_lang", value.code).apply()

    var clipboardRight: Boolean
        get() = sp.getBoolean("clipboard_right", false)
        set(v) = sp.edit().putBoolean("clipboard_right", v).apply()

    var rowHeightDp: Int
        get() = sp.getInt("row_height", 54)
        set(v) = sp.edit().putInt("row_height", v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean("double_space", true)
        set(v) = sp.edit().putBoolean("double_space", v).apply()

    var autoCaps: Boolean
        get() = sp.getBoolean("auto_caps", true)
        set(v) = sp.edit().putBoolean("auto_caps", v).apply()

    var suggestions: Boolean
        get() = sp.getBoolean("suggestions", true)
        set(v) = sp.edit().putBoolean("suggestions", v).apply()

    var autocorrect: Boolean
        get() = sp.getBoolean("autocorrect", true)
        set(v) = sp.edit().putBoolean("autocorrect", v).apply()

    /** ⌫ direkt nach einer Korrektur stellt das getippte Wort wieder her. */
    var undoCorrection: Boolean
        get() = sp.getBoolean("undo_correction", true)
        set(v) = sp.edit().putBoolean("undo_correction", v).apply()

    /** Zwischenablage: Spalten (1 oder 2) und Zeilen pro Eintrag (1 bis 4). */
    var clipColumns: Int
        get() = sp.getInt("clip_columns", 2)
        set(v) = sp.edit().putInt("clip_columns", v).apply()

    var clipLines: Int
        get() = sp.getInt("clip_lines", 3)
        set(v) = sp.edit().putInt("clip_lines", v).apply()

    /** Nach . , ! ? : ; automatisch ein Leerzeichen setzen. */
    var autoSpacePunct: Boolean
        get() = sp.getBoolean("auto_space_punct", true)
        set(v) = sp.edit().putBoolean("auto_space_punct", v).apply()

    /** Enter übernimmt den fetten Vorschlag wie das Leerzeichen. */
    var correctOnEnter: Boolean
        get() = sp.getBoolean("correct_on_enter", true)
        set(v) = sp.edit().putBoolean("correct_on_enter", v).apply()

    /** Unbekannte Wörter, die du öfter tippst, automatisch lernen. */
    var learnWords: Boolean
        get() = sp.getBoolean("learn_words", true)
        set(v) = sp.edit().putBoolean("learn_words", v).apply()

    /** Suchmaschine für die Websuche aus der Tastatur. */
    var searchEngine: String
        get() = sp.getString("search_engine", "google") ?: "google"
        set(v) = sp.edit().putString("search_engine", v).apply()

    /** Eigene Emoticon-Liste (Reihenfolge = Reihenfolge in der Tastatur). */
    var emoticons: List<String>
        get() {
            val raw = sp.getString("emoticons", null) ?: return Layouts.EMOTICONS
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) {
                Layouts.EMOTICONS
            }
        }
        set(v) = sp.edit().putString("emoticons", org.json.JSONArray(v).toString()).apply()

    fun resetEmoticons() = sp.edit().remove("emoticons").apply()

    // --- Aussehen ---
    var theme: String
        get() = sp.getString("theme", "dunkel") ?: "dunkel"
        set(v) = sp.edit().putString("theme", v).apply()

    /** Deckkraft der Tasten in Prozent. */
    var keyOpacity: Int
        get() = sp.getInt("key_opacity", 25)
        set(v) = sp.edit().putInt("key_opacity", v).apply()

    var cornerRadiusDp: Int
        get() = sp.getInt("corner_radius", 6)
        set(v) = sp.edit().putInt("corner_radius", v).apply()

    /** Schriftgröße in Prozent. */
    var textScale: Int
        get() = sp.getInt("text_scale", 100)
        set(v) = sp.edit().putInt("text_scale", v).apply()

    /** Kleine Zeichen über/unter den Buchstaben anzeigen. */
    var showHints: Boolean
        get() = sp.getBoolean("show_hints", true)
        set(v) = sp.edit().putBoolean("show_hints", v).apply()

    /** Hintergrundbild abdunkeln in Prozent. */
    var bgDim: Int
        get() = sp.getInt("bg_dim", 30)
        set(v) = sp.edit().putInt("bg_dim", v).apply()

    /** Sichtbarer Ausschnitt des Bildes: 0 = oben, 100 = unten. */
    var bgPosition: Int
        get() = sp.getInt("bg_position", 50)
        set(v) = sp.edit().putInt("bg_position", v).apply()

    /** Ändert sich bei jedem neuen Bild (0 = kein Bild). */
    var bgVersion: Long
        get() = sp.getLong("bg_version", 0L)
        set(v) = sp.edit().putLong("bg_version", v).apply()

    /** Zusätzlicher Abstand unter der Tastatur (für die Systemknöpfe). */
    var bottomPaddingDp: Int
        get() = sp.getInt("bottom_padding", 0)
        set(v) = sp.edit().putInt("bottom_padding", v).apply()

    /** Die Einstellungs-App bittet die Tastatur, den Größen-Modus zu öffnen. */
    var resizeRequest: Boolean
        get() = sp.getBoolean(KEY_RESIZE_REQUEST, false)
        set(v) = sp.edit().putBoolean(KEY_RESIZE_REQUEST, v).apply()

    fun registerListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val KEY_RESIZE_REQUEST = "resize_request"
    }
}
