package de.spark633.tastatur

import android.content.ClipboardManager
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import java.util.concurrent.Executors

/** Der eigentliche Tastatur-Dienst, den Android in jeder App einblendet. */
class TastaturService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: Prefs
    private lateinit var clipHistory: ClipboardHistory
    private lateinit var userDict: UserDictionary
    private lateinit var nextWords: NextWordModel

    /** Zuletzt gelöschter Text, zum Wiederherstellen. */
    private val deletedText = StringBuilder()

    /** Markierter Text wurde durch Tippen ersetzt: Text davor merken, um das Getippte wieder zu entfernen. */
    private var replaceMode = false
    private var replaceAnchor = ""
    private var wordsSinceDelete = 0
    private var keyboard: KeyboardView? = null
    private var language = Language.DE
    private var isTextField = true
    private var lastSpaceTime = 0L

    // --- Wortvorschläge ---
    private var dict: Dictionary? = null
    private var dictLang: Language? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var neighbors = Layouts.neighbors(true)
    private var current: Dictionary.Suggestions? = null
    private var suggestSeq = 0
    private var suggestOn = true
    private var autocorrectOn = true

    /** Letzte Korrektur: getippt, ersetzt durch, Trennzeichen danach. */
    private var lastCorrection: Triple<String, String, String>? = null

    /** Wörter, deren Korrektur du abgelehnt hast (bleiben in dieser Sitzung unverändert). */
    private val ignored = HashSet<String>()

    /** Nach einem Satzzeichen wurde automatisch ein Leerzeichen gesetzt. */
    private var autoSpacePending = false

    /** Zuletzt kopierter Text, wird kurz in der Leiste zum Einfügen angeboten. */
    private var recentClip: String? = null
    private var recentClipImage: String? = null
    private var lastImageStamp = 0L
    private var recentClipTime = 0L
    private var clipShown = false
    private var isPasswordField = false
    private var isEmailField = false
    /** In der Leiste stehen gemerkte E-Mail-Adressen. */
    private var emailShown: List<String> = emptyList()

    /** Leerzeichen kam automatisch nach einem angetippten Vorschlag (Satzzeichen rückt dann ans Wort). */
    private var suggestionSpace = false

    /** Aktiver Unicode-Stil ("bold", "strike" …) oder null. */
    private var activeStyle: String? = null
    private var sarcasmCount = 0

    /** Beim formatierten Schreiben: das normale Wort und seine formatierten Teile (für Korrektur). */
    private val styledPlain = StringBuilder()
    private val styledPieces = ArrayList<String>()
    private var styledWordStart = 0

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
    }

    private val clipboard: ClipboardManager? by lazy { getSystemService(ClipboardManager::class.java) }

    /** Jeder kopierte Text landet im Verlauf (Android erlaubt das der aktiven Tastatur). */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    /** Die Einstellungs-App kann den Größen-Modus anstoßen. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_RESIZE_REQUEST) {
            if (prefs.resizeRequest) {
                prefs.resizeRequest = false
                keyboard?.startResize()
            }
        } else {
            // Live-Vorschau: Aussehen und andere Einstellungen sofort übernehmen
            keyboard?.configure(prefs, language)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        clipHistory = ClipboardHistory.get(this)
        userDict = UserDictionary.get(this)
        nextWords = NextWordModel.get(this)
        clipboard?.addPrimaryClipChangedListener(clipListener)
        prefs.registerListener(prefListener)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        clipboard?.removePrimaryClipChangedListener(clipListener)
        prefs.unregisterListener(prefListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        v.clipHistory = clipHistory
        keyboard = v
        // Systemknöpfe (Zurück, Tastatur wechseln) liegen über der Tastatur:
        // deren Höhe als Abstand unten freihalten.
        window?.window?.decorView?.setOnApplyWindowInsetsListener { dv, insets ->
            updateSystemInset(insets)
            dv.onApplyWindowInsets(insets)
        }
        return v
    }

    private fun updateSystemInset(insets: WindowInsets?) {
        var bottom = 0
        if (insets != null) {
            bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
        }
        // Ab Android 15 liegt die Tastatur hinter der Navigationsleiste, auch
        // wenn Android keinen Abstand meldet: Höhe der Leiste selbst bestimmen.
        if (bottom == 0 && Build.VERSION.SDK_INT >= 35) bottom = navigationBarHeight()
        keyboard?.setSystemInset(bottom)
    }

    @Suppress("DiscouragedApi", "InternalInsetResource")
    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            .takeIf { it != 0 } ?: resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else (48 * resources.displayMetrics.density).toInt()
    }

    /** Kopiertes Bild in den eigenen Ordner kopieren (im Hintergrund) und anbieten. */
    private fun captureImage(uri: android.net.Uri, mime: String) {
        worker.execute {
            val name = try {
                val n = "bild_${System.currentTimeMillis()}.${ClipImageProvider.extFor(mime)}"
                val f = java.io.File(ClipboardHistory.imageDir(this), n)
                contentResolver.openInputStream(uri)?.use { input ->
                    f.outputStream().use { out -> input.copyTo(out) }
                }
                if (f.length() in 1..MAX_IMAGE_BYTES) n else { f.delete(); null }
            } catch (_: Exception) { null }
            if (name != null) mainHandler.post {
                clipHistory.addImage(name)
                recentClip = null
                recentClipImage = name
                recentClipTime = SystemClock.uptimeMillis()
                updateSuggestions()
                keyboard?.invalidate()
            }
        }
    }

    /**
     * Bild einfügen wie beim normalen "Einfügen": zurück in die Android-Zwischenablage
     * legen und die App selbst einfügen lassen.
     */
    private fun pasteImage(name: String) {
        val ic = currentInputConnection ?: return
        try {
            val clip = android.content.ClipData.newUri(contentResolver, "Bild", ClipImageProvider.uriFor(name))
            clipboard?.setPrimaryClip(clip)
            if (!ic.performContextMenuAction(android.R.id.paste)) keyboard?.showMessage("Diese App nimmt hier keine Bilder an")
        } catch (_: Exception) {
            keyboard?.showMessage("Bild konnte nicht eingefügt werden")
        }
    }

    override fun onPasteImage(name: String) {
        own()
        captureReplacedSelection()
        if (name == recentClipImage) recentClipImage = null
        pasteImage(name)
    }

    private fun captureClip() {
        try {
            val clip = clipboard?.primaryClip ?: return
            if (clip.itemCount == 0) return
            // Passwörter aus Passwort-Managern nicht speichern
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            val item = clip.getItemAt(0)
            // Bild kopiert: Kopie im Verlauf ablegen
            val uri = item.uri
            if (uri != null) {
                if (uri.authority == ClipImageProvider.AUTHORITY) return // eigenes Bild (gerade eingefügt)
                val mime = clip.description?.getMimeType(0)?.takeIf { it.startsWith("image/") }
                    ?: contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
                if (mime != null) {
                    // Jeder Kopiervorgang nur einmal (sonst doppelt beim Öffnen der Zwischenablage)
                    val stamp = clip.description?.timestamp ?: 0L
                    if (stamp != 0L && stamp == lastImageStamp) return
                    lastImageStamp = stamp
                    if (prefs.clipImages) captureImage(uri, mime)
                    return
                }
            }
            val text = item.coerceToText(this)?.toString() ?: return
            clipHistory.add(text)
            // Neu kopiert: für ein paar Minuten direkt in der Leiste anbieten
            if (text.isNotBlank() && text != recentClip) {
                recentClip = text
                recentClipImage = null
                recentClipTime = SystemClock.uptimeMillis()
                updateSuggestions()
            }
            keyboard?.invalidate()
        } catch (_: Exception) {
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Zurück-Taste/-Geste: erst Menüs schließen, dann wie gewohnt die Tastatur. */
    private var backConsumed = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown && keyboard?.handleBack() == true) {
            backConsumed = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && backConsumed) {
            backConsumed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = keyboard ?: return
        language = prefs.currentLanguage
        v.configure(prefs, language)

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        if (!restarting) {
            when (cls) {
                // Nur Zahlen gefragt (z. B. Authenticator-Code): Ziffernblock
                InputType.TYPE_CLASS_NUMBER -> v.showNumpad(Layouts.NumpadKind.NUMBER)
                InputType.TYPE_CLASS_PHONE -> v.showNumpad(Layouts.NumpadKind.PHONE)
                InputType.TYPE_CLASS_DATETIME -> v.showNumpad(Layouts.NumpadKind.DATETIME)
                else -> v.setMode(KeyboardView.Mode.LETTERS)
            }
        }

        isEmailField = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        isPasswordField = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER &&
            (info.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        isTextField = cls == InputType.TYPE_CLASS_TEXT && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )

        val noSuggestFlag = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        // Adress-/Suchleiste im Browser: Vorschläge ja, aber keine Autokorrektur
        val isUri = cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        suggestOn = (isTextField || isUri) && prefs.suggestions
        autocorrectOn = suggestOn && !isUri && prefs.autocorrect && !noSuggestFlag &&
            variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        lastCorrection = null
        autoSpacePending = false
        activeStyle = null
        clearDeleted()
        v.setStyleIndicator(null)
        current = null // eigene Wörter könnten sich in den Einstellungen geändert haben
        languageChanged()

        v.setEnterLabel(enterLabel(info))
        updateSystemInset(window?.window?.decorView?.rootWindowInsets)
        updateAutoCaps()
        if (prefs.resizeRequest) {
            prefs.resizeRequest = false
            v.startResize()
        }
    }

    override fun onFinishInput() {
        saveEmail()
        super.onFinishInput()
    }

    // --- E-Mail-Adressen ---

    private val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}")

    /** Adresse im E-Mail-Feld merken (beim Verlassen des Feldes oder ↵). */
    private fun saveEmail() {
        if (!isEmailField || !prefs.rememberEmails) return
        val ic = currentInputConnection ?: return
        val text = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()?.trim()
            ?: return
        if (text.length <= 100 && EMAIL.matches(text)) prefs.noteEmail(text)
    }

    /** Passende gemerkte Adressen zum Getippten (Text seit dem letzten Leerzeichen/Komma). */
    private fun emailMatches(): List<String> {
        if (!isEmailField || !prefs.rememberEmails) return emptyList()
        val before = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString() ?: return emptyList()
        val typed = before.takeLastWhile { !it.isWhitespace() && it != ',' && it != ';' }
        return prefs.savedEmails.filter { it.startsWith(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
            .take(3)
    }

    private fun insertEmail(e: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(100, 0)?.toString().orEmpty()
        val typed = before.takeLastWhile { !it.isWhitespace() && it != ',' && it != ';' }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(e, 1)
        ic.endBatchEdit()
        prefs.noteEmail(e)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        nextWords.save()
        keyboard?.leaveSubPage()
        keyboard?.cancelTouches()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        trackExternalReplace(newSelStart, newSelEnd)
        handleWordTap(newSelStart, newSelEnd)
        updateAutoCaps()
        updateSuggestions()
    }

    // --- Markierten Text, den die App ersetzt (Einfügen/Ausschneiden im Menü), wiederherstellbar machen ---

    /** Letzte Markierung: Anfang, Text, Text davor (Anker). */
    private var lastSelection: Triple<Int, String, String>? = null

    private fun trackExternalReplace(start: Int, end: Int) {
        val ic = currentInputConnection ?: return
        if (start != end) {
            val sel = ic.getSelectedText(0)?.toString()
            lastSelection = if (sel.isNullOrEmpty() || sel.length > 5000) null
            else Triple(minOf(start, end), sel, ic.getTextBeforeCursor(40, 0)?.toString().orEmpty())
            return
        }
        val prev = lastSelection ?: return
        lastSelection = null
        // Über die Tastatur ersetzt/gelöscht: schon erfasst
        if (SystemClock.uptimeMillis() - lastOwnInput < 600) return
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val text = et.text ?: return
        val (selStart, selText, anchor) = prev
        val i = selStart - et.startOffset
        val unchanged = i >= 0 && i + selText.length <= text.length &&
            text.subSequence(i, i + selText.length).toString() == selText
        if (unchanged) return // nur Markierung aufgehoben
        clearDeleted()
        rememberDeleted(selText)
        replaceMode = true
        replaceAnchor = anchor
        val j = start - et.startOffset
        replaceInserted = if (i in 0..j && j <= text.length) text.subSequence(i, j).toString() else ""
    }

    /** Von der App eingefügter Text (beim Wiederherstellen nur genau den entfernen). */
    private var replaceInserted = ""

    // --- Zweimal ins selbe Wort tippen → Cursor ans Wortende ---

    /** Zeitpunkt der letzten Eingabe über die Tastatur (Cursor-Bewegungen danach sind keine Taps). */
    private var lastOwnInput = 0L
    private var lastWordTap: Triple<Int, Int, Long>? = null

    private fun own() {
        lastOwnInput = SystemClock.uptimeMillis()
        lastWordTap = null
    }

    /**
     * Cursor bewegt, ohne dass auf der Tastatur getippt wurde = Tipp in den Text.
     * Zweiter Tipp ins selbe Wort → Cursor ans Wortende. Markieren (Doppeltipp) bleibt unberührt.
     */
    private fun handleWordTap(start: Int, end: Int) {
        val now = SystemClock.uptimeMillis()
        if (!prefs.tapWordEnd || now - lastOwnInput < 600 || start != end) {
            if (start != end) lastWordTap = null
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(48, 0)?.toString().orEmpty()
        val inWord = before.isNotEmpty() && isWordChar(before.last()) && after.isNotEmpty() && isWordChar(after[0])
        if (!inWord) {
            lastWordTap = null
            return
        }
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        var j = 0
        while (j < after.length && isWordChar(after[j])) j++
        val wordStart = start - (before.length - i)
        val wordEnd = start + j
        val prev = lastWordTap
        if (prev != null && prev.first == wordStart && prev.second == wordEnd && now - prev.third < 4000) {
            lastWordTap = null
            lastOwnInput = now // die eigene Bewegung nicht als Tipp zählen
            ic.setSelection(wordEnd, wordEnd)
        } else {
            lastWordTap = Triple(wordStart, wordEnd, now)
        }
    }

    private fun enterLabel(info: EditorInfo): String {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "➜"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }
    }

    /** Großbuchstabe am Satzanfang, nach . ! ? und nach einem Zeilenumbruch. */
    private fun updateAutoCaps() {
        val v = keyboard ?: return
        if (!prefs.autoCaps || !isTextField) {
            v.setAutoShift(false)
            return
        }
        val longer = currentInputConnection?.getTextBeforeCursor(12, 0)?.toString() ?: return
        val before = longer.takeLast(3)
        val trimmed = before.trimEnd()
        var caps = (trimmed.isEmpty() && longer.length < 3) ||
            before.endsWith("\n") ||
            (before.length > trimmed.length && trimmed.isNotEmpty() && trimmed.last() in ".!?")
        // Nach "z.B." oder "d.h." geht der Satz weiter
        if (caps && !before.endsWith("\n")) {
            val lastToken = longer.trimEnd().substringAfterLast(' ').substringAfterLast('\n').lowercase()
            if (Dictionary.DOT_ABBREVIATIONS_DE.values.any { it.lowercase() == lastToken }) caps = false
        }
        v.setAutoShift(caps)
    }

    // ------------------------------------------------------------------
    // Wortvorschläge und Korrektur
    // ------------------------------------------------------------------

    private fun languageChanged() {
        neighbors = Layouts.neighbors(prefs.isQwertz(language))
        if (dictLang != language) {
            val lang = language
            dictLang = lang
            dict = null // altes Wörterbuch freigeben
            current = null
            worker.execute {
                val d = try { Dictionary.load(this, lang) } catch (_: Throwable) { null }
                mainHandler.post {
                    if (dictLang == lang) {
                        dict = d
                        updateSuggestions()
                    }
                }
            }
        }
        updateSuggestions()
    }

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '-'

    /** Das Wort direkt vor dem Cursor, oder null (z. B. mitten im Wort, in Links, nach Ziffern). */
    private fun currentWord(): String? {
        val ic = currentInputConnection ?: return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return null
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        val word = before.substring(i).trimStart('\'', '-')
        if (word.isEmpty() || !word[0].isLetter() || word.last() == '-') return null
        val start = before.length - word.length
        if (start > 0 && before[start - 1].let { it.isDigit() || it in "@/._#\\" }) return null
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && isWordChar(after[0])) return null
        return word
    }

    private fun isKnown(word: String) = ignored.contains(word.lowercase()) || userDict.isKnown(word)

    /** Vorschläge sofort berechnen (für Leerzeichen, Satzzeichen, Enter). */
    private fun suggestNow(d: Dictionary, word: String): Dictionary.Suggestions =
        dotAbbreviation(word) ?: current?.takeIf { it.typed == word }
            ?: d.suggest(word, neighbors, ::isKnown, userDict.knownWords(), userDict.blockedWords().toHashSet())

    /** Ein Wort ist fertig getippt und blieb unverändert: unbekannte Wörter merken. */
    private fun noteWordEnd(word: String?) {
        if (word == null || !prefs.learnWords || word.length < 2) return
        val d = dict ?: return
        if (d.contains(word) || word.any { it.isDigit() }) return
        userDict.noteTyped(word, sentenceStartBefore(word.length))
    }

    /** Steht vor den letzten [chars] Zeichen ein Satzanfang (Textbeginn, . ! ? oder Zeilenumbruch)? */
    private fun sentenceStartBefore(chars: Int): Boolean {
        val text = currentInputConnection?.getTextBeforeCursor(chars + 4, 0)?.toString() ?: return false
        val prefix = text.dropLast(chars).trimEnd(' ')
        return prefix.isEmpty() || prefix.last() in ".!?\n"
    }

    /** "zb" → "z.B." (nur Deutsch). Am Satzanfang großgeschrieben: "Z.B.". */
    private fun dotAbbreviation(word: String): Dictionary.Suggestions? {
        if (language != Language.DE || isKnown(word)) return null
        val e = Dictionary.DOT_ABBREVIATIONS_DE[word.lowercase()] ?: return null
        val cased = if (word[0].isUpperCase()) e.replaceFirstChar { it.uppercaseChar() } else e
        return Dictionary.Suggestions(word, cased, listOf("\"$word\"", cased, ""))
    }

    // --- Wiederherstellen ---

    /** Genug gelöscht, dass sich ein Angebot lohnt (mehrere Wörter oder längerer Text). */
    private fun restoreAvailable() =
        (replaceMode && deletedText.isNotEmpty()) ||
            deletedText.length >= RESTORE_MIN_CHARS ||
            deletedText.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

    private fun rememberDeleted(text: CharSequence) {
        if (text.isEmpty()) return
        deletedText.insert(0, text)
        if (deletedText.length > 5000) deletedText.setLength(5000)
    }

    private fun clearDeleted() {
        deletedText.setLength(0)
        replaceMode = false
        replaceAnchor = ""
        replaceInserted = ""
        wordsSinceDelete = 0
    }

    /** Neue Eingabe: kleine Löschungen (Tippfehler) vergessen, größere noch zwei Wörter lang anbieten. */
    private fun forgetDeleted(wordFinished: Boolean) {
        if (!restoreAvailable()) {
            clearDeleted()
        } else if (wordFinished && ++wordsSinceDelete >= 2) {
            clearDeleted()
        }
    }

    /**
     * Vor dem Tippen: Ist Text markiert, ersetzt der Buchstabe ihn. Den markierten
     * Text merken, damit er sich wiederherstellen lässt.
     */
    private fun captureReplacedSelection() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) return
        clearDeleted()
        rememberDeleted(selected)
        replaceMode = true
        replaceAnchor = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
    }

    private fun restoreDeleted() {
        own()
        val ic = currentInputConnection ?: return
        val text = deletedText.toString()
        ic.beginBatchEdit()
        if (replaceMode) {
            // Versehentlich Getipptes (seit dem Ersetzen) wieder entfernen
            val before = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
            val start = if (replaceAnchor.isEmpty()) 0
            else before.lastIndexOf(replaceAnchor).let { if (it >= 0) it + replaceAnchor.length else -1 }
            if (start >= 0 && before.length - start in 1..300) ic.deleteSurroundingText(before.length - start, 0)
            else if (replaceInserted.length > 300) {
                // Langer eingefügter Text: nur entfernen, wenn er noch genau vor dem Cursor steht
                val far = ic.getTextBeforeCursor(replaceInserted.length + replaceAnchor.length, 0)?.toString().orEmpty()
                if (far == replaceAnchor + replaceInserted || (replaceAnchor.isEmpty() && far == replaceInserted)) {
                    ic.deleteSurroundingText(replaceInserted.length, 0)
                }
            }
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        clearDeleted()
        keyboard?.showMessage("Wiederhergestellt")
    }

    // --- Zuletzt Kopiertes ---

    /** Leeres Feld, leere neue Zeile oder direkt nach einem Doppelpunkt (dort passt Eingefügtes hin). */
    private fun atEmptySpot(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: return false
        val after = ic.getTextAfterCursor(200, 0)?.toString().orEmpty()
        val lineBefore = before.substringAfterLast('\n')
        val lineAfter = after.substringBefore('\n')
        if (!lineAfter.isBlank()) return false
        // Leere Zeile, direkt nach "Link: " / "Adresse: " oder nach einem öffnenden Anführungszeichen
        return lineBefore.isBlank() || lineBefore.trimEnd().endsWith(":") || opensQuote(lineBefore)
    }

    /** Endet mit einem öffnenden (nicht schließenden) Anführungszeichen: davor Zeilenanfang, Leerzeichen, : oder ( */
    private fun opensQuote(line: String): Boolean {
        if (line.isEmpty() || line.last() !in "\"„“”«»‚‘'") return false
        val prev = line.dropLast(1).lastOrNull() ?: return true
        return prev.isWhitespace() || prev in ":("
    }

    /** Beschriftung für den Einfüge-Vorschlag, oder null wenn nichts Frisches kopiert wurde. */
    private fun clipOffer(): String? {
        if (isPasswordField || SystemClock.uptimeMillis() - recentClipTime > CLIP_OFFER_MS) return null
        // E-Mail-Feld: nur Kopiertes mit @ anbieten (sonst lieber die gemerkten Adressen)
        if (isEmailField && recentClip?.contains('@') != true) return null
        if (recentClipImage != null) return "📋 Bild einfügen"
        val text = recentClip ?: return null
        val line = text.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        val short = if (line.length > 28) line.take(27) + "…" else line
        return "📋 $short"
    }

    // --- Nächstes Wort ---

    /** Nach einem fertigen Wort das Wortpaar lernen (Trennzeichen steht schon). */
    private fun learnPair() {
        if (!suggestOn || !prefs.predictNext) return
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: return
        val tokens = before.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || (tokens.size < 2 && before.length >= 80)) return
        val last = tokens.last().trim { !it.isLetterOrDigit() }
        if (last.isEmpty() || !last[0].isLetter() || last.any { Character.isSurrogate(it) }) return
        val prevRaw = tokens.getOrNull(tokens.size - 2)
        val prev = when {
            prevRaw == null || prevRaw.last() in ".!?" -> "^"
            else -> prevRaw.trim { !it.isLetterOrDigit() }.ifEmpty { "^" }
        }
        // Am Satzanfang großgeschriebene Wörter klein lernen, wenn das Wörterbuch sie klein kennt
        val word = if (prev == "^" && dict?.contains(last.lowercase()) == true) last.lowercase() else last
        nextWords.record(language, prev, word)
    }

    /** Vorhersagen für die Stelle hinter dem Cursor (nur direkt nach einem Leerzeichen). */
    private fun predictions(): List<String> {
        val before = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString() ?: return emptyList()
        val ctx = when {
            before.isEmpty() -> "^"
            !before.last().isWhitespace() -> return emptyList()
            else -> {
                val t = before.trim().split(Regex("\\s+")).lastOrNull { it.isNotEmpty() }
                when {
                    t == null -> "^"
                    t.last() in ".!?" -> "^"
                    else -> t.trim { !it.isLetterOrDigit() }.ifEmpty { return emptyList() }
                }
            }
        }
        val blocked = userDict.blockedWords().toHashSet()
        val list = nextWords.predict(language, ctx, 5).filter { !blocked.contains(it.lowercase()) }.take(3)
        val cased = if (ctx == "^") list.map { w -> w.replaceFirstChar { it.uppercaseChar() } } else list
        if (cased.isEmpty()) return emptyList()
        return listOf(cased.getOrElse(1) { "" }, cased[0], cased.getOrElse(2) { "" })
    }

    private fun updateSuggestions() {
        val v = keyboard ?: return
        val d = dict
        clipShown = false
        emailShown = emptyList()
        v.setRestoreVisible(restoreAvailable()) // ↶ neben den Vorschlägen, Vorschläge laufen weiter
        // Gerade etwas kopiert: nur in leerem Feld oder am Anfang einer neuen Zeile anbieten
        clipOffer()?.let { label ->
            if (atEmptySpot()) {
                current = null
                suggestSeq++
                clipShown = true
                v.setSuggestions(listOf("", label, ""))
                return
            }
        }
        // E-Mail-Feld: gemerkte Adressen vorschlagen (wichtigste in der Mitte)
        val mails = emailMatches()
        if (mails.isNotEmpty()) {
            current = null
            suggestSeq++
            emailShown = mails
            v.setSuggestions(listOf(mails.getOrElse(1) { "" }, mails[0], mails.getOrElse(2) { "" }))
            return
        }
        val styled = activeStyle != null
        val word = when {
            !suggestOn -> null
            styled -> styledPlain.toString().ifEmpty { null }
            else -> currentWord()
        }
        if (word == null || d == null) {
            suggestSeq++
            val preds = if (suggestOn && prefs.predictNext && word == null) predictions() else emptyList()
            current = if (preds.isEmpty()) null else Dictionary.Suggestions("", null, preds)
            v.setSuggestions(preds)
            return
        }
        if (current?.typed == word) return
        dotAbbreviation(word)?.let {
            suggestSeq++
            current = it
            v.setSuggestions(it.slots)
            return
        }
        val seq = ++suggestSeq
        val nb = neighbors
        val own = userDict.knownWords()
        val ownLower = own.map { it.lowercase() }.toHashSet()
        val blocked = userDict.blockedWords().toHashSet()
        val ign = HashSet(ignored)
        worker.execute {
            val s = try {
                d.suggest(word, nb, { ign.contains(it.lowercase()) || ownLower.contains(it.lowercase()) }, own, blocked)
            } catch (_: Throwable) { null }
            mainHandler.post {
                if (seq == suggestSeq && s != null) {
                    current = s
                    v.setSuggestions(s.slots)
                }
            }
        }
    }

    /** Korrigiert das Wort vor dem Cursor und fügt das Trennzeichen an. true = erledigt. */
    private fun correctAndCommit(separator: String): Boolean {
        if (!autocorrectOn) return false
        val d = dict ?: return false
        val word = currentWord() ?: return false
        val s = suggestNow(d, word)
        val corr = s.correction ?: return false
        if (corr == word) return false
        // "zb." getippt: kein doppelter Punkt hinter "z.B."
        val sep = if (separator == "." && corr.endsWith(".")) "" else separator
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(corr + sep, 1)
        ic.endBatchEdit()
        lastCorrection = Triple(word, corr, sep)
        current = null
        return true
    }

    /** ⌫ direkt nach einer Korrektur: getipptes Wort wiederherstellen. */
    private fun undoCorrection(): Boolean {
        val lc = lastCorrection ?: return false
        lastCorrection = null
        if (!prefs.undoCorrection) return false
        val (original, corrected, sep) = lc
        val ic = currentInputConnection ?: return false
        val expected = corrected + sep
        val before = ic.getTextBeforeCursor(expected.length, 0)?.toString()
        if (before != expected) return false
        val atStart = sentenceStartBefore(expected.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(original + sep.trim(), 1) // Satzzeichen bleibt, Leerzeichen fällt weg
        ic.endBatchEdit()
        ignored.add(original.lowercase())
        if (prefs.learnWords) userDict.learnNow(original, atStart)
        current = null
        autoSpacePending = false
        return true
    }

    // ------------------------------------------------------------------
    // KeyboardView.Listener
    // ------------------------------------------------------------------

    override fun onText(text: String) {
        own()
        val ic = currentInputConnection ?: return
        if (text == recentClip) recentClip = null // aus der Zwischenablage eingefügt
        captureReplacedSelection()
        activeStyle?.let { style ->
            val isWordChar = text.length == 1 && (text[0].isLetter() || text[0] == '\'')
            if (isWordChar) {
                // Normales Wort im Hintergrund mitschreiben, damit es korrigiert werden kann
                if (styledPlain.isEmpty()) styledWordStart = sarcasmCount
                val piece = styleOf(style, text)
                styledPlain.append(text)
                styledPieces.add(piece)
                ic.commitText(piece, 1)
                updateSuggestions()
                return
            }
            if (text.length == 1 && text[0] in PUNCTUATION) finishStyledWord(style) else resetStyledWord()
            ic.commitText(styleOf(style, text), 1)
            return
        }
        if (text.length == 1 && text[0] in PUNCTUATION) {
            onPunctuation(text)
            return
        }
        if (text.length == 1 && text[0] in CLOSING_QUOTES && onQuote(text)) return
        suggestionSpace = false
        lastCorrection = null
        autoSpacePending = false
        forgetDeleted(false)
        ic.commitText(text, 1)
    }

    /**
     * Anführungszeichen direkt nach einem Wort (oder nach . ! ?) schließt es:
     * Wort korrigieren, danach automatisch ein Leerzeichen. Öffnende Zeichen
     * (nach einem Leerzeichen) laufen normal. true = erledigt.
     */
    private fun onQuote(q: String): Boolean {
        val ic = currentInputConnection ?: return false
        pullBackSuggestionSpace()
        // Nach "Hallo. " das automatische Leerzeichen wieder entfernen: Hallo."
        var before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (autoSpacePending && before.length == 2 && before[1] == ' ' && before[0] in ".!?") {
            ic.deleteSurroundingText(1, 0)
            before = before.dropLast(1)
        }
        autoSpacePending = false
        val prev = before.lastOrNull()
        if (prev == null || !(prev.isLetterOrDigit() || prev in ".!?")) return false // öffnend
        val word = currentWord()
        forgetDeleted(true)
        if (!correctAndCommit(q)) {
            noteWordEnd(word)
            lastCorrection = null
            ic.commitText(q, 1)
        }
        if (word != null) learnPair()
        val nextIsSpace = ic.getTextAfterCursor(1, 0)?.toString()?.firstOrNull()?.isWhitespace() == true
        if (prefs.autoSpacePunct && isTextField && !nextIsSpace) {
            ic.commitText(" ", 1)
            autoSpacePending = true
            lastCorrection = lastCorrection?.let { Triple(it.first, it.second, it.third + " ") }
        }
        return true
    }

    /** Satzzeichen: Wort davor korrigieren, danach ggf. automatisch ein Leerzeichen. */
    /** Nach einem Vorschlag: das automatische Leerzeichen weg, damit das Zeichen ans Wort rückt. */
    private fun pullBackSuggestionSpace() {
        if (!suggestionSpace) return
        suggestionSpace = false
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(1, 0)
    }

    private fun onPunctuation(p: String) {
        val ic = currentInputConnection ?: return
        pullBackSuggestionSpace()
        var afterPunct = false
        // "?" direkt nach "!" : das automatische Leerzeichen wieder entfernen
        if (autoSpacePending && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.deleteSurroundingText(1, 0)
            afterPunct = true
        }
        autoSpacePending = false
        val prev = ic.getTextBeforeCursor(1, 0)?.toString()?.firstOrNull()
        val word = currentWord()
        forgetDeleted(true)
        val corrected = correctAndCommit(p)
        if (!corrected) {
            noteWordEnd(word)
            lastCorrection = null
            ic.commitText(p, 1)
        }
        if (word != null) learnPair()
        val wantsSpace = prefs.autoSpacePunct && isTextField &&
            (afterPunct || (prev != null && (prev.isLetter() || prev in ")\"'")))
        val nextIsSpace = ic.getTextAfterCursor(1, 0)?.toString()?.firstOrNull()?.isWhitespace() == true
        if (wantsSpace && !nextIsSpace) {
            ic.commitText(" ", 1)
            autoSpacePending = true
            lastCorrection = lastCorrection?.let { Triple(it.first, it.second, it.third + " ") }
        }
    }

    override fun onSuggestion(index: Int) {
        own()
        val style = activeStyle
        if (style != null) {
            val s = current ?: return
            if (styledPlain.isEmpty() || s.typed != styledPlain.toString() || !styledWordIntact()) return
            val chosen = if (index == 0 && s.correction != null) s.typed
            else s.slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
            replaceStyledWord(style, chosen)
            currentInputConnection?.commitText(styledSpace(style), 1)
            resetStyledWord()
            current = null
            return
        }
        if (emailShown.isNotEmpty()) {
            val e = when (index) { 1 -> emailShown.getOrNull(0); 0 -> emailShown.getOrNull(1); else -> emailShown.getOrNull(2) }
            if (e != null) insertEmail(e)
            return
        }
        if (clipShown) {
            if (index == 1) {
                val img = recentClipImage
                if (img != null) pasteImage(img) else recentClip?.let { currentInputConnection?.commitText(it, 1) }
                recentClip = null // eingefügt: nicht mehr anbieten
                recentClipImage = null
                clipShown = false
            }
            return
        }
        val s = current ?: return
        if (s.typed.isEmpty()) {
            // Vorhersage: Wort mit Leerzeichen anhängen
            val chosen = s.slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
            currentInputConnection?.commitText("$chosen ", 1)
            learnPair()
            current = null
            suggestionSpace = true
            lastSpaceTime = SystemClock.uptimeMillis()
            return
        }
        val word = currentWord() ?: return
        if (s.typed != word) return
        val chosen = if (index == 0 && s.correction != null) {
            ignored.add(word.lowercase()) // eigenes Wort behalten und lernen
            if (prefs.learnWords) userDict.learnNow(word, sentenceStartBefore(word.length))
            word
        } else {
            s.slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$chosen ", 1)
        ic.endBatchEdit()
        forgetDeleted(true)
        learnPair()
        lastCorrection = null
        autoSpacePending = false
        current = null
        suggestionSpace = true
        lastSpaceTime = SystemClock.uptimeMillis()
    }

    override fun onRestore() {
        own()
        if (!restoreAvailable()) return updateSuggestions()
        keyboard?.askConfirm(if (replaceMode) "Ersetzten Text wiederherstellen?" else "Gelöschten Text wiederherstellen?", yes = {
            if (restoreAvailable()) restoreDeleted()
            updateSuggestions()
        }, yesLabel = "Wiederherstellen", no = {
            clearDeleted()
            keyboard?.showMessage("Verworfen")
            updateSuggestions()
        })
    }

    override fun onSuggestionLongPress(index: Int) {
        if (emailShown.isNotEmpty()) {
            val e = when (index) { 1 -> emailShown.getOrNull(0); 0 -> emailShown.getOrNull(1); else -> emailShown.getOrNull(2) }
                ?: return
            keyboard?.askConfirm("„$e“ vergessen?", yes = {
                prefs.forgetEmail(e)
                keyboard?.showMessage("Vergessen")
                updateSuggestions()
            }, yesLabel = "Vergessen", no = { updateSuggestions() })
            return
        }
        if (clipShown) {
            recentClip = null
            recentClipImage = null
            keyboard?.showMessage("Ausgeblendet")
            updateSuggestions()
            return
        }
        val s = current ?: return
        if (index == 0 && s.correction != null) {
            keyboard?.showMessage("Antippen behält dein Wort")
            return
        }
        val word = s.slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        if (word.equals(s.typed, ignoreCase = true) && dict?.contains(word) == true) {
            keyboard?.showMessage("Wörterbuch-Wörter lassen sich nur als Vorschlag entfernen")
            return
        }
        keyboard?.askConfirm("„$word“ nicht mehr vorschlagen?", yes = {
            userDict.block(word)
            ignored.remove(word.lowercase())
            keyboard?.showMessage("„$word“ wird nicht mehr vorgeschlagen")
            current = null
            updateSuggestions()
        })
    }

    override fun onDelete() {
        own()
        suggestionSpace = false
        if (activeStyle != null && styledPieces.isNotEmpty() && styledWordIntact()) {
            // Letzten formatierten Buchstaben samt Strichen/Akzenten löschen
            val piece = styledPieces.removeAt(styledPieces.lastIndex)
            styledPlain.setLength(styledPlain.length - 1)
            if (activeStyle == "sarcasm") sarcasmCount = maxOf(styledWordStart, sarcasmCount - 1)
            currentInputConnection?.deleteSurroundingText(piece.length, 0)
            updateSuggestions()
            return
        }
        if (activeStyle != null) resetStyledWord()
        if (undoCorrection()) return
        autoSpacePending = false
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            rememberDeleted(selected)
            ic.commitText("", 1)
        } else {
            val b = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            // Nach dem Ersetzen einer Markierung: das Getippte löschen gehört nicht zum alten Text
            if (b.isNotEmpty() && !replaceMode) {
                rememberDeleted(if (b.length == 2 && Character.isLowSurrogate(b[1])) b else b.takeLast(1))
            }
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** Alles Zusammenhängende bis zum letzten Leerzeichen löschen (z. B. "!?.!€-*" oder ein Passwort). */
    override fun onDeleteChunk() {
        own()
        suggestionSpace = false
        lastCorrection = null
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            rememberDeleted(selected)
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val count = before.length - i
        if (count > 0) {
            if (!replaceMode) rememberDeleted(before.substring(i))
            ic.deleteSurroundingText(count, 0)
        }
    }

    override fun onDeleteWord() {
        own()
        suggestionSpace = false
        lastCorrection = null
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            rememberDeleted(selected)
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] in "'-")) {
            while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] in "'-")) i--
        } else if (i > 0) {
            // Satzzeichen oder Emoji: ein Zeichen (Emojis bestehen aus zwei Teilen)
            i -= if (i > 1 && Character.isLowSurrogate(before[i - 1])) 2 else 1
        }
        val count = before.length - i
        if (count > 0) {
            if (!replaceMode) rememberDeleted(before.substring(i))
            ic.deleteSurroundingText(count, 0)
        }
    }

    override fun onEnter() {
        own()
        saveEmail()
        val ic = currentInputConnection ?: return
        suggestionSpace = false
        captureReplacedSelection()
        val word = currentWord()
        if (!(prefs.correctOnEnter && correctAndCommit(""))) noteWordEnd(word)
        if (word != null) learnPair()
        forgetDeleted(true)
        lastCorrection = null
        autoSpacePending = false
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else if (!continueList()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSpace() {
        own()
        val ic = currentInputConnection ?: return
        suggestionSpace = false
        captureReplacedSelection()
        val now = SystemClock.uptimeMillis()
        if (prefs.doubleSpacePeriod && isTextField && now - lastSpaceTime < 700) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString()
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0
                return
            }
        }
        activeStyle?.let {
            finishStyledWord(it)
            ic.commitText(styledSpace(it), 1)
            lastSpaceTime = now
            return
        }
        // Leerzeichen kam schon automatisch nach dem Satzzeichen
        if (autoSpacePending) {
            autoSpacePending = false
            lastSpaceTime = now
            return
        }
        val word = currentWord()
        forgetDeleted(true)
        if (correctAndCommit(" ")) {
            lastSpaceTime = now
            learnPair()
            return
        }
        noteWordEnd(word)
        lastCorrection = null
        ic.commitText(" ", 1)
        if (word != null) learnPair()
        lastSpaceTime = now
    }

    override fun onLanguageSwipe(direction: Int) {
        val langs = prefs.enabledLanguages
        if (langs.size < 2) {
            keyboard?.showMessage("Nur eine Sprache aktiv")
            return
        }
        val idx = langs.indexOf(language).coerceAtLeast(0)
        language = langs[(idx + direction + langs.size) % langs.size]
        prefs.currentLanguage = language
        keyboard?.configure(prefs, language)
        languageChanged()
        keyboard?.showMessage(language.displayName)
    }

    // ------------------------------------------------------------------
    // Formatieren
    // ------------------------------------------------------------------

    override fun onFormat(code: String) {
        own()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        when (code) {
            "off" -> setStyle(null)
            // Umdrehen geht nur am ganzen Text: Markierung, sonst alles im Feld
            // Umdrehen geht nur am ganzen Text: Markierung, sonst alles im Feld
            "upside", "mirror", "binary" -> transformWhole(code, selected)
            // Formatierten Text wieder normal machen (und Stil beenden)
            "plain" -> {
                setStyle(null)
                transformWhole(code, selected)
            }
            "bold", "italic", "strike", "underline", "sarcasm", "small", "glitch" -> {
                if (selected.isNotEmpty()) {
                    ic.commitText(Formatter.apply(code, selected), 1)
                } else {
                    setStyle(if (activeStyle == code) null else code)
                }
            }
            "md_bold", "md_strike", "md_code", "md_spoiler" -> {
                // Formatierung für Telegram und Discord
                val mark = when (code) {
                    "md_bold" -> "**"
                    "md_strike" -> "~~"
                    "md_code" -> "`"
                    else -> "||"
                }
                if (selected.isNotEmpty()) {
                    ic.commitText(mark + selected + mark, 1)
                } else {
                    // Zeichen paarweise einfügen, Cursor dazwischen
                    ic.beginBatchEdit()
                    ic.commitText(mark, 1)
                    ic.commitText(mark, 0)
                    ic.endBatchEdit()
                }
            }
            "bullet" -> startList("• ")
            "number" -> startList("1. ")
        }
    }

    private fun transformWhole(code: String, selected: String) {
        val ic = currentInputConnection ?: return
        if (selected.isNotEmpty()) {
            ic.commitText(Formatter.apply(code, selected), 1)
            return
        }
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val all = et?.text?.toString().orEmpty()
        if (all.isEmpty() || all.length > 2000) {
            keyboard?.showMessage("Erst Text schreiben oder markieren")
            return
        }
        val cursor = et!!.selectionStart.coerceIn(0, all.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(cursor, all.length - cursor)
        ic.commitText(Formatter.apply(code, all), 1)
        ic.endBatchEdit()
    }

    /** Text im aktiven Stil (SpongeBob-Schrift zählt über Tasten hinweg weiter). */
    private fun styleOf(style: String, text: String): String = if (style == "sarcasm") {
        Formatter.sarcasm(text, sarcasmCount).also { sarcasmCount += text.count(Char::isLetter) }
    } else {
        Formatter.apply(style, text)
    }

    /** Durch- und Unterstreichen läuft über Leerzeichen hinweg. */
    private fun styledSpace(style: String) =
        if (style == "strike" || style == "underline") Formatter.apply(style, " ") else " "

    private fun resetStyledWord() {
        styledPlain.setLength(0)
        styledPieces.clear()
    }

    /** Steht das formatierte Wort noch direkt vor dem Cursor (Cursor nicht woanders hin bewegt)? */
    private fun styledWordIntact(): Boolean {
        val joined = styledPieces.joinToString("")
        if (joined.isEmpty()) return false
        return currentInputConnection?.getTextBeforeCursor(joined.length, 0)?.toString() == joined
    }

    /** Formatiertes Wort durch ein anderes (im selben Stil) ersetzen. */
    private fun replaceStyledWord(style: String, word: String) {
        val ic = currentInputConnection ?: return
        val len = styledPieces.sumOf { it.length }
        if (style == "sarcasm") sarcasmCount = styledWordStart
        ic.beginBatchEdit()
        ic.deleteSurroundingText(len, 0)
        ic.commitText(styleOf(style, word), 1)
        ic.endBatchEdit()
    }

    /** Wortende im Stil: das normale Wort prüfen und bei Tippfehler formatiert ersetzen. */
    private fun finishStyledWord(style: String) {
        val plain = styledPlain.toString()
        val d = dict
        if (plain.isNotEmpty() && autocorrectOn && d != null && styledWordIntact()) {
            val s = current?.takeIf { it.typed == plain } ?: suggestNow(d, plain)
            val corr = s.correction
            if (corr != null && corr != plain) replaceStyledWord(style, corr)
        }
        resetStyledWord()
        current = null
    }

    private fun setStyle(style: String?) {
        activeStyle = style
        sarcasmCount = 0
        resetStyledWord()
        keyboard?.setStyleIndicator(style?.let { Formatter.LABELS[it] })
    }

    /** Liste beginnen: am Zeilenanfang direkt, sonst in einer neuen Zeile. */
    private fun startList(prefix: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        ic.commitText(if (before.isEmpty() || before == "\n") prefix else "\n$prefix", 1)
    }

    /**
     * Enter in einer Liste: nächster Punkt ("• " oder "2. "). Enter auf einem
     * leeren Punkt beendet die Liste. true = erledigt.
     */
    private fun continueList(): Boolean {
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return false
        val line = before.substringAfterLast('\n')
        val bullet = Regex("^(\\s*)([•\\-*–]) ").find(line)
        val number = Regex("^(\\s*)(\\d+)\\. ").find(line)
        val (prefix, next) = when {
            bullet != null -> bullet.value to bullet.value
            number != null -> number.value to "${number.groupValues[1]}${number.groupValues[2].toInt() + 1}. "
            else -> return false
        }
        if (line == prefix) {
            ic.deleteSurroundingText(line.length, 0) // leerer Punkt: Liste beenden
        } else {
            ic.commitText("\n$next", 1)
        }
        return true
    }

    // ------------------------------------------------------------------
    // Websuche
    // ------------------------------------------------------------------

    /** Sieht der Text wie eine Webadresse aus (github.com/…, www.…, https://…)? */
    private fun looksLikeUrl(t: String): Boolean =
        !t.contains(' ') && (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.") ||
            Regex("^[\\w-]+(\\.[\\w-]+)*\\.[a-zA-Z]{2,}(:\\d+)?(/\\S*)?$").matches(t))

    /**
     * Browser öffnen: markierter Link direkt, anderer Text als Suche,
     * ohne Markierung eine leere Seite (sonst einfach der Browser).
     */
    private fun runSearch(query: String) {
        val base = SEARCH_ENGINES[prefs.searchEngine] ?: SEARCH_ENGINES.getValue("google")
        val url = when {
            query.isEmpty() -> "about:blank"
            looksLikeUrl(query) -> if (query.startsWith("http")) query else "https://$query"
            else -> base + java.net.URLEncoder.encode(query, "UTF-8")
        }
        val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        requestHideSelf(0)
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(flags))
        } catch (_: Exception) {
            // Leere Seite nimmt nicht jeder Browser an: dann einfach den Browser öffnen
            try {
                startActivity(
                    android.content.Intent.makeMainSelectorActivity(
                        android.content.Intent.ACTION_MAIN, android.content.Intent.CATEGORY_APP_BROWSER
                    ).addFlags(flags)
                )
            } catch (_: Exception) {
                keyboard?.showMessage("Kein Browser gefunden")
            }
        }
    }

    override fun onMenu(section: String) {
        if (section == "resize") {
            keyboard?.startResize()
            return
        }
        if (section == "search") {
            // Markierter Text wird gesucht, sonst öffnet die Startseite der Suchmaschine
            runSearch(currentInputConnection?.getSelectedText(0)?.toString().orEmpty().trim())
            return
        }
        requestHideSelf(0)
        startActivity(
            android.content.Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, section)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun onClipboardOpen() {
        captureClip()
    }

    override fun onMoveCursor(direction: Int) {
        own()
        lastCorrection = null
        autoSpacePending = false
        sendDownUpKeyEvents(if (direction < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    /**
     * Shift auf das Wort direkt vor dem Cursor anwenden: 1 = Wort, 2 = WORT, 0 = wort.
     * Nur am Wortende; mitten im Wort und am Wortanfang wie gewohnt (nächster Buchstabe groß).
     * Ist das Wort schon groß geschrieben, macht Shift den nächsten Buchstaben groß (zusammengeschriebene Namen).
     */
    override fun onShiftChanged(state: Int): Boolean {
        own()
        if (activeStyle != null) return false
        val ic = currentInputConnection ?: return false
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) return cycleSelectionCase(selected)
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return false
        val after = ic.getTextAfterCursor(48, 0)?.toString().orEmpty()
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        val head = before.substring(i)
        if (head.isEmpty() || !head.any { it.isLetter() }) return false // Wortanfang
        // Nur am Wortende: mitten im Wort wird wie gewohnt der nächste Buchstabe groß
        if (after.isNotEmpty() && isWordChar(after[0])) return false
        val tail = ""
        val word = head
        // Wort schon groß angefangen (Baron, BaronDer): Shift gilt dem nächsten Buchstaben → BaronDerNacht
        if (state == 1 && word.first().isUpperCase() && word.any { it.isLowerCase() }) return false
        val changed = when (state) {
            1 -> word.lowercase().replaceFirstChar { it.uppercaseChar() }
            2 -> word.replace("ß", "ẞ").uppercase()
            else -> word.lowercase()
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(head.length, tail.length)
        ic.commitText(changed, 1)
        ic.endBatchEdit()
        lastCorrection = null
        current = null
        // Mehrfach Shift (Wort → WORT → wort): rückgängig führt immer zum Ausgangswort
        val start = lastWordShift?.takeIf { it.second == word }?.first ?: word
        lastWordShift = start to changed
        return true
    }

    /** Wort vor und nach der letzten Shift-Umwandlung (für baronDer). */
    private var lastWordShift: Pair<String, String>? = null

    override fun onUndoWordShift(): Boolean {
        own()
        val (original, changed) = lastWordShift ?: return false
        lastWordShift = null
        if (original == changed) return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(changed.length, 0)?.toString() ?: return false
        if (before != changed) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(changed.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        current = null
        return true
    }

    /**
     * Markierter Text: bei jedem Shift weiter Erster Groß → ALLES GROSS → alles klein.
     * Die Markierung bleibt, so lässt sich durchtippen. Ein markierter Buchstabe wird einfach groß/klein.
     */
    private fun cycleSelectionCase(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val title = text.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        val upper = text.replace("ß", "ẞ").uppercase()
        val lower = text.lowercase()
        val next = when (text) {
            title -> if (upper != title) upper else lower
            upper -> lower
            else -> if (title != text) title else upper
        }
        val et = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val start = et?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) }
        ic.beginBatchEdit()
        ic.commitText(next, 1)
        if (start != null) ic.setSelection(start, start + next.length) // Markierung behalten
        ic.endBatchEdit()
        current = null
        return true
    }

    override fun onKeyFeedback() {
        own()
        if (!prefs.vibrate) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val PUNCTUATION = ".,!?;:"
        private const val CLOSING_QUOTES = "\"“”»"
        private const val RESTORE_MIN_CHARS = 15
        private const val CLIP_OFFER_MS = 3 * 60 * 1000L
        private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024

        /** Name → Such-URL (der Begriff wird angehängt). */
        val SEARCH_ENGINES = linkedMapOf(
            "google" to "https://www.google.com/search?q=",
            "duckduckgo" to "https://duckduckgo.com/?q=",
            "ecosia" to "https://www.ecosia.org/search?q=",
            "bing" to "https://www.bing.com/search?q=",
            "startpage" to "https://www.startpage.com/do/search?q=",
        )
    }
}
