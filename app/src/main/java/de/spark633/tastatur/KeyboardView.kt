package de.spark633.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zeichnet die Vorschlagsleiste und die Tasten und wertet Tippen, Wischen
 * und langes Drücken aus.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onDelete()
        /** ⌫ gedrückt gehalten: ganzes Wort löschen. */
        fun onDeleteWord()
        /** ⌫ doppelt getippt und gehalten: alles Zusammenhängende bis zum Leerzeichen löschen. */
        fun onDeleteChunk()
        fun onEnter()
        fun onSpace()
        fun onLanguageSwipe(direction: Int)
        /** Zwischenablage wird geöffnet: aktuellen Inhalt in den Verlauf übernehmen. */
        fun onClipboardOpen()
        /** Menüpunkt hinter dem Zahnrad, z. B. "general" oder "dictionary". */
        fun onMenu(section: String)
        /** Vorschlag lange gedrückt: nicht mehr vorschlagen. */
        fun onSuggestionLongPress(index: Int)
        /** ↶ neben den Vorschlägen angetippt (fragt nach: wiederherstellen oder verwerfen). */
        fun onRestore()
        /** Formatieren: "bold", "strike", "bullet", "md_bold" … oder "off" (Stil beenden). */
        fun onFormat(code: String)
        /** Vorschlag angetippt: 0 = links, 1 = Mitte, 2 = rechts. */
        fun onSuggestion(index: Int)
        fun onKeyFeedback()
        /** Bild aus der Zwischenablage einfügen (wie normales Einfügen). */
        fun onPasteImage(name: String)
        /** Shift geändert (0 aus, 1 an, 2 Feststelltaste). true = auf das Wort am Cursor angewendet. */
        fun onShiftChanged(state: Int): Boolean
        /** Letzte Shift-Umwandlung des Wortes rückgängig machen (baron → Baron → baron). true = erledigt. */
        fun onUndoWordShift(): Boolean
        /** Cursor um ein Zeichen bewegen: -1 = links, 1 = rechts. */
        fun onMoveCursor(direction: Int)
    }

    enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, EMOTICONS, CLIPBOARD, MENU, FORMAT, NUMPAD }
    enum class Shift { OFF, ON, LOCK }

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // --- Zustand, von außen gesetzt ---
    private var language = Language.DE
    private var languageCount = 1
    private var qwertz = true
    private var clipboardRight = false
    private var langLeft = true
    private var langCodeShown = false
    private var rowHeight = 54 * dp
    private var enterLabel = "↵"
    private var systemInset = 0
    private var bottomPad = 0f
    private var prefs: Prefs? = null
    var clipHistory: ClipboardHistory? = null

    private var mode = Mode.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var lastDeleteUp = 0L
    private var chunkDeleteOn = true
    private var deleteRepeatMs = 420L
    private var emoticonPage = 0
    private var formatPage = 0
    private var numpadKind = Layouts.NumpadKind.NUMBER

    /** Reines Zahlenfeld: großer Ziffernblock. */
    fun showNumpad(kind: Layouts.NumpadKind) {
        numpadKind = kind
        setMode(Mode.NUMPAD)
    }
    private var emoticonList: List<String> = Layouts.EMOTICONS
    private var message: String? = null
    private var slots: List<String> = emptyList()
    private var restoreVisible = false
    private var styleLabel: String? = null

    /** Rückfrage in der Leiste (z. B. Vorschlag entfernen). */
    private var confirmText: String? = null
    private var confirmAction: (() -> Unit)? = null
    private var confirmNo: (() -> Unit)? = null
    private var confirmYesLabel = "Entfernen"
    private val confirmYesRect = RectF()
    private var lettersMode = Mode.LETTERS

    // Zwischenablage-Ansicht
    private var clipScroll = 0f
    private var clearArmedUntil = 0L
    private val clipGap = 4 * dp
    private var clipColumns = 2
    private var clipLines = 3
    private var clipMenuFor: ClipboardHistory.Entry? = null

    // Größen-Modus
    private var resizing = false
    private var resizeStartRaw = 0f
    private var resizeStartHeight = 0f

    private val barHeight = 44 * dp
    private val gapH = 3 * dp
    private val gapV = 4 * dp
    private val swipeThreshold = 22 * dp
    private val sideThreshold = 26 * dp
    private val spaceSwipeThreshold = 40 * dp

    private class KeyRect(val key: Key, val rect: RectF, val row: Int)

    private var keyRects = listOf<KeyRect>()
    private val clipRect = RectF()
    private val settingsRect = RectF()

    // --- Farben ---
    private val bgPaint = Paint().apply { color = Color.rgb(0x1E, 0x27, 0x2B) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x40, 255, 255, 255) }
    private val funcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x24, 255, 255, 255) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x80, 255, 255, 255) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4D, 0xB6, 0xAC) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = sp(23f)
    }
    private val funcLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = sp(17f)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(0xC8, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = sp(12f)
    }
    private val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = sp(17f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.8f * dp
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x37, 0x47, 0x4F) }

    // ------------------------------------------------------------------
    // Konfiguration
    // ------------------------------------------------------------------

    fun configure(prefs: Prefs, language: Language) {
        this.prefs = prefs
        this.language = language
        languageCount = prefs.enabledLanguages.size
        qwertz = prefs.isQwertz(language)
        clipboardRight = prefs.clipboardRight
        langLeft = prefs.langLeft
        langCodeShown = prefs.isLangCodeShown(language)
        clipColumns = prefs.clipColumns.coerceIn(1, 2)
        clipLines = prefs.clipLines.coerceIn(1, 4)
        clipLayouts.clear()
        emoticonList = prefs.emoticonsOrdered
        emoticonPage = emoticonPage.coerceIn(0, Layouts.emoticonPages(emoticonList) - 1)
        applyTheme(prefs)
        rowHeight = prefs.rowHeightDp * dp
        bottomPad = prefs.bottomPaddingDp * dp
        requestLayout()
        layoutKeys()
        invalidate()
    }

    // --- Aussehen ---
    private var keyRadius = 6 * dp
    private var keyPreview = true
    private var spaceCursor = true
    private var showHints = true
    private var themeLight = false
    private var bgBitmap: android.graphics.Bitmap? = null
    private var bgVersionLoaded = -1L
    private var bgPos = 0.5f
    private var bgPosX = 0.5f
    private var bgZoom = 1f
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint()

    private fun withAlpha(color: Int, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** Farbschema, Deckkraft, Schriftgröße und Hintergrundbild aus den Einstellungen übernehmen. */
    private fun applyTheme(prefs: Prefs) {
        val t = Theme.byId(prefs.theme)
        themeLight = t.light
        val op = prefs.keyOpacity.coerceIn(0, 100) / 100f
        val keyAlpha = if (t.light) 0.5f + op * 0.5f else op
        val colors = Theme.keyColors(t, prefs)
        bgPaint.color = t.background
        keyPaint.color = colors.key
        funcPaint.color = colors.function
        pressedPaint.color = if (t.light) withAlpha(Color.rgb(0x90, 0x9A, 0xA4), 0.9f)
        else withAlpha(Color.WHITE, min(1f, keyAlpha + 0.3f))
        accentPaint.color = t.accent
        accentTextPaint.color = t.accent
        for (p in listOf(labelPaint, funcLabelPaint, barTextPaint, boldBarPaint, iconPaint)) p.color = colors.text
        clipTextPaint.color = Color.WHITE // Zwischenablage: immer weiß auf dunklen Kacheln
        hintPaint.color = colors.hint
        // Dunkle Schrift: Auswahl- und Vorschau-Kästen hell, damit sie lesbar bleiben
        popupPaint.color = if (Color.luminance(colors.text) < 0.5f) Color.rgb(0xF2, 0xF2, 0xF2) else t.popup

        val scale = prefs.textScale.coerceIn(70, 150) / 100f
        labelPaint.textSize = sp(23f) * scale
        funcLabelPaint.textSize = sp(17f) * scale
        hintPaint.textSize = sp(12f) * scale
        keyRadius = prefs.cornerRadiusDp.coerceIn(0, 24) * dp
        showHints = prefs.showHints
        keyPreview = prefs.keyPreview
        chunkDeleteOn = prefs.chunkDelete
        deleteRepeatMs = prefs.deleteRepeatMs.coerceIn(150, 1000).toLong()
        spaceCursor = prefs.spaceCursor

        bgPos = prefs.bgPosition.coerceIn(0, 100) / 100f
        bgPosX = prefs.bgPositionX.coerceIn(0, 100) / 100f
        bgZoom = prefs.bgZoom.coerceIn(100, 300) / 100f
        dimPaint.color = withAlpha(if (t.light) Color.WHITE else Color.BLACK, prefs.bgDim.coerceIn(0, 90) / 100f)
        if (prefs.bgVersion != bgVersionLoaded) {
            bgVersionLoaded = prefs.bgVersion
            bgBitmap = if (prefs.bgVersion == 0L) null else try { Background.load(context) } catch (_: Throwable) { null }
        }
    }

    /** Bild füllt die Tastatur (wie "centerCrop"), senkrechter Ausschnitt einstellbar. */
    private fun drawBackground(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val bmp = bgBitmap ?: return
        val src = Background.sourceRect(bmp.width, bmp.height, width.toFloat(), height.toFloat(), bgZoom, bgPosX, bgPos)
        canvas.drawBitmap(bmp, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
    }

    fun setMode(m: Mode) {
        mode = m
        if (m == Mode.CLIPBOARD) {
            clipScroll = 0f
            clearArmedUntil = 0L
            clipMenuFor = null
        }
        layoutKeys()
        invalidate()
    }

    fun setEnterLabel(label: String) {
        enterLabel = label
        invalidate()
    }

    /** Automatische Großschreibung am Satzanfang. Feststelltaste bleibt unberührt. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.LOCK || shiftAppliedToWord) return
        val new = if (on) Shift.ON else Shift.OFF
        if (new != shift) {
            shift = new
            invalidate()
        }
    }

    /** ↶-Knopf rechts neben den Vorschlägen ein-/ausblenden. */
    fun setRestoreVisible(on: Boolean) {
        if (on != restoreVisible) {
            restoreVisible = on
            invalidate()
        }
    }

    fun setSuggestions(list: List<String>) {
        if (list != slots) {
            slots = list
            invalidate()
        }
    }

    /** Rückfrage in der Leiste. [no] läuft bei ✕ bzw. Tippen daneben in der Leiste. */
    fun askConfirm(text: String, yes: () -> Unit, yesLabel: String = "Entfernen", no: (() -> Unit)? = null) {
        confirmText = text
        confirmAction = yes
        confirmNo = no
        confirmYesLabel = yesLabel
        invalidate()
    }

    private fun clearConfirm() {
        if (confirmText != null) {
            confirmText = null
            confirmAction = null
            confirmNo = null
            invalidate()
        }
    }

    private fun drawConfirm(canvas: Canvas, text: String) {
        val btnW = max(110 * dp, barTextPaint.measureText(confirmYesLabel) + 28 * dp)
        confirmYesRect.set(width - btnW - 6 * dp, 6 * dp, width - 6 * dp, barHeight - 6 * dp)
        canvas.drawRoundRect(confirmYesRect, confirmYesRect.height() / 2, confirmYesRect.height() / 2, accentPaint)
        canvas.drawText(confirmYesLabel, confirmYesRect.centerX(), centerY(barTextPaint, confirmYesRect.centerY()), barTextPaint)
        val avail = confirmYesRect.left - 16 * dp
        val p = fitPaint(barTextPaint, "$text   ✕", avail)
        canvas.drawText("$text   ✕", avail / 2f + 8 * dp, centerY(p, barHeight / 2f), p)
    }

    /** Aktiver Schreibstil, wird in der Leiste angezeigt (antippen beendet ihn). */
    fun setStyleIndicator(label: String?) {
        styleLabel = label
        invalidate()
    }

    fun showMessage(text: String) {
        message = text
        invalidate()
        removeCallbacks(clearMessage)
        postDelayed(clearMessage, 1500)
    }

    private val clearMessage = Runnable { message = null; invalidate() }

    private fun inSubPage() = mode == Mode.EMOTICONS || mode == Mode.CLIPBOARD || mode == Mode.FORMAT

    /** Android-Zurück: aus Menüs und Unterseiten zurück zu den Buchstaben. true = erledigt. */
    fun handleBack(): Boolean {
        if (resizing) {
            cancelResize() // nur „Fertig“ speichert
            return true
        }
        // Sonst schließt Zurück die Tastatur sofort, auch aus Menüs (beim nächsten Öffnen: Buchstaben)
        return false
    }

    /** Beim Schließen: Unterseiten verlassen, damit beim nächsten Öffnen die Buchstaben kommen. */
    fun leaveSubPage() {
        if (resizing) cancelResize()
        clearConfirm()
        if (mode != Mode.LETTERS && mode != Mode.NUMPAD) setMode(Mode.LETTERS)
    }

    fun cancelTouches() {
        touches.values.forEach { cancelTimers(it) }
        touches.clear()
        invalidate()
    }

    // ------------------------------------------------------------------
    // Maße
    // ------------------------------------------------------------------

    /** Höhe der Systemknöpfe unter der Tastatur, vom Dienst ermittelt. */
    fun setSystemInset(px: Int) {
        if (px != systemInset) {
            systemInset = px
            requestLayout()
            invalidate()
        }
    }

    /** Größe durch Ziehen anpassen, mit Live-Vorschau. */
    fun startResize() {
        resizing = true
        if (mode == Mode.CLIPBOARD || mode == Mode.EMOTICONS) setMode(Mode.LETTERS)
        cancelTouches()
        invalidate()
    }

    private fun finishResize() {
        resizing = false
        prefs?.let {
            it.rowHeightDp = (rowHeight / dp).toInt()
            it.bottomPaddingDp = (bottomPad / dp).toInt()
        }
        showMessage("Größe gespeichert")
    }

    /** Größen-Modus ohne „Fertig“ verlassen: alte Größe zurück. */
    private fun cancelResize() {
        resizing = false
        prefs?.let {
            rowHeight = it.rowHeightDp * dp
            bottomPad = it.bottomPaddingDp * dp
        }
        requestLayout()
        layoutKeys()
        showMessage("Größe nicht gespeichert")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (barHeight + 4 * rowHeight + 4 * dp + bottomPad).toInt() + systemInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun rows(): List<List<Key>> = when (mode) {
        Mode.LETTERS -> Layouts.letters(qwertz, language)
        Mode.SYMBOLS -> Layouts.symbols()
        Mode.SYMBOLS2 -> Layouts.symbols2()
        Mode.EMOTICONS -> Layouts.emoticons(emoticonPage, emoticonList)
        Mode.CLIPBOARD -> Layouts.clipboard()
        Mode.MENU -> Layouts.menu()
        Mode.FORMAT -> Layouts.format(formatPage)
        Mode.NUMPAD -> Layouts.numpad(numpadKind)
    }

    private fun layoutKeys() {
        val w = width.toFloat()
        if (w <= 0f) return
        val unit = w / 10f
        val list = ArrayList<KeyRect>()
        rows().forEachIndexed { r, row ->
            val sum = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (10f - sum) / 2f * unit
            val top = barHeight + r * rowHeight
            for (k in row) {
                val kw = k.width * unit
                list += KeyRect(k, RectF(x + gapH, top + gapV, x + kw - gapH, top + rowHeight - gapV), r)
                x += kw
            }
        }
        keyRects = list
        val cs = barHeight
        if (clipboardRight) {
            clipRect.set(w - cs, 0f, w, cs)
            settingsRect.set(0f, 0f, cs, cs)
        } else {
            clipRect.set(0f, 0f, cs, cs)
            settingsRect.set(w - cs, 0f, w, cs)
        }
    }

    private fun keyAt(x: Float, y: Float): KeyRect? {
        if (y < barHeight) return null
        val row = ((y - barHeight) / rowHeight).toInt().coerceIn(0, 3)
        val inRow = keyRects.filter { it.row == row }
        if (inRow.isEmpty()) return null
        inRow.firstOrNull { x >= it.rect.left - gapH && x <= it.rect.right + gapH }?.let { return it }
        return inRow.minByOrNull { abs(it.rect.centerX() - x) }
    }

    // ------------------------------------------------------------------
    // Zeichnen
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawBar(canvas)
        val pressed = touches.values.mapNotNull { it.target }.toSet()
        for (kr in keyRects) drawKey(canvas, kr, kr in pressed)
        if (mode == Mode.CLIPBOARD) drawClipboard(canvas)
        touches.values.firstOrNull { it.popup != null }?.let { drawPopup(canvas, it) }
        if (keyPreview) touches.values.filter { it.popup == null }.forEach { drawKeyPreview(canvas, it) }
        if (resizing) drawResizeOverlay(canvas)
    }

    // --- Zwischenablage ---

    private val clipArea: RectF get() = RectF(0f, barHeight, width.toFloat(), barHeight + 3 * rowHeight)

    /** Dunkle, fast deckende Kacheln, damit die Einträge auf jedem Hintergrund lesbar sind. */
    private val clipTilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xE0, 0x1C, 0x21, 0x25) }

    private val clipDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0xA0, 0, 0, 0) }

    private val clipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = sp(15f) }
    private val clipTileHeight: Float get() = 16 * dp + clipLines * clipTextPaint.fontSpacing
    private val clipMenuZone = 34 * dp

    /** Kacheln in 1 oder 2 Spalten, wie in den Einstellungen gewählt. */
    private fun clipRects(): List<Pair<ClipboardHistory.Entry, RectF>> {
        val entries = clipHistory?.entries ?: return emptyList()
        val area = clipArea
        val colW = (area.width() - clipGap) / clipColumns
        val h = clipTileHeight
        return entries.mapIndexed { i, e ->
            val left = area.left + clipGap + (i % clipColumns) * colW
            val top = area.top + clipGap + (i / clipColumns) * (h + clipGap) - clipScroll
            e to RectF(left, top, left + colW - clipGap, top + h)
        }
    }

    private fun maxClipScroll(): Float {
        val n = clipHistory?.entries?.size ?: 0
        val rows = (n + clipColumns - 1) / clipColumns
        val content = clipGap + rows * (clipTileHeight + clipGap)
        return max(0f, content - clipArea.height())
    }

    private val clipLayouts = HashMap<String, android.text.StaticLayout>()

    // Vorschaubilder für Bild-Einträge (verkleinert geladen und zwischengespeichert)
    private val clipThumbs = HashMap<String, android.graphics.Bitmap?>()
    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun clipThumb(name: String): android.graphics.Bitmap? = clipThumbs.getOrPut(name) {
        try {
            val f = java.io.File(ClipboardHistory.imageDir(context), name)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 360) sample *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) { null }
    }

    private fun clipLayout(text: String, width: Int): android.text.StaticLayout {
        val key = "$width|$text"
        return clipLayouts.getOrPut(key) {
            android.text.StaticLayout.Builder.obtain(text, 0, text.length, clipTextPaint, max(1, width))
                .setMaxLines(clipLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
    }

    private fun drawClipboard(canvas: Canvas) {
        val area = clipArea
        val rects = clipRects()
        if (rects.isEmpty()) {
            canvas.drawText(
                "Noch nichts kopiert", area.centerX(), centerY(barTextPaint, area.centerY()), barTextPaint
            )
            return
        }
        canvas.save()
        canvas.clipRect(area)
        val radius = 8 * dp
        for ((e, base) in rects) {
            val r = RectF(base)
            if (r.bottom < area.top || r.top > area.bottom) continue

            // Menü bei einer Kachel offen: die anderen abdunkeln
            val dimOthers = clipMenuFor != null && e !== clipMenuFor
            if (e === clipMenuFor) {
                // Aktionen: links anheften/lösen, rechts löschen
                val half = r.width() / 2f
                val left = RectF(r.left, r.top, r.left + half - 2 * dp, r.bottom)
                val right = RectF(r.left + half + 2 * dp, r.top, r.right, r.bottom)
                canvas.drawRoundRect(left, radius, radius, accentPaint)
                canvas.drawRoundRect(right, radius, radius, popupPaint)
                canvas.drawText(if (e.pinned) "☆ Lösen" else "★ Anheften", left.centerX(),
                    centerY(barTextPaint, left.centerY()), barTextPaint)
                canvas.drawText("✕ Löschen", right.centerX(), centerY(barTextPaint, right.centerY()), barTextPaint)
                continue
            }

            canvas.drawRoundRect(r, radius, radius, clipTilePaint)
            if (e.pinned) canvas.drawRoundRect(RectF(r.left, r.top, r.left + 4 * dp, r.bottom), 2 * dp, 2 * dp, accentPaint)
            val img = e.image
            if (img != null) {
                // Bild: Vorschau füllt die Kachel (abgerundet)
                clipThumb(img)?.let { bmp ->
                    canvas.save()
                    val path = android.graphics.Path().apply { addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW) }
                    canvas.clipPath(path)
                    val src = Background.sourceRect(bmp.width, bmp.height, r.width(), r.height(), 1f, 0.5f, 0.5f)
                    canvas.drawBitmap(bmp, src, r, thumbPaint)
                    canvas.restore()
                } ?: canvas.drawText("🖼", r.centerX(), centerY(labelPaint, r.centerY()), labelPaint)
            } else {
                val textW = (r.width() - 14 * dp - clipMenuZone).toInt()
                val layout = clipLayout(e.text.trim(), textW)
                canvas.save()
                canvas.translate(r.left + 10 * dp, r.top + 8 * dp)
                layout.draw(canvas)
                canvas.restore()
            }
            // ⋮ oben rechts
            val mx = r.right - clipMenuZone / 2f
            val my = r.top + 14 * dp
            for (k in -1..1) canvas.drawCircle(mx, my + k * 5 * dp, 1.8f * dp, clipTextPaint)
            if (e.pinned) canvas.drawText("★", mx, r.bottom - 8 * dp, accentTextPaint.apply { textSize = sp(14f) })
            if (dimOthers) canvas.drawRoundRect(r, radius, radius, clipDimPaint)
        }
        canvas.restore()
    }

    private val accentTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x4D, 0xB6, 0xAC); textAlign = Paint.Align.CENTER; textSize = sp(14f)
    }

    private fun clipEntryAt(x: Float, y: Float) = clipRects().firstOrNull { it.second.contains(x, y) }

    private fun clipTap(x: Float, y: Float) {
        val hist = clipHistory ?: return
        val hit = clipEntryAt(x, y)
        val menuEntry = clipMenuFor
        if (menuEntry != null) {
            clipMenuFor = null
            if (hit != null && hit.first === menuEntry) {
                val r = hit.second
                if (x < r.centerX()) hist.togglePin(menuEntry)
                else askConfirm(if (menuEntry.image != null) "Bild löschen?" else "Eintrag löschen?", yes = {
                    deleteClip(menuEntry)
                    invalidate()
                }, yesLabel = "Löschen")
            }
            invalidate()
            return
        }
        val (entry, r) = hit ?: return
        if (x > r.right - clipMenuZone && y < r.top + clipMenuZone) {
            clipMenuFor = entry
        } else {
            val img = entry.image
            if (img != null) listener.onPasteImage(img) else listener.onText(entry.text)
            setMode(lettersMode) // nach dem Einfügen zurück zur Tastatur
        }
        invalidate()
    }

    private fun deleteClip(e: ClipboardHistory.Entry) {
        clipHistory?.remove(e)
        clipScroll = clipScroll.coerceIn(0f, maxClipScroll())
        showMessage("Gelöscht")
    }

    private fun clipClear() {
        askConfirm("Alles außer Angeheftetem löschen?", yes = {
            clipHistory?.clearUnpinned()
            clipScroll = 0f
            showMessage("Geleert – angeheftete bleiben")
        }, yesLabel = "Leeren")
    }

    // --- Größen-Modus ---

    private val resizeButtons: Triple<RectF, RectF, RectF>
        get() {
            val w = width / 3f
            return Triple(RectF(0f, 0f, w, barHeight), RectF(w, 0f, 2 * w, barHeight), RectF(2 * w, 0f, 3 * w, barHeight))
        }

    private val overlayPaint = Paint().apply { color = Color.argb(0xB0, 0, 0, 0) }

    private fun drawResizeOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val (minus, done, plus) = resizeButtons
        canvas.drawText("− Abstand", minus.centerX(), centerY(barTextPaint, minus.centerY()), barTextPaint)
        canvas.drawRoundRect(RectF(done.left + 8 * dp, done.top + 6 * dp, done.right - 8 * dp, done.bottom - 6 * dp),
            8 * dp, 8 * dp, accentPaint)
        canvas.drawText("Fertig ✓", done.centerX(), centerY(barTextPaint, done.centerY()), barTextPaint)
        canvas.drawText("Abstand +", plus.centerX(), centerY(barTextPaint, plus.centerY()), barTextPaint)
        val cy = barHeight + 2 * rowHeight
        canvas.drawText("↕  Nach oben / unten ziehen", width / 2f, cy - 14 * dp, barTextPaint)
        canvas.drawText(
            "Tastenhöhe ${(rowHeight / dp).toInt()} dp  ·  Abstand unten ${(bottomPad / dp).toInt()} dp",
            width / 2f, cy + 14 * dp, hintPaint
        )
    }

    private fun resizeTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (minus, done, plus) = resizeButtons
                when {
                    done.contains(e.x, e.y) -> { finishResize(); return }
                    minus.contains(e.x, e.y) -> bottomPad = max(0f, bottomPad - 4 * dp)
                    plus.contains(e.x, e.y) -> bottomPad = min(80 * dp, bottomPad + 4 * dp)
                }
                resizeStartRaw = e.rawY
                resizeStartHeight = rowHeight
                requestLayout()
            }
            MotionEvent.ACTION_MOVE -> {
                // Rohkoordinaten, weil sich die Tastatur beim Ziehen selbst verschiebt
                val dy = e.rawY - resizeStartRaw
                rowHeight = (resizeStartHeight - dy / 4f).coerceIn(36 * dp, 90 * dp)
                requestLayout()
            }
        }
        layoutKeys()
        invalidate()
    }

    private fun drawBar(canvas: Canvas) {
        confirmText?.let {
            drawConfirm(canvas, it)
            return
        }
        // Zwischenablage-Symbol
        val cx = clipRect.centerX()
        val cy = clipRect.centerY()
        val bw = 8 * dp
        val bh = 10 * dp
        canvas.drawRoundRect(cx - bw, cy - bh, cx + bw, cy + bh, 2 * dp, 2 * dp, iconPaint)
        canvas.drawRect(cx - 4 * dp, cy - bh - 2 * dp, cx + 4 * dp, cy - bh + 3 * dp, iconPaint)
        canvas.drawLine(cx - 4 * dp, cy - 2 * dp, cx + 4 * dp, cy - 2 * dp, iconPaint)
        canvas.drawLine(cx - 4 * dp, cy + 3 * dp, cx + 4 * dp, cy + 3 * dp, iconPaint)

        if (mode == Mode.CLIPBOARD) canvas.drawCircle(cx, cy, barHeight * 0.42f, funcPaint)
        if (mode == Mode.MENU) canvas.drawCircle(settingsRect.centerX(), settingsRect.centerY(), barHeight * 0.42f, funcPaint)
        drawGear(canvas, settingsRect.centerX(), settingsRect.centerY())
        if (showStyleStop) {
            val r = styleStopRect
            val pill = RectF(r.left + 3 * dp, r.top + 9 * dp, r.right - 3 * dp, r.bottom - 9 * dp)
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, accentPaint)
            styleStopPaint.textSize = sp(14f)
            canvas.drawText("✎✕", pill.centerX(), centerY(styleStopPaint, pill.centerY()), styleStopPaint)
        }
        if (showLang && mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT) {
            val r = langRect
            langPaint.color = hintPaint.color
            langPaint.textSize = sp(12f)
            langPaint.isFakeBoldText = true
            canvas.drawText(language.code.uppercase(), r.centerX(), centerY(langPaint, r.centerY()), langPaint)
        }
        if (restoreVisible && mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT) {
            val r = restoreRect
            canvas.drawCircle(r.centerX(), r.centerY(), barHeight * 0.38f, accentPaint)
            canvas.drawText("↶", r.centerX(), centerY(barTextPaint, r.centerY()), barTextPaint)
        }
        val text = message ?: when (mode) {
            Mode.CLIPBOARD -> "Zwischenablage"
            Mode.MENU -> "Einstellungen"
            Mode.FORMAT -> "Text markieren oder Stil wählen"
            // Stil aktiv: beim Tippen eines Wortes Vorschläge, sonst der Hinweis zum Beenden
            else -> if (slots.any { it.isNotEmpty() }) null else styleLabel?.let { "✎ $it aktiv" }
        }
        if (text != null) {
            canvas.drawText(text, width / 2f, centerY(barTextPaint, barHeight / 2f), barTextPaint)
            return
        }
        // Vorschläge: Mitte fett (wird beim Leerzeichen eingesetzt)
        val rects = slotRects()
        slots.forEachIndexed { i, word ->
            if (word.isEmpty() || i >= rects.size) return@forEachIndexed
            val r = rects[i]
            val base = if (i == 1) boldBarPaint else barTextPaint
            val p = fitPaint(base, word, r.width() - 8 * dp)
            canvas.drawText(word, r.centerX(), centerY(p, r.centerY()), p)
        }
    }

    private val boldBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = sp(20f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Drei gleich breite Felder zwischen Zwischenablage-Knopf und gegenüberliegendem Rand. */
    private val langWidth = 30 * dp

    /** Sprachkürzel: je Sprache abschaltbar. */
    private val showLang: Boolean get() = langCodeShown

    /** Formatierung aktiv: eigener Knopf zum Beenden neben den Vorschlägen. */
    private val showStyleStop: Boolean
        get() = styleLabel != null && mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT
    private val styleStopWidth = 44 * dp

    private val styleStopRect: RectF get() {
        val right = width - barHeight - (if (restoreVisible) barHeight else 0f) -
            (if (showLang && !langLeft) langWidth else 0f)
        return RectF(right - styleStopWidth, 0f, right, barHeight)
    }

    private val styleStopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    /** Drei gleich breite Felder zwischen den Knöpfen; links ggf. Sprachkürzel, rechts ggf. ✎✕ und ↶. */
    private fun slotRects(): List<RectF> {
        val cs = barHeight
        val left = cs + if (showLang && langLeft) langWidth else 0f
        val right = width - cs - (if (restoreVisible) cs else 0f) - (if (showStyleStop) styleStopWidth else 0f) -
            (if (showLang && !langLeft) langWidth else 0f)
        val w = (right - left) / 3f
        return (0 until 3).map { RectF(left + it * w, 0f, left + (it + 1) * w, barHeight) }
    }

    /** Kleines Sprachkürzel (EN/NL) links oder rechts neben den Vorschlägen, nur zur Anzeige. */
    private val langRect: RectF get() {
        if (langLeft) return RectF(barHeight, 0f, barHeight + langWidth, barHeight)
        val right = width - barHeight - if (restoreVisible) barHeight else 0f
        return RectF(right - langWidth, 0f, right, barHeight)
    }

    private val langPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** ↶ sitzt rechts neben den Vorschlägen, vor dem rechten Knopf. */
    private val restoreRect: RectF get() = RectF(width - 2 * barHeight, 0f, width - barHeight, barHeight)

    /** Zahnrad: Ring mit acht Zähnen. */
    private fun drawGear(canvas: Canvas, cx: Float, cy: Float) {
        val inner = 5.5f * dp
        val outer = 9f * dp
        val tooth = 11.5f * dp
        canvas.drawCircle(cx, cy, inner, iconPaint)
        canvas.drawCircle(cx, cy, outer, iconPaint)
        val old = iconPaint.strokeWidth
        iconPaint.strokeWidth = 3.2f * dp
        for (k in 0 until 8) {
            val a = Math.PI * k / 4
            val c = Math.cos(a).toFloat()
            val s = Math.sin(a).toFloat()
            canvas.drawLine(cx + c * outer, cy + s * outer, cx + c * tooth, cy + s * tooth, iconPaint)
        }
        iconPaint.strokeWidth = old
    }

    private fun centerY(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f

    /** Großschreiben ohne "SS": Feststelltaste gibt ẞ, einfaches Shift lässt ß stehen. */
    private fun upper(t: String) = when {
        t != "ß" -> t.uppercase()
        shift == Shift.LOCK -> "ẞ"
        else -> t
    }

    private fun displayText(k: Key): String {
        if (!k.cased || shift == Shift.OFF || k.text == "ß") return k.text
        return k.text.uppercase()
    }

    private fun drawKey(canvas: Canvas, kr: KeyRect, pressed: Boolean) {
        val k = kr.key
        val r = kr.rect
        val radius = keyRadius
        val paint = when {
            pressed -> pressedPaint
            k.action == Action.SHIFT && shift == Shift.LOCK -> accentPaint
            k.isFunction -> funcPaint
            else -> keyPaint
        }
        canvas.drawRoundRect(r, radius, radius, paint)

        when (k.action) {
            Action.CHAR -> {
                val text = displayText(k)
                val p = fitPaint(labelPaint, text, r.width() - 6 * dp)
                val hasUp = k.up != null && showHints
                val hasDown = k.down != null && showHints
                var offset = when {
                    hasUp && hasDown -> -1 * dp
                    hasUp -> 5 * dp
                    hasDown -> -5 * dp
                    else -> 0f
                }
                // Punkt und Komma sitzen unten in der Zeile: etwas anheben
                if (text == "." || text == ",") offset -= 7 * dp
                canvas.drawText(text, r.centerX(), centerY(p, r.centerY() + offset), p)
                if (showHints) {
                    k.up?.let { canvas.drawText(it, r.centerX(), r.top + hintPaint.textSize + 1 * dp, hintPaint) }
                    k.down?.let { canvas.drawText(it, r.centerX(), r.bottom - 4 * dp, hintPaint) }
                }
                // Symbolseiten: Punkt oben rechts = gedrückt halten für mehr Zeichen
                if ((mode == Mode.SYMBOLS || mode == Mode.SYMBOLS2) && k.longPress.isNotEmpty()) {
                    canvas.drawCircle(r.right - 6 * dp, r.top + 6 * dp, 2.2f * dp, hintPaint)
                }
            }
            Action.SPACE -> {
                val name = when {
                    mode == Mode.FORMAT -> k.label // Seitenanzeige "1 / 2"
                    languageCount > 1 && mode != Mode.EMOTICONS -> "‹  ${language.displayName}  ›"
                    else -> language.displayName
                }
                canvas.drawText(name, r.centerX(), centerY(hintPaint, r.centerY()), hintPaint)
            }
            Action.SHIFT -> {
                val label = when (shift) {
                    Shift.OFF -> "⇧"
                    Shift.ON -> "⬆"
                    Shift.LOCK -> "⇪"
                }
                canvas.drawText(label, r.centerX(), centerY(labelPaint, r.centerY()), labelPaint)
            }
            Action.ENTER -> canvas.drawText(enterLabel, r.centerX(), centerY(labelPaint, r.centerY()), labelPaint)
            Action.DELETE -> canvas.drawText(k.label, r.centerX(), centerY(labelPaint, r.centerY()), labelPaint)
            else -> {
                val p = fitPaint(funcLabelPaint, k.label, r.width() - 8 * dp)
                canvas.drawText(k.label, r.centerX(), centerY(p, r.centerY()), p)
            }
        }
    }

    private val fitPaintCache = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Verkleinert die Schrift, falls der Text (z. B. ein langes Emoticon) nicht passt. */
    private fun fitPaint(base: Paint, text: String, maxWidth: Float): Paint {
        val w = base.measureText(text)
        if (w <= maxWidth) return base
        fitPaintCache.set(base)
        fitPaintCache.textSize = base.textSize * maxWidth / w
        return fitPaintCache
    }

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Großer Buchstabe über dem Finger (beim Wischen das Zeichen, das gleich kommt). */
    private fun drawKeyPreview(canvas: Canvas, t: Touch) {
        val kr = t.target ?: return
        val k = kr.key
        if (k.action != Action.CHAR || mode == Mode.EMOTICONS) return
        val text = when {
            t.swipe == 1 && k.up != null -> k.up
            t.swipe == -1 && k.down != null -> k.down
            else -> displayText(k)
        }
        if (text.length > 3) return
        val r = kr.rect
        val w = max(r.width() * 1.25f, 44 * dp)
        val h = rowHeight * 1.05f
        var left = r.centerX() - w / 2f
        left = left.coerceIn(0f, width - w)
        val top = max(0f, r.top - h + 4 * dp)
        val box = RectF(left, top, left + w, top + h - 8 * dp)
        canvas.drawRoundRect(box, keyRadius + 4 * dp, keyRadius + 4 * dp, popupPaint)
        previewPaint.color = labelPaint.color
        previewPaint.textSize = labelPaint.textSize * 1.35f
        canvas.drawText(text, box.centerX(), centerY(previewPaint, box.centerY()), previewPaint)
    }

    private fun popupRects(t: Touch): List<RectF> {
        val options = t.popup ?: return emptyList()
        val key = t.target?.rect ?: return emptyList()
        val itemW = max(key.width(), 36 * dp)
        val itemH = rowHeight * 0.95f
        val total = itemW * options.size
        var left = key.centerX() - itemW / 2f
        left = min(left, width - total).coerceAtLeast(0f)
        val top = max(0f, key.top - itemH - 2 * dp)
        return options.indices.map { i -> RectF(left + i * itemW, top, left + (i + 1) * itemW, top + itemH) }
    }

    private fun drawPopup(canvas: Canvas, t: Touch) {
        val options = t.popup ?: return
        val rects = popupRects(t)
        if (rects.isEmpty()) return
        val all = RectF(rects.first().left, rects.first().top, rects.last().right, rects.last().bottom)
        canvas.drawRoundRect(all, 8 * dp, 8 * dp, popupPaint)
        options.forEachIndexed { i, opt ->
            val r = rects[i]
            if (i == t.popupIndex) canvas.drawRoundRect(r, 8 * dp, 8 * dp, accentPaint)
            val text = if (t.target?.key?.cased == true && shift != Shift.OFF) upper(opt) else opt
            canvas.drawText(text, r.centerX(), centerY(labelPaint, r.centerY()), labelPaint)
        }
    }

    // ------------------------------------------------------------------
    // Berührungen
    // ------------------------------------------------------------------

    private inner class Touch(val id: Int, val target: KeyRect?, val onBar: Boolean, val x0: Float, val y0: Float) {
        var lastX = x0
        var lastY = y0
        var swipe = 0 // 1 = hoch, -1 = runter
        var popup: List<String>? = null
        var popupIndex = 0
        var languageSwitched = false
        val downTime = SystemClock.uptimeMillis()
        var cursorMode = false
        var cursorLastX = 0f
        var inClipList = false
        var barLong = false
        var dragging = false
        var scrollStart = 0f
        val longPress = Runnable { startPopup(this) }
        /** „Allgemein“ im Zahnrad-Menü gedrückt halten: Hauptseite der Einstellungen. */
        var menuHeld = false
        val menuHold = Runnable {
            menuHeld = true
            listener.onKeyFeedback()
            setMode(Mode.LETTERS)
            listener.onMenu("main")
        }
        var chunkDelete = false
        /** ⌫ hat nur die Shift-Umwandlung des Wortes zurückgenommen. */
        var shiftUndone = false
        val repeat: Runnable = object : Runnable {
            override fun run() {
                if (chunkDelete) listener.onDeleteChunk() else listener.onDeleteWord()
                postDelayed(this, deleteRepeatMs)
            }
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (resizing) {
            resizeTouch(e)
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pointerUp(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        // Schnelles Tippen: eine noch gedrückte Buchstabentaste wird sofort
        // abgeschlossen, damit die Reihenfolge stimmt.
        touches.values.filter { it.target?.key?.action == Action.CHAR && it.popup == null && it.swipe == 0 }
            .toList().forEach { finish(it) }

        if (y >= barHeight) clearConfirm() // Tippen auf der Tastatur bricht die Rückfrage ab
        if (y < barHeight) {
            val t = Touch(id, null, true, x, y)
            touches[id] = t
            if (confirmText == null && mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT && message == null &&
                styleLabel == null) {
                val i = slotRects().indexOfFirst { it.contains(x, y) }
                if (i >= 0 && slots.getOrNull(i)?.isNotEmpty() == true) postDelayed(t.longPress, 500)
            }
            return
        }
        if (mode == Mode.CLIPBOARD && clipArea.contains(x, y)) {
            touches[id] = Touch(id, null, false, x, y).apply { inClipList = true; scrollStart = clipScroll }
            return
        }
        val kr = keyAt(x, y) ?: return
        val t = Touch(id, kr, false, x, y)
        touches[id] = t
        listener.onKeyFeedback()
        val k = kr.key
        when (k.action) {
            Action.DELETE -> if (!inSubPage() && shiftAppliedToWord && listener.onUndoWordShift()) {
                // Nach Shift aufs Wort: ⌫ nimmt nur die Umwandlung zurück (baronDerNacht ⇧ ⌫)
                shiftAppliedToWord = false
                shift = Shift.OFF
                t.shiftUndone = true
            } else if (!inSubPage()) {
                // Doppelt getippt und gleich wieder gedrückt: am Stück löschen (bis zum Leerzeichen)
                t.chunkDelete = chunkDeleteOn && SystemClock.uptimeMillis() - lastDeleteUp < 350
                if (!t.chunkDelete) listener.onDelete()
                postDelayed(t.repeat, if (t.chunkDelete) 250 else 400)
            }
            Action.CHAR -> if (k.longPress.isNotEmpty()) postDelayed(t.longPress, 320)
            Action.MENU -> if (k.text == "general") postDelayed(t.menuHold, 450)
            Action.SPACE -> if (spaceCursor && mode != Mode.EMOTICONS && mode != Mode.FORMAT) postDelayed(t.longPress, 350)
            else -> {}
        }
        invalidate()
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val t = touches[id] ?: return
        t.lastX = x
        t.lastY = y
        if (t.inClipList) {
            if (abs(y - t.y0) > 8 * dp) t.dragging = true
            if (t.dragging) {
                clipScroll = (t.scrollStart - (y - t.y0)).coerceIn(0f, maxClipScroll())
                invalidate()
            }
            return
        }
        val k = t.target?.key ?: return
        val dx = x - t.x0
        val dy = y - t.y0

        t.popup?.let { options ->
            val rects = popupRects(t)
            val idx = rects.indexOfFirst { x >= it.left && x < it.right }
            val newIdx = when {
                idx >= 0 -> idx
                x < rects.first().left -> 0
                else -> options.lastIndex
            }
            if (newIdx != t.popupIndex) {
                t.popupIndex = newIdx
                invalidate()
            }
            return
        }

        when (k.action) {
            Action.SPACE -> when {
                // Gedrückt gehalten: Cursor folgt dem Finger
                t.cursorMode -> {
                    val step = 11 * dp
                    while (x - t.cursorLastX >= step) {
                        listener.onMoveCursor(1)
                        t.cursorLastX += step
                    }
                    while (t.cursorLastX - x >= step) {
                        listener.onMoveCursor(-1)
                        t.cursorLastX -= step
                    }
                }
                !t.languageSwitched && mode != Mode.EMOTICONS && abs(dx) > spaceSwipeThreshold && abs(dx) > abs(dy) -> {
                    removeCallbacks(t.longPress)
                    t.languageSwitched = true
                    listener.onLanguageSwipe(if (dx > 0) 1 else -1)
                }
                // Schnell losgewischt: das ist der Sprachwechsel, kein Cursor
                abs(dx) > 14 * dp -> removeCallbacks(t.longPress)
            }
            Action.CHAR -> {
                // Zur Seite ziehen: Auswahl sofort öffnen (ohne langes Drücken)
                if (!k.cased && k.longPress.isNotEmpty() && abs(dx) > sideThreshold && abs(dx) > abs(dy)) {
                    removeCallbacks(t.longPress)
                    t.swipe = 0
                    startPopup(t)
                    pointerMove(id, x, y) // Auswahl gleich der Fingerposition folgen lassen
                    return
                }
                val newSwipe = if (abs(dy) > swipeThreshold && abs(dy) > abs(dx)) {
                    if (dy < 0 && k.up != null) 1 else if (dy > 0 && k.down != null) -1 else 0
                } else 0
                if (newSwipe != 0) removeCallbacks(t.longPress)
                t.swipe = newSwipe
            }
            else -> {}
        }
    }

    private fun startPopup(t: Touch) {
        if (t.target?.key?.action == Action.SPACE) {
            if (!t.languageSwitched) {
                t.cursorMode = true
                t.cursorLastX = t.lastX
                listener.onKeyFeedback()
                showMessage("◀  Cursor bewegen  ▶")
            }
            return
        }
        if (t.onBar) {
            val i = slotRects().indexOfFirst { it.contains(t.lastX, t.lastY) }
            if (i >= 0) {
                t.barLong = true
                listener.onKeyFeedback()
                listener.onSuggestionLongPress(i)
            }
            return
        }
        if (t.swipe != 0) return
        val options = t.target?.key?.longPress ?: return
        if (options.isEmpty()) return
        t.popup = options
        t.popupIndex = 0
        listener.onKeyFeedback()
        invalidate()
    }

    private fun pointerUp(id: Int, x: Float, y: Float) {
        val t = touches[id] ?: return
        t.lastX = x
        t.lastY = y
        finish(t)
    }

    private fun cancelTimers(t: Touch) {
        removeCallbacks(t.longPress)
        removeCallbacks(t.repeat)
        removeCallbacks(t.menuHold)
    }

    /** Schließt eine Berührung ab und löst die passende Aktion aus. */
    private fun finish(t: Touch) {
        touches.remove(t.id)
        cancelTimers(t)

        if (t.onBar) {
            if (t.barLong) return
            if (confirmText != null) {
                val action = confirmAction
                val no = confirmNo
                val yes = confirmYesRect.contains(t.lastX, t.lastY)
                clearConfirm()
                if (yes) action?.invoke() else no?.invoke()
                return
            }
            if (clipRect.contains(t.lastX, t.lastY) && clipRect.contains(t.x0, t.y0)) {
                if (mode == Mode.CLIPBOARD) {
                    setMode(lettersMode)
                } else {
                    lettersMode = if (mode == Mode.SYMBOLS || mode == Mode.SYMBOLS2) mode else Mode.LETTERS
                    listener.onClipboardOpen()
                    setMode(Mode.CLIPBOARD)
                }
            } else if (showStyleStop && styleStopRect.contains(t.lastX, t.lastY) && styleStopRect.contains(t.x0, t.y0)) {
                listener.onKeyFeedback()
                listener.onFormat("off") // Formatierung beenden
            } else if (restoreVisible && mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT &&
                restoreRect.contains(t.lastX, t.lastY) && restoreRect.contains(t.x0, t.y0)) {
                listener.onKeyFeedback()
                listener.onRestore()
            } else if (settingsRect.contains(t.lastX, t.lastY) && settingsRect.contains(t.x0, t.y0)) {
                when (mode) {
                    // Aus einer Unterseite direkt in ihre Einstellungen
                    Mode.CLIPBOARD -> { setMode(Mode.LETTERS); listener.onMenu("clipboard") }
                    Mode.EMOTICONS -> { setMode(Mode.LETTERS); listener.onMenu("emoticons") }
                    Mode.MENU -> setMode(Mode.LETTERS)
                    else -> setMode(Mode.MENU)
                }
            } else if (styleLabel != null && message == null && slots.none { it.isNotEmpty() } &&
                mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT) {
                listener.onFormat("off")
            } else if (mode != Mode.CLIPBOARD && mode != Mode.MENU && mode != Mode.FORMAT && message == null) {
                val rects = slotRects()
                val i = rects.indexOfFirst { it.contains(t.lastX, t.lastY) }
                if (i >= 0 && rects[i].contains(t.x0, t.y0) && slots.getOrNull(i)?.isNotEmpty() == true) {
                    listener.onKeyFeedback()
                    listener.onSuggestion(i)
                }
            }
            invalidate()
            return
        }
        if (t.inClipList) {
            if (!t.dragging) clipTap(t.lastX, t.lastY)
            return
        }
        val k = t.target?.key ?: return

        t.popup?.let { options ->
            val opt = options[t.popupIndex]
            typeText(if (k.cased && shift != Shift.OFF) upper(opt) else opt, k.cased)
            invalidate()
            return
        }

        if (k.action != Action.SHIFT && k.action != Action.CHAR) shiftAppliedToWord = false
        when (k.action) {
            Action.CHAR -> {
                // Nach Shift aufs Wort gleich weitertippen: doch der nächste Buchstabe groß (baronDer)
                val afterWordShift = shiftAppliedToWord && shift == Shift.ON
                shiftAppliedToWord = false
                when {
                    t.swipe == 1 && k.up != null -> typeText(k.up, false)
                    t.swipe == -1 && k.down != null -> typeText(k.down, false)
                    else -> {
                        if (afterWordShift && k.cased) listener.onUndoWordShift()
                        typeText(displayText(k), k.cased)
                    }
                }
                if (mode == Mode.EMOTICONS) {
                    prefs?.let {
                        it.noteEmoticonUsed(k.text)
                        emoticonList = it.emoticonsOrdered
                    }
                    setMode(Mode.LETTERS) // nach dem Emoticon zurück zur Tastatur
                }
            }
            Action.SPACE -> if (!t.languageSwitched && !t.cursorMode) listener.onSpace()
            Action.ENTER -> listener.onEnter()
            Action.SHIFT -> onShiftTap()
            Action.TO_LETTERS -> setMode(Mode.LETTERS)
            Action.TO_SYMBOLS -> setMode(Mode.SYMBOLS)
            Action.TO_SYMBOLS2 -> setMode(Mode.SYMBOLS2)
            Action.TO_EMOTICONS -> {
                emoticonPage = 0
                setMode(Mode.EMOTICONS)
            }
            Action.PAGE_PREV, Action.PAGE_NEXT -> {
                val step = if (k.action == Action.PAGE_NEXT) 1 else -1
                if (mode == Mode.FORMAT) {
                    formatPage = (formatPage + step + Layouts.FORMAT_PAGES) % Layouts.FORMAT_PAGES
                    setMode(Mode.FORMAT)
                } else {
                    val pages = Layouts.emoticonPages(emoticonList)
                    emoticonPage = (emoticonPage + step + pages) % pages
                    setMode(Mode.EMOTICONS)
                }
            }
            Action.CLIP_CLEAR -> clipClear()
            Action.MENU -> if (t.menuHeld) {
                // schon beim Halten geöffnet
            } else if (k.text == "format") {
                formatPage = 0
                setMode(Mode.FORMAT)
            } else {
                setMode(Mode.LETTERS)
                listener.onMenu(k.text)
            }
            Action.FORMAT -> {
                setMode(Mode.LETTERS)
                listener.onFormat(k.text)
            }
            // In Emoticons, Zwischenablage und Formatieren: ⌫ führt zurück zur Tastatur
            Action.DELETE -> if (inSubPage()) setMode(Mode.LETTERS) else if (!t.shiftUndone) {
                // Doppeltipp ohne Halten: zweites Zeichen löschen wie gewohnt
                if (t.chunkDelete && SystemClock.uptimeMillis() - t.downTime < 250) listener.onDelete()
                lastDeleteUp = SystemClock.uptimeMillis()
            }
            Action.TO_CLIPBOARD -> {}
        }
        invalidate()
    }

    private fun typeText(text: String, wasLetter: Boolean) {
        listener.onText(text)
        if (wasLetter && shift == Shift.ON) {
            shift = Shift.OFF
            invalidate()
        }
    }

    /** Shift wurde gerade auf das Wort am Cursor angewendet (nicht auf den nächsten Buchstaben). */
    private var shiftAppliedToWord = false

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when (shift) {
            Shift.OFF -> Shift.ON
            Shift.ON -> if (now - lastShiftTap < 350) Shift.LOCK else Shift.OFF
            Shift.LOCK -> Shift.OFF
        }
        lastShiftTap = now
        // Steht der Cursor am Ende eines Wortes: Wort umwandeln (Wort / WORT / wort)
        // Ausschalten ändert das Wort nur, wenn das Einschalten es auch geändert hat
        val wasOnWord = shiftAppliedToWord
        shiftAppliedToWord = mode == Mode.LETTERS && (shift != Shift.OFF || wasOnWord) &&
            listener.onShiftChanged(when (shift) { Shift.OFF -> 0; Shift.ON -> 1; Shift.LOCK -> 2 })
        invalidate()
    }
}
