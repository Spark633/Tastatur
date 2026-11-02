package de.spark633.tastatur

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/** Der eigentliche Tastatur-Dienst, den Android in jeder App einblendet. */
class TastaturService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: Prefs
    private var keyboard: KeyboardView? = null
    private var language = Language.DE
    private var isTextField = true
    private var lastSpaceTime = 0L

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this, this)
        keyboard = v
        return v
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

        v.setEnterLabel(enterLabel(info))
        updateAutoCaps()
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
    // KeyboardView.Listener
    // ------------------------------------------------------------------

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    override fun onEnter() {
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
        keyboard?.showMessage(language.displayName)
    }

    override fun onClipboard() {
        // Vorläufig: fügt den zuletzt kopierten Text ein.
        // Der Verlauf mit angehefteten Einträgen kommt in Schritt 3.
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrEmpty()) keyboard?.showMessage("Zwischenablage ist leer")
        else currentInputConnection?.commitText(text, 1)
    }

    override fun onKeyFeedback() {
        if (!prefs.vibrate) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
