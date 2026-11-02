package de.spark633.tastatur

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Einrichtung und Einstellungen. Die Oberfläche wird direkt im Code aufgebaut. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = "Tastatur – Einstellungen"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p * 3)
        }

        // --- Einrichtung ---
        root.addView(header("Einrichtung"))
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, (8 * dp).toInt()) }
        root.addView(status)
        root.addView(button("1. Tastatur in den Android-Einstellungen aktivieren") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("2. Tastatur auswählen") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        })
        root.addView(EditText(this).apply { hint = "Hier ausprobieren …"; minLines = 2 })

        // --- Sprachen ---
        root.addView(header("Sprachen"))
        root.addView(info("Zum Wechseln auf der Leertaste nach links oder rechts wischen."))
        for (lang in Language.entries) {
            root.addView(CheckBox(this).apply {
                text = lang.displayName
                isChecked = prefs.isLanguageEnabled(lang)
                setOnCheckedChangeListener { box, checked ->
                    if (!checked && prefs.enabledLanguages.size <= 1) {
                        box.isChecked = true // mindestens eine Sprache
                    } else {
                        prefs.setLanguageEnabled(lang, checked)
                    }
                }
            })
            root.addView(switch("${lang.displayName}: QWERTY statt QWERTZ", !prefs.isQwertz(lang)) {
                prefs.setQwertz(lang, !it)
            }.apply { setPadding((32 * dp).toInt(), 0, 0, 0) })
        }

        // --- Leiste ---
        root.addView(header("Zwischenablage-Knopf"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val left = RadioButton(this).apply { text = "Links"; id = View.generateViewId() }
        val right = RadioButton(this).apply { text = "Rechts"; id = View.generateViewId() }
        group.addView(left)
        group.addView(right)
        group.check(if (prefs.clipboardRight) right.id else left.id)
        group.setOnCheckedChangeListener { _, checkedId -> prefs.clipboardRight = checkedId == right.id }
        root.addView(group)

        // --- Tippen ---
        root.addView(header("Tippen"))
        root.addView(switch("Vibration beim Tippen", prefs.vibrate) { prefs.vibrate = it })
        root.addView(switch("Automatisch großschreiben am Satzanfang", prefs.autoCaps) { prefs.autoCaps = it })
        root.addView(switch("Doppeltes Leerzeichen setzt einen Punkt", prefs.doubleSpacePeriod) {
            prefs.doubleSpacePeriod = it
        })

        val heightLabel = TextView(this).apply { textSize = 15f }
        fun updateHeightLabel() { heightLabel.text = "Tastenhöhe: ${prefs.rowHeightDp} dp" }
        updateHeightLabel()
        root.addView(heightLabel)
        root.addView(SeekBar(this).apply {
            max = 40 // 40 … 80 dp
            progress = prefs.rowHeightDp - 40
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                    prefs.rowHeightDp = 40 + value
                    updateHeightLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })

        // --- Hilfe ---
        root.addView(header("Bedienung"))
        root.addView(info(
            "• Nach oben wischen: kleines Zeichen über dem Buchstaben (z. B. q → 1)\n" +
            "• Untere Reihe nach unten wischen: Zeichen unter dem Buchstaben (z. B. x → €)\n" +
            "• Lange drücken: Umlaute und Akzente (a → ä, s → ß)\n" +
            "• Leertaste seitlich wischen: Sprache wechseln\n" +
            "• Shift zweimal schnell: Feststelltaste\n" +
            "• Zwei Leerzeichen: Punkt am Satzende"
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        status.text = when {
            selected -> "✅ Die Tastatur ist aktiv und ausgewählt."
            enabled -> "⚠️ Aktiviert, aber noch nicht ausgewählt → Schritt 2."
            else -> "❌ Noch nicht aktiviert → Schritt 1."
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
    }

    private fun info(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        alpha = 0.8f
        setPadding(0, 0, 0, (8 * dp).toInt())
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    @Suppress("DEPRECATION")
    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        textSize = 15f
        isChecked = checked
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }
}
