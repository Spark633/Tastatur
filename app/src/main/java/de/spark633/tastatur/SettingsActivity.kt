package de.spark633.tastatur

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
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
import android.widget.Switch
import android.widget.TextView

/**
 * Einstellungen, aufgeteilt in Bereiche. Ohne Bereich erscheint die Startseite
 * mit Einrichtung und Menü. Die Oberfläche wird direkt im Code aufgebaut.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var userDict: UserDictionary
    private var status: TextView? = null
    private var testField: EditText? = null
    private val dp by lazy { resources.displayMetrics.density }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        userDict = UserDictionary.get(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p * 3)
        }
        val section = intent.getStringExtra(EXTRA_SECTION)
        actionBar?.setDisplayHomeAsUpEnabled(section != null && section != "main") // ← zurück zu den Haupteinstellungen
        when (section) {
            "general" -> buildGeneral()
            "languages" -> buildLanguages()
            "dictionary" -> buildDictionary()
            "clipboard" -> buildClipboard()
            "help" -> buildHelp()
            "design" -> buildDesign()
            "emoticons" -> buildEmoticons()
            "profiles" -> buildProfiles()
            else -> buildMain()
        }
        val scroll = ScrollView(this).apply { addView(root) }
        val pv = preview
        if (pv == null) {
            setContentView(scroll)
        } else {
            // Aussehen: Vorschau bleibt oben stehen, Einstellungen scrollen darunter
            val p = (12 * dp).toInt()
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(p, p, p, p / 2) })
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            })
            prefs.registerListener(previewListener)
        }
    }

    override fun onDestroy() {
        if (preview != null) prefs.unregisterListener(previewListener)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (isTaskRoot) {
                // Aus der Tastatur geöffnet: Startseite darunter legen, dann zurück dorthin
                startActivity(Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
            }
            finish() // echtes Zurück (mit Zurück-Animation)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    private fun open(section: String) {
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, section))
    }

    // ------------------------------------------------------------------
    // Startseite
    // ------------------------------------------------------------------

    private fun buildMain() {
        title = "Tastatur"
        // Einrichtung: eingeklappt, Antippen klappt auf
        setupHeader = header("▸ Einrichtung").apply {
            setOnClickListener {
                setupExpanded = !setupExpanded
                updateStatus()
            }
        }
        root.addView(setupHeader)
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, (8 * dp).toInt()) }
        setupBox?.addView(status)
        setupBox?.addView(button("1. Tastatur in den Android-Einstellungen aktivieren") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        setupBox?.addView(button("2. Tastatur auswählen") {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        })
        root.addView(setupBox)
        addTestField()

        root.addView(header("Einstellungen"))
        root.addView(button("⚙  Allgemein – Tippen, Größe, Leiste") { open("general") })
        root.addView(button("🌍  Sprachen und Layout") { open("languages") })
        root.addView(button("📖  Wörterbuch – Vorschläge, eigene Wörter") { open("dictionary") })
        root.addView(button("📋  Zwischenablage") { open("clipboard") })
        root.addView(button("🎨  Aussehen – Farben, Hintergrundbild") { open("design") })
        root.addView(button("^.^  Emoticons bearbeiten") { open("emoticons") })
        root.addView(button("💾  Profile & Sicherung") { open("profiles") })
        root.addView(button("❓  Hilfe – Bedienung") { open("help") })

        // Version und Download der neuesten Version
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { null }
        root.addView(info("Version ${version ?: "unbekannt"}").apply { setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt()) })
        // Unterstrichener Text-Link statt großem Knopf
        root.addView(TextView(this).apply {
            text = android.text.SpannableString("(Neueste Version herunterladen)").apply {
                setSpan(android.text.style.UnderlineSpan(), 0, length, 0)
            }
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(0x4D, 0xB6, 0xAC))
            setPadding(0, 0, 0, (16 * dp).toInt())
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(AppLinks.DOWNLOAD)))
                } catch (_: Exception) {
                }
            }
        })
    }

    private var setupHeader: TextView? = null
    private var setupBox: LinearLayout? = null
    private var setupExpanded = false
    private var setupAutoOpened = false

    private fun updateStatus() {
        val st = status ?: return
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        st.text = when {
            selected -> "✅ Die Tastatur ist aktiv und ausgewählt."
            enabled -> "⚠️ Aktiviert, aber noch nicht ausgewählt → Schritt 2."
            else -> "❌ Noch nicht aktiviert → Schritt 1."
        }
        // Noch nicht fertig eingerichtet: einmal von selbst aufklappen
        if (!selected && !setupAutoOpened) {
            setupExpanded = true
            setupAutoOpened = true
        }
        val icon = when {
            selected -> "✅"
            enabled -> "⚠️"
            else -> "❌"
        }
        setupHeader?.text = (if (setupExpanded) "▾" else "▸") + " Einrichtung  $icon"
        setupBox?.visibility = if (setupExpanded) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Allgemein
    // ------------------------------------------------------------------

    private fun buildGeneral() {
        title = "Allgemein"
        addTestField()
        root.addView(header("Tippen"))
        root.addView(switch("Vibration beim Tippen", prefs.vibrate) { prefs.vibrate = it })
        root.addView(switch("Automatisch großschreiben am Satzanfang", prefs.autoCaps) { prefs.autoCaps = it })
        root.addView(switch("Leerzeichen nach . , ! ? automatisch", prefs.autoSpacePunct) { prefs.autoSpacePunct = it })
        root.addView(switch("Buchstabe beim Drücken groß anzeigen", prefs.keyPreview) { prefs.keyPreview = it })
        root.addView(switch("Zweimal ins selbe Wort tippen springt ans Wortende", prefs.tapWordEnd) { prefs.tapWordEnd = it })
        root.addView(switch("Leertaste gedrückt halten und ziehen bewegt den Cursor", prefs.spaceCursor) {
            prefs.spaceCursor = it
        })
        root.addView(switch("Doppeltes Leerzeichen setzt einen Punkt", prefs.doubleSpacePeriod) {
            prefs.doubleSpacePeriod = it
        })
        root.addView(switch("⌫ doppelt tippen und halten: löscht bis zum Leerzeichen", prefs.chunkDelete) { prefs.chunkDelete = it })
        root.addView(info("Tempo beim Löschen (⌫ halten)"))
        val speeds = listOf(280, 420, 560) // schnell, normal, langsam
        val current = speeds.indexOfFirst { it >= prefs.deleteRepeatMs }.let { if (it < 0) 2 else it }
        root.addView(radio(listOf("Schnell", "Normal", "Langsam"), current) { prefs.deleteRepeatMs = speeds[it] })

        root.addView(header("Websuche"))
        root.addView(info(
            "⚙ → 🔍 Suche öffnet den Standard-Browser. Ist Text markiert, wird direkt danach gesucht."
        ))
        val engines = TastaturService.SEARCH_ENGINES.keys.toList()
        val names = listOf("Google", "DuckDuckGo", "Ecosia", "Bing", "Startpage")
        root.addView(dropdown(names, engines.indexOf(prefs.searchEngine).coerceAtLeast(0)) {
            prefs.searchEngine = engines[it]
        })

        root.addView(header("Leiste"))
        root.addView(info("Seite des Zwischenablage-Knopfs. Das Zahnrad sitzt gegenüber."))
        root.addView(radio(listOf("Links", "Rechts"), if (prefs.clipboardRight) 1 else 0) {
            prefs.clipboardRight = it == 1
        })
    }

    // ------------------------------------------------------------------
    // Sprachen
    // ------------------------------------------------------------------

    private fun buildLanguages() {
        title = "Sprachen"
        addTestField()
        root.addView(header("Sprachen"))
        root.addView(info("Zum Wechseln auf der Leertaste nach links oder rechts wischen."))
        for (lang in Language.entries) {
            root.addView(CheckBox(this).apply {
                text = lang.displayName
                textSize = 16f
                isChecked = prefs.isLanguageEnabled(lang)
                setOnCheckedChangeListener { box, checked ->
                    if (!checked && prefs.enabledLanguages.size <= 1) {
                        box.isChecked = true // mindestens eine Sprache
                    } else {
                        prefs.setLanguageEnabled(lang, checked)
                    }
                }
            })
            root.addView(switch("QWERTY statt QWERTZ", !prefs.isQwertz(lang)) {
                prefs.setQwertz(lang, !it)
            }.apply { setPadding((32 * dp).toInt(), 0, 0, (8 * dp).toInt()) })
        }
        root.addView(header("Sprachkürzel"))
        root.addView(info("Neben den Vorschlägen anzeigen bei:"))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (lang in Language.entries) {
                addView(CheckBox(this@SettingsActivity).apply {
                    text = lang.code.uppercase()
                    textSize = 16f
                    isChecked = prefs.isLangCodeShown(lang)
                    setOnCheckedChangeListener { _, checked -> prefs.setLangCodeShown(lang, checked) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        root.addView(info("Seite:"))
        root.addView(radio(listOf("Links", "Rechts"), if (prefs.langLeft) 0 else 1) {
            prefs.langLeft = it == 0
        })
    }

    // ------------------------------------------------------------------
    // Wörterbuch
    // ------------------------------------------------------------------

    private lateinit var wordList: LinearLayout
    private lateinit var blockedList: LinearLayout

    private fun buildDictionary() {
        title = "Wörterbuch"
        addTestField()
        root.addView(header("Vorschläge"))
        root.addView(info(
            "Die Mitte der Leiste (fett) wird beim Leerzeichen eingesetzt. Links steht bei einer Korrektur " +
                "dein Wort in Anführungszeichen. Antippen behält es. Richtig geschriebene Wörter werden nie geändert."
        ))
        root.addView(switch("Wortvorschläge anzeigen", prefs.suggestions) { prefs.suggestions = it })
        root.addView(switch("Tippfehler beim Leerzeichen korrigieren", prefs.autocorrect) { prefs.autocorrect = it })
        root.addView(switch("Enter übernimmt den Vorschlag auch", prefs.correctOnEnter) { prefs.correctOnEnter = it })
        root.addView(switch("⌫ direkt danach macht die Korrektur rückgängig", prefs.undoCorrection) {
            prefs.undoCorrection = it
        })
        root.addView(switch("Neue Wörter automatisch lernen", prefs.learnWords) { prefs.learnWords = it })
        root.addView(switch("Nächstes Wort vorschlagen", prefs.predictNext) {
            prefs.predictNext = it
        })
        root.addView(info(
            "Nach dem Leerzeichen schlägt die Tastatur vor, was du an dieser Stelle oft als Nächstes schreibst " +
                "(z. B. nach „Guten“ → „Morgen“). Das lernt sie aus deinem Schreiben."
        ))
        root.addView(button("Gelernte Vorhersagen löschen") {
            AlertDialog.Builder(this)
                .setMessage("Alles vergessen, welche Wörter du oft nacheinander schreibst? Eigene Wörter bleiben.")
                .setPositiveButton("Vergessen") { _, _ -> NextWordModel.get(this).clear() }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
        root.addView(info("Löscht nur, was die Tastatur über deine Wortfolgen gelernt hat. Eigene Wörter bleiben erhalten."))

        root.addView(header("Eigene Wörter"))
        root.addView(info(
            "Gelten in allen Sprachen und werden nie korrigiert. Gelernt wird ein Wort, wenn du es " +
                "${UserDictionary.LEARN_AFTER}× benutzt hast (eine abgelehnte Korrektur zählt einmal). " +
                "Von Hand hinzugefügte Wörter gelten sofort."
        ))
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Neues Wort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(input)
        addRow.addView(Button(this).apply {
            text = "Hinzufügen"
            isAllCaps = false
            setOnClickListener {
                val w = input.text.toString().trim()
                if (w.isNotEmpty()) {
                    userDict.add(w)
                    input.setText("")
                    renderWords()
                }
            }
        })
        root.addView(addRow)
        wordList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(wordList)

        root.addView(header("Entfernte Vorschläge"))
        root.addView(info("Lange auf einen Vorschlag in der Leiste drücken, um ihn nicht mehr anzuzeigen."))
        blockedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(blockedList)
        renderWords()

        root.addView(header("E-Mail-Adressen"))
        root.addView(switch("In E-Mail-Feldern getippte Adressen merken und vorschlagen", prefs.rememberEmails) {
            prefs.rememberEmails = it
        })
        root.addView(info(
            "Gemerkt wird eine Adresse, wenn du sie in ein E-Mail-Feld tippst (beim Verlassen des Feldes oder ↵). " +
                "Lange auf eine vorgeschlagene Adresse drücken, um sie zu vergessen."
        ))
        val mailRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val mailInput = EditText(this).apply {
            hint = "Neue Adresse"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mailRow.addView(mailInput)
        mailRow.addView(Button(this).apply {
            text = "Hinzufügen"
            isAllCaps = false
            setOnClickListener {
                val e = mailInput.text.toString().trim()
                if (!looksLikeEmail(e)) {
                    android.widget.Toast.makeText(this@SettingsActivity, "Das sieht nicht nach einer E-Mail-Adresse aus", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    prefs.noteEmail(e)
                    mailInput.setText("")
                    renderEmails()
                }
            }
        })
        root.addView(mailRow)
        emailList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(emailList)
        renderEmails()
    }

    private lateinit var emailList: LinearLayout

    private fun looksLikeEmail(e: String) = e.length <= 100 && Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}").matches(e)

    private fun editEmail(old: String) {
        val field = EditText(this).apply {
            setText(old)
            setSelection(old.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this)
            .setTitle("Adresse bearbeiten")
            .setView(field)
            .setPositiveButton("Speichern") { _, _ ->
                val new = field.text.toString().trim()
                if (new != old && looksLikeEmail(new)) prefs.renameEmail(old, new)
                else if (new != old) android.widget.Toast.makeText(this, "Das sieht nicht nach einer E-Mail-Adresse aus", android.widget.Toast.LENGTH_SHORT).show()
                renderEmails()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun renderEmails() {
        emailList.removeAllViews()
        val mails = prefs.savedEmails
        if (mails.isEmpty()) emailList.addView(info("Noch keine Adressen gemerkt."))
        for (e in mails) {
            emailList.addView(listRow(e, "✎" to { editEmail(e) }, "✕" to {
                confirmDelete("„$e“ vergessen?") {
                    prefs.forgetEmail(e)
                    renderEmails()
                }
            }))
        }
    }

    private fun renderWords() {
        wordList.removeAllViews()
        val words = userDict.knownWords()
        if (words.isEmpty()) wordList.addView(info("Noch keine eigenen Wörter."))
        for (w in words) {
            wordList.addView(listRow(w, "✎" to { editWord(w) }, "✕" to {
                confirmDelete("„$w“ löschen?") {
                    userDict.remove(w)
                    renderWords()
                }
            }))
        }
        blockedList.removeAllViews()
        val blocked = userDict.blockedWords()
        if (blocked.isEmpty()) blockedList.addView(info("Keine."))
        for (w in blocked) {
            blockedList.addView(listRow(w, "Wieder zeigen" to {
                userDict.unblock(w)
                renderWords()
            }))
        }
    }

    private fun editWord(old: String) {
        val field = EditText(this).apply {
            setText(old)
            setSelection(old.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Wort bearbeiten")
            .setView(field)
            .setPositiveButton("Speichern") { _, _ ->
                val new = field.text.toString().trim()
                if (new.isNotEmpty() && new != old) userDict.rename(old, new)
                renderWords()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /** Rückfrage vor jedem Löschen. */
    private fun confirmDelete(message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Löschen") { _, _ -> onYes() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun listRow(text: String, vararg actions: Pair<String, () -> Unit>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                this.text = text
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Emoticons (▲ ▼ ✎ ✕): schmale Knöpfe, ▲▼ eng, ✎ und ✕ mit gleichem Abstand
            val compact = actions.any { it.first == "▲" }
            for ((label, action) in actions) {
                val btn = Button(this@SettingsActivity, null, android.R.attr.borderlessButtonStyle).apply {
                    this.text = label
                    isAllCaps = false
                    if (compact) {
                        minWidth = 0
                        minimumWidth = 0
                        val pad = (8 * dp).toInt()
                        setPadding(pad, paddingTop, pad, paddingBottom)
                    }
                    setOnClickListener { action() }
                }
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (compact) lp.marginStart = when (label) {
                    "▼" -> (8 * dp).toInt()   // ▲ ▼ etwas auseinander (1–2 mm)
                    "✎", "✕" -> (22 * dp).toInt() // gleicher Abstand vor ✎ und vor ✕
                    else -> 0
                }
                addView(btn, lp)
            }
        }

    // ------------------------------------------------------------------
    // Zwischenablage
    // ------------------------------------------------------------------

    private fun buildClipboard() {
        title = "Zwischenablage"
        addTestField()
        root.addView(info(
            "Die Tastatur merkt sich die letzten ${ClipboardHistory.MAX_UNPINNED} kopierten Texte und " +
                "${ClipboardHistory.MAX_IMAGES} Bilder. Bilder werden wie beim normalen Einfügen eingesetzt. " +
                "Antippen fügt ein. ⋮ oben rechts: anheften oder löschen. " +
                "Angeheftete Einträge bleiben dauerhaft. Alles bleibt nur auf dem Handy."
        ))
        root.addView(switch("Kopierte Bilder speichern", prefs.clipImages) {
            prefs.clipImages = it
            // Ausschalten: vorhandene Bilder entfernen, angeheftete bleiben
            if (!it) ClipboardHistory.get(this).clearUnpinnedImages()
        })
        root.addView(info("Aus: Nur Texte kommen in den Verlauf, vorhandene Bilder werden gelöscht (angeheftete bleiben)."))
        root.addView(header("Ansicht"))
        root.addView(info("Spalten"))
        root.addView(radio(listOf("1 Spalte (breit)", "2 Spalten"), prefs.clipColumns.coerceIn(1, 2) - 1) {
            prefs.clipColumns = it + 1
        })
        root.addView(info("Zeilen pro Eintrag"))
        root.addView(radio(listOf("1", "2", "3", "4"), prefs.clipLines.coerceIn(1, 4) - 1) {
            prefs.clipLines = it + 1
        })
        root.addView(header("Knopf"))
        root.addView(radio(listOf("Links", "Rechts"), if (prefs.clipboardRight) 1 else 0) {
            prefs.clipboardRight = it == 1
        })
        root.addView(header("Verlauf"))
        root.addView(button("Verlauf leeren (angeheftete bleiben)") {
            AlertDialog.Builder(this)
                .setMessage("Alle nicht angehefteten Einträge löschen?")
                .setPositiveButton("Leeren") { _, _ -> ClipboardHistory.get(this).clearUnpinned() }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
    }

    // ------------------------------------------------------------------
    // Aussehen
    // ------------------------------------------------------------------

    private var bgStatus: TextView? = null
    private var preview: KeyboardPreview? = null
    private var positionBar: android.widget.SeekBar? = null
    private var positionXBar: android.widget.SeekBar? = null
    private var zoomBar: android.widget.SeekBar? = null
    private var dimBar: android.widget.SeekBar? = null
    private var opacityBar: android.widget.SeekBar? = null
    private var keyColorList: android.widget.Spinner? = null
    private var textColorList: android.widget.Spinner? = null

    /** Vorschau und Ausschnitt-Regler bei jeder Änderung aktualisieren. */
    private val previewListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        preview?.refresh()
        if (key == "bg_position") positionBar?.progress = prefs.bgPosition
        if (key == "bg_position_x") positionXBar?.progress = prefs.bgPositionX
        if (key == "bg_zoom") zoomBar?.progress = prefs.bgZoom - 100
        if (key == "bg_dim") dimBar?.progress = prefs.bgDim
        if (key == "key_opacity") opacityBar?.progress = prefs.keyOpacity
        val colorValues = Theme.COLOR_CHOICES.map { it.second }
        if (key == "key_color") keyColorList?.let {
            val i = colorValues.indexOf(prefs.keyColor).coerceAtLeast(0)
            if (it.selectedItemPosition != i) it.setSelection(i)
        }
        if (key == "key_text_color") textColorList?.let {
            val i = colorValues.indexOf(prefs.keyTextColor).coerceAtLeast(0)
            if (it.selectedItemPosition != i) it.setSelection(i)
        }
    }

    private fun buildDesign() {
        title = "Aussehen"
        preview = KeyboardPreview(this, prefs).also { it.locked = prefs.lockImage }
        root.addView(info("Die Vorschau oben zeigt jede Änderung sofort. Bild darin ziehen = verschieben, mit zwei Fingern = zoomen."))

        root.addView(header("Farbschema"))
        val themes = Theme.ALL
        root.addView(dropdown(themes.map { it.name }, themes.indexOfFirst { it.id == prefs.theme }.coerceAtLeast(0)) {
            prefs.theme = themes[it].id
        })

        root.addView(header("Größe"))
        root.addView(info(
            "Öffnet die Tastatur im Größen-Modus: nach oben/unten ziehen ändert die Tastenhöhe, " +
                "„Abstand“ verschiebt die Tasten über die Systemknöpfe. Mit „Fertig“ speichern. " +
                "Geht auch direkt über das Zahnrad in der Tastatur."
        ))
        root.addView(button("Größe anpassen") {
            prefs.resizeRequest = true
            testField?.let {
                it.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(it, 0)
            }
        })

        root.addView(header("Hintergrundbild"))
        bgStatus = info("")
        root.addView(bgStatus)
        updateBgStatus()
        root.addView(button("Neues Bild aus der Galerie wählen") { pickImage() })
        root.addView(button("Hintergrund ausschalten") {
            Background.remove(this)
            updateBgStatus()
        })
        root.addView(info("Frühere Bilder: antippen = verwenden, lange drücken = löschen. Jedes Bild merkt sich Abdunkeln, Zoom, Ausschnitt sowie Tastenfarbe, Schriftfarbe und Deckkraft."))
        gallery = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(gallery) })
        renderGallery()
        root.addView(switch("🔒 Bild-Regler sperren (auch Verschieben/Zoomen in der Vorschau)", prefs.lockImage) {
            prefs.lockImage = it
            applySliderLock()
        })
        sliderGroup = GROUP_IMAGE
        root.addView(slider("Bild abdunkeln", 0, 90, prefs.bgDim, "%") { prefs.bgDim = it }
            .also { dimBar = it.getChildAt(1) as android.widget.SeekBar })
        root.addView(slider("Zoom", 0, 200, prefs.bgZoom - 100, "%") { prefs.bgZoom = it + 100 }
            .also { zoomBar = it.getChildAt(1) as android.widget.SeekBar })
        root.addView(slider("Ausschnitt senkrecht (0 = oben, 100 = unten)", 0, 100, prefs.bgPosition, "") { prefs.bgPosition = it }
            .also { positionBar = it.getChildAt(1) as android.widget.SeekBar })
        root.addView(slider("Ausschnitt waagerecht (0 = links, 100 = rechts)", 0, 100, prefs.bgPositionX, "") {
            prefs.bgPositionX = it
        }.also { positionXBar = it.getChildAt(1) as android.widget.SeekBar })

        root.addView(header("Tasten"))
        root.addView(switch("🔒 Tasten-Regler sperren", prefs.lockKeys) {
            prefs.lockKeys = it
            applySliderLock()
        })
        sliderGroup = GROUP_KEYS
        val colorNames = Theme.COLOR_CHOICES.map { it.first }
        val colorValues = Theme.COLOR_CHOICES.map { it.second }
        root.addView(info("Tastenfarbe"))
        root.addView(colorDropdown(colorNames, colorValues, colorValues.indexOf(prefs.keyColor).coerceAtLeast(0)) {
            prefs.keyColor = colorValues[it]
        }.also { keyColorList = it })
        root.addView(info("Schriftfarbe"))
        root.addView(colorDropdown(colorNames, colorValues, colorValues.indexOf(prefs.keyTextColor).coerceAtLeast(0)) {
            prefs.keyTextColor = colorValues[it]
        }.also { textColorList = it })
        root.addView(slider("Deckkraft der Tasten", 0, 100, prefs.keyOpacity, "%") { prefs.keyOpacity = it }
            .also { opacityBar = it.getChildAt(1) as android.widget.SeekBar })
        root.addView(slider("Eckenrundung", 0, 24, prefs.cornerRadiusDp, " dp") { prefs.cornerRadiusDp = it })
        root.addView(slider("Schriftgröße", 70, 150, prefs.textScale, "%") { prefs.textScale = it })
        root.addView(switch("Kleine Zeichen über/unter den Buchstaben anzeigen", prefs.showHints) { prefs.showHints = it })
        addTestField()
    }

    private fun updateBgStatus() {
        bgStatus?.text = if (Background.current(this) != null) "✅ Ein Hintergrundbild ist gesetzt." else "Kein Bild gesetzt."
        renderGallery()
    }

    private var gallery: LinearLayout? = null

    /** Vorschaubilder der früheren Hintergründe; das aktive ist farbig umrandet. */
    private fun renderGallery() {
        val g = gallery ?: return
        g.removeAllViews()
        val names = Background.history(this)
        val active = prefs.bgFile
        val w = (96 * dp).toInt()
        val h = (64 * dp).toInt()
        val pad = (3 * dp).toInt()
        for (name in names) {
            val img = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(Background.thumbnail(this@SettingsActivity, name, w))
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(if (name == active) android.graphics.Color.rgb(0x4D, 0xB6, 0xAC) else android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(w, h).apply { setMargins(0, 0, (8 * dp).toInt(), 0) }
                setOnClickListener {
                    Background.select(this@SettingsActivity, name)
                    updateBgStatus()
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setMessage("Dieses Bild aus dem Verlauf löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            Background.delete(this@SettingsActivity, name)
                            updateBgStatus()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    true
                }
            }
            g.addView(img)
        }
        if (names.isEmpty()) g.addView(info("Noch keine Bilder."))
    }

    private fun pickImage() {
        val photoPicker = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        } else null
        val fallback = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        try {
            startActivityForResult(photoPicker ?: fallback, REQ_IMAGE)
        } catch (_: Exception) {
            startActivityForResult(fallback, REQ_IMAGE)
        }
    }

    @Deprecated("Activity-API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == REQ_EXPORT) return exportBackup(uri)
        if (requestCode == REQ_IMPORT) return importBackup(uri)
        if (requestCode != REQ_IMAGE) return
        bgStatus?.text = "Bild wird übernommen …"
        Thread {
            val ok = Background.import(this, uri)
            runOnUiThread {
                updateBgStatus()
                if (!ok) bgStatus?.text = "❌ Das Bild konnte nicht gelesen werden."
            }
        }.start()
    }

    /** Regler nach Gruppe (Bild / Tasten), jede Gruppe lässt sich einzeln sperren. */
    private val sliders = ArrayList<Pair<String, android.widget.SeekBar>>()
    private var sliderGroup = ""

    private fun isLocked(group: String) = when (group) {
        GROUP_IMAGE -> prefs.lockImage
        GROUP_KEYS -> prefs.lockKeys
        else -> false
    }

    /** Gesperrt: Regler (und beim Bild die Vorschau) reagieren nicht. */
    private fun applySliderLock() {
        sliders.forEach { (group, bar) -> bar.isEnabled = !isLocked(group) }
        preview?.locked = prefs.lockImage
    }

    /** Runder Farbpunkt; Weiß/Schwarz mit grauem Rand, "Automatisch" aus den Farben des Farbschemas. */
    private fun colorDot(color: Int): android.graphics.drawable.Drawable {
        val size = (20 * dp).toInt()
        val d = if (color == 0) {
            val t = Theme.byId(prefs.theme)
            android.graphics.drawable.GradientDrawable().apply {
                gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
                colors = intArrayOf(t.accent, t.accent, android.graphics.Color.WHITE, t.background, t.accent)
            }
        } else {
            android.graphics.drawable.GradientDrawable().apply { setColor(color) }
        }
        d.shape = android.graphics.drawable.GradientDrawable.OVAL
        d.setStroke((2 * dp).toInt(), android.graphics.Color.rgb(0x88, 0x88, 0x88))
        d.setSize(size, size)
        d.setBounds(0, 0, size, size)
        return d
    }

    /** Aufklapp-Liste mit Farbpunkt vor jedem Eintrag. */
    private fun colorDropdown(names: List<String>, values: List<Int>, selected: Int, onSelect: (Int) -> Unit) =
        android.widget.Spinner(this, android.widget.Spinner.MODE_DIALOG).apply {
            adapter = object : android.widget.ArrayAdapter<String>(
                this@SettingsActivity, android.R.layout.simple_spinner_item, names
            ) {
                private fun withDot(v: View, pos: Int): View {
                    (v as? TextView)?.apply {
                        setCompoundDrawablesRelative(colorDot(values[pos]), null, null, null)
                        compoundDrawablePadding = (14 * dp).toInt()
                    }
                    return v
                }
                override fun getView(pos: Int, convert: View?, parent: ViewGroup) =
                    withDot(super.getView(pos, convert, parent), pos)
                override fun getDropDownView(pos: Int, convert: View?, parent: ViewGroup) =
                    withDot(super.getDropDownView(pos, convert, parent), pos)
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, names.lastIndex), false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

    /** Aufklapp-Liste (braucht weniger Platz als Auswahlknöpfe). */
    private fun dropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
        android.widget.Spinner(this, android.widget.Spinner.MODE_DIALOG).apply {
            adapter = android.widget.ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, options)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(0, options.lastIndex), false)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }

    private fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onChange: (Int) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
            val text = TextView(this@SettingsActivity).apply { textSize = 15f }
            fun show(v: Int) { text.text = "$label: $v$unit" }
            show(value)
            addView(text)
            addView(android.widget.SeekBar(this@SettingsActivity).apply {
                val group = sliderGroup
                sliders.add(group to this)
                isEnabled = !isLocked(group)
                this.max = max - min
                progress = (value - min).coerceIn(0, max - min)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                        show(min + p)
                        if (fromUser) onChange(min + p)
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                })
            })
        }

    // ------------------------------------------------------------------
    // Profile & Sicherung
    // ------------------------------------------------------------------

    private lateinit var profileList: LinearLayout
    private var backupStatus: TextView? = null

    private fun buildProfiles() {
        title = "Profile & Sicherung"
        root.addView(header("Profile"))
        root.addView(info(
            "Ein Profil speichert alle Einstellungen: Aussehen samt Hintergrundbild, Tippen, Vorschläge, " +
                "Zwischenablage-Ansicht und Emoticons. Eigene Wörter gelten immer für alle Profile."
        ))
        root.addView(button("Aktuelle Einstellungen als Profil speichern") {
            val field = EditText(this).apply { hint = "Name, z. B. Dunkel mit Pony" }
            AlertDialog.Builder(this)
                .setTitle("Profil speichern")
                .setView(field)
                .setPositiveButton("Speichern") { _, _ ->
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty()) {
                        Profiles(this).save(name)
                        renderProfiles()
                    }
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(profileList)
        renderProfiles()

        root.addView(header("Sicherung als Datei"))
        root.addView(info(
            "Für ein neues Handy oder eine Neuinstallation: Einstellungen, Profile, eigene und gelernte Wörter, " +
                "gelernte Vorhersagen, Emoticons und das aktuelle Hintergrundbild in einer Datei, z. B. in Google Drive. " +
                "Der Zwischenablage-Verlauf wird aus Datenschutzgründen nicht gesichert."
        ))
        backupStatus = info("")
        root.addView(backupStatus)
        root.addView(button("Sicherung speichern …") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, "tastatur-sicherung.json"),
                REQ_EXPORT
            )
        })
        root.addView(button("Sicherung laden …") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_IMPORT
            )
        })
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val names = Profiles(this).names()
        if (names.isEmpty()) profileList.addView(info("Noch keine Profile."))
        for (n in names) {
            profileList.addView(listRow(n,
                "Laden" to {
                    if (Profiles(this).load(n)) backupStatus?.text = "✅ Profil „$n“ geladen."
                },
                "✕" to {
                    AlertDialog.Builder(this)
                        .setMessage("Profil „$n“ löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            Profiles(this).delete(n)
                            renderProfiles()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                },
            ))
        }
    }

    private fun exportBackup(uri: android.net.Uri) {
        backupStatus?.text = "Wird gespeichert …"
        Thread {
            val ok = try {
                val json = Backup.create(this)
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
            } catch (_: Exception) { false }
            runOnUiThread { backupStatus?.text = if (ok) "✅ Sicherung gespeichert." else "❌ Speichern fehlgeschlagen." }
        }.start()
    }

    private fun importBackup(uri: android.net.Uri) {
        backupStatus?.text = "Wird geladen …"
        Thread {
            val ok = try {
                val json = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                json != null && Backup.restore(this, json)
            } catch (_: Exception) { false }
            runOnUiThread {
                backupStatus?.text = if (ok) "✅ Sicherung geladen." else "❌ Das ist keine gültige Tastatur-Sicherung."
                renderProfiles()
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Emoticons
    // ------------------------------------------------------------------

    private lateinit var emoList: LinearLayout

    private fun buildEmoticons() {
        title = "Emoticons"
        addTestField()
        root.addView(info(
            "Erscheinen hinter der ^.^-Taste, je 12 pro Seite. Neue kommen oben dazu, ▲ ▼ verschieben einen Eintrag."
        ))
        root.addView(switch("Am häufigsten benutzte zuerst zeigen (bis zu 12)", prefs.emoticonsFrequentFirst) {
            prefs.emoticonsFrequentFirst = it
        })
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "Neues Emoticon, z. B. (◕‿◕)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(input)
        addRow.addView(Button(this).apply {
            text = "Hinzufügen"
            isAllCaps = false
            setOnClickListener {
                val e = input.text.toString().trim()
                if (e.isNotEmpty()) {
                    prefs.emoticons = listOf(e) + prefs.emoticons // neue Emoticons oben
                    input.setText("")
                    renderEmoticons()
                }
            }
        })
        root.addView(addRow)
        emoList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(emoList)
        root.addView(button("Standard wiederherstellen") {
            AlertDialog.Builder(this)
                .setMessage("Eigene Liste durch die ursprünglichen Emoticons ersetzen?")
                .setPositiveButton("Ersetzen") { _, _ ->
                    prefs.resetEmoticons()
                    renderEmoticons()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        })
        renderEmoticons()
    }

    private fun renderEmoticons() {
        emoList.removeAllViews()
        val list = prefs.emoticons
        if (list.isEmpty()) emoList.addView(info("Keine Emoticons."))
        list.forEachIndexed { i, e ->
            emoList.addView(listRow(e,
                "▲" to {
                    if (i > 0) {
                        val l = prefs.emoticons.toMutableList()
                        l.add(i - 1, l.removeAt(i))
                        prefs.emoticons = l
                        renderEmoticons()
                    }
                },
                "▼" to {
                    val l = prefs.emoticons.toMutableList()
                    if (i < l.lastIndex) {
                        l.add(i + 1, l.removeAt(i))
                        prefs.emoticons = l
                        renderEmoticons()
                    }
                },
                "✎" to { editEmoticon(i, e) },
                "✕" to {
                    confirmDelete("„$e“ löschen?") {
                        prefs.emoticons = prefs.emoticons.toMutableList().also { it.removeAt(i) }
                        renderEmoticons()
                    }
                },
            ))
        }
    }

    private fun editEmoticon(index: Int, old: String) {
        val field = EditText(this).apply {
            setText(old)
            setSelection(old.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle("Emoticon bearbeiten")
            .setView(field)
            .setPositiveButton("Speichern") { _, _ ->
                val new = field.text.toString().trim()
                if (new.isNotEmpty()) {
                    prefs.emoticons = prefs.emoticons.toMutableList().also { it[index] = new }
                    renderEmoticons()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Hilfe
    // ------------------------------------------------------------------

    private fun buildHelp() {
        title = "Hilfe"
        root.addView(header("Bedienung"))
        root.addView(info(
            "• Nach oben wischen: kleines Zeichen über dem Buchstaben (z. B. q → 1)\n" +
                "• Untere Reihe nach unten wischen: Zeichen unter dem Buchstaben (z. B. x → €)\n" +
                "• Lange drücken: Umlaute und Akzente (a → ä, s → ß)\n" +
                "• Leertaste seitlich wischen: Sprache wechseln\n" +
                "• Leertaste kurz halten, dann ziehen: Cursor bewegen\n" +
                "• Shift zweimal schnell: Feststelltaste\n" +
                "• ⌫ gedrückt halten: ganze Wörter löschen\n" +
                "• Zwei Leerzeichen: Punkt am Satzende\n" +
                "• Vorschlag antippen: einsetzen. Lange drücken: nicht mehr vorschlagen\n" +
                "• ⌫ direkt nach einer Korrektur: dein Wort zurück\n" +
                "• Versehentlich gelöscht oder übertippt: ↶ rechts neben den Vorschlägen antippen, dann „Wiederherstellen“ oder ✕\n" +
                "• Nach einem Leerzeichen: Vorschläge fürs nächste Wort (lernt aus deinem Schreiben)\n" +
                "• zb → z.B., oä → o.Ä., idr → i.d.R.\n" +
                "• 📋 Zwischenablage, ⚙ Menü mit Einstellungen, Größe und Aussehen\n" +
                "• ⚙ → 🔍 Suche: Browser öffnen, markierter Text wird gesucht\n" +
                "• ⚙ → ✎ Formatieren: Listen, fett, kursiv, durch- und unterstrichen, Telegram/Discord (**fett** ||Spoiler||), sPoNgEbOb, Kopfüber, Spiegel"
        ))
    }

    // ------------------------------------------------------------------
    // Bausteine
    // ------------------------------------------------------------------

    private fun addTestField() {
        root.addView(info("Ausprobieren:"))
        testField = EditText(this).apply {
            hint = "Hier tippen …"
            minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(testField)
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

    private fun radio(options: List<String>, selected: Int, onSelect: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = options.map { label ->
            RadioButton(this).apply {
                text = label
                id = View.generateViewId()
                setPadding(0, 0, (12 * dp).toInt(), 0)
            }.also { group.addView(it) }.id
        }
        group.check(ids[selected.coerceIn(0, ids.lastIndex)])
        group.setOnCheckedChangeListener { _, checkedId -> onSelect(ids.indexOf(checkedId)) }
        return group
    }

    @Suppress("DEPRECATION")
    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        textSize = 15f
        isChecked = checked
        setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    companion object {
        const val EXTRA_SECTION = "section"
        private const val REQ_IMAGE = 41
        private const val GROUP_IMAGE = "bild"
        private const val GROUP_KEYS = "tasten"
        private const val REQ_EXPORT = 42
        private const val REQ_IMPORT = 43
    }
}
