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
        if (key == Prefs.KEY_RESIZE_REQUEST && prefs.resizeRequest) {
            prefs.resizeRequest = false
            keyboard?.startResize()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        clipHistory = ClipboardHistory(this)
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

    private fun captureClip() {
        try {
            val clip = clipboard?.primaryClip ?: return
            if (clip.itemCount == 0) return
            // Passwörter aus Passwort-Managern nicht speichern
            if (clip.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            clipHistory.add(text)
            keyboard?.invalidate()
        } catch (_: Exception) {
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val v = keyboard ?: return
        language = prefs.currentLanguage
        v.configure(prefs, language)

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        if (!restarting) v.setMode(if (numeric) KeyboardView.Mode.SYMBOLS else KeyboardView.Mode.LETTERS)

        isTextField = cls == InputType.TYPE_CLASS_TEXT && variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )

        val noSuggestFlag = (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        suggestOn = isTextField && prefs.suggestions
        autocorrectOn = suggestOn && prefs.autocorrect && !noSuggestFlag &&
            variation != InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        lastCorrection = null
        languageChanged()

        v.setEnterLabel(enterLabel(info))
        updateSystemInset(window?.window?.decorView?.rootWindowInsets)
        updateAutoCaps()
        if (prefs.resizeRequest) {
            prefs.resizeRequest = false
            v.startResize()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboard?.cancelTouches()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
        updateSuggestions()
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
        val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val caps = (trimmed.isEmpty() && before.length < 3) ||
            before.endsWith("\n") ||
            (before.length > trimmed.length && trimmed.isNotEmpty() && trimmed.last() in ".!?")
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

    private fun isKnown(word: String) = ignored.contains(word.lowercase())

    private fun updateSuggestions() {
        val v = keyboard ?: return
        val d = dict
        val word = if (suggestOn) currentWord() else null
        if (word == null || d == null) {
            current = null
            suggestSeq++
            v.setSuggestions(emptyList())
            return
        }
        if (current?.typed == word) return
        val seq = ++suggestSeq
        val nb = neighbors
        val ign = HashSet(ignored)
        worker.execute {
            val s = try { d.suggest(word, nb) { ign.contains(it.lowercase()) } } catch (_: Throwable) { null }
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
        val s = current?.takeIf { it.typed == word } ?: d.suggest(word, neighbors, ::isKnown)
        val corr = s.correction ?: return false
        if (corr == word) return false
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(corr + separator, 1)
        ic.endBatchEdit()
        lastCorrection = Triple(word, corr, separator)
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
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()
        ignored.add(original.lowercase())
        current = null
        return true
    }

    // ------------------------------------------------------------------
    // KeyboardView.Listener
    // ------------------------------------------------------------------

    override fun onText(text: String) {
        if (text.length == 1 && text[0] in ".,!?;:" && correctAndCommit(text)) return
        lastCorrection = null
        currentInputConnection?.commitText(text, 1)
    }

    override fun onSuggestion(index: Int) {
        val s = current ?: return
        val word = currentWord() ?: return
        if (s.typed != word) return
        val chosen = if (index == 0 && s.correction != null) {
            ignored.add(word.lowercase()) // eigenes Wort behalten
            word
        } else {
            s.slots.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$chosen ", 1)
        ic.endBatchEdit()
        lastCorrection = null
        current = null
        lastSpaceTime = SystemClock.uptimeMillis()
    }

    override fun onDelete() {
        if (undoCorrection()) return
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
        lastCorrection = null
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
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
        if (correctAndCommit(" ")) {
            lastSpaceTime = now
            return
        }
        lastCorrection = null
        ic.commitText(" ", 1)
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

    override fun onClipboardOpen() {
        captureClip()
    }

    override fun onKeyFeedback() {
        if (!prefs.vibrate) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
