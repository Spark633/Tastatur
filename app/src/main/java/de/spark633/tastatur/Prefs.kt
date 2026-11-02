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
