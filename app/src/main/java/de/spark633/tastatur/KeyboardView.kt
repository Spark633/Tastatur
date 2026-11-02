package de.spark633.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
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
        fun onEnter()
        fun onSpace()
        fun onLanguageSwipe(direction: Int)
        fun onClipboard()
        fun onKeyFeedback()
    }

    enum class Mode { LETTERS, SYMBOLS, SYMBOLS2, EMOTICONS }
    enum class Shift { OFF, ON, LOCK }

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // --- Zustand, von außen gesetzt ---
    private var language = Language.DE
    private var languageCount = 1
    private var qwertz = true
    private var clipboardRight = false
    private var rowHeight = 54 * dp
    private var enterLabel = "↵"
    private var bottomInset = 0

    private var mode = Mode.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var emoticonPage = 0
    private var message: String? = null

    private val barHeight = 44 * dp
    private val gapH = 3 * dp
    private val gapV = 4 * dp
    private val swipeThreshold = 22 * dp
    private val spaceSwipeThreshold = 40 * dp

    private class KeyRect(val key: Key, val rect: RectF, val row: Int)

    private var keyRects = listOf<KeyRect>()
    private val clipRect = RectF()

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
        this.language = language
        languageCount = prefs.enabledLanguages.size
        qwertz = prefs.isQwertz(language)
        clipboardRight = prefs.clipboardRight
        rowHeight = prefs.rowHeightDp * dp
        requestLayout()
        layoutKeys()
        invalidate()
    }

    fun setMode(m: Mode) {
        mode = m
        layoutKeys()
        invalidate()
    }

    fun setEnterLabel(label: String) {
        enterLabel = label
        invalidate()
    }

    /** Automatische Großschreibung am Satzanfang. Feststelltaste bleibt unberührt. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.LOCK) return
        val new = if (on) Shift.ON else Shift.OFF
        if (new != shift) {
            shift = new
            invalidate()
        }
    }

    fun showMessage(text: String) {
        message = text
        invalidate()
        removeCallbacks(clearMessage)
        postDelayed(clearMessage, 1500)
    }

    private val clearMessage = Runnable { message = null; invalidate() }

    fun cancelTouches() {
        touches.values.forEach { cancelTimers(it) }
        touches.clear()
        invalidate()
    }

    // ------------------------------------------------------------------
    // Maße
    // ------------------------------------------------------------------

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= 30) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        if (bottom != bottomInset) {
            bottomInset = bottom
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (barHeight + 4 * rowHeight + 4 * dp).toInt() + bottomInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun rows(): List<List<Key>> = when (mode) {
        Mode.LETTERS -> Layouts.letters(qwertz)
        Mode.SYMBOLS -> Layouts.symbols()
        Mode.SYMBOLS2 -> Layouts.symbols2()
        Mode.EMOTICONS -> Layouts.emoticons(emoticonPage)
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
        if (clipboardRight) clipRect.set(w - cs, 0f, w, cs) else clipRect.set(0f, 0f, cs, cs)
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
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawBar(canvas)
        val pressed = touches.values.mapNotNull { it.target }.toSet()
        for (kr in keyRects) drawKey(canvas, kr, kr in pressed)
        touches.values.firstOrNull { it.popup != null }?.let { drawPopup(canvas, it) }
    }

    private fun drawBar(canvas: Canvas) {
        // Zwischenablage-Symbol
        val cx = clipRect.centerX()
        val cy = clipRect.centerY()
        val bw = 8 * dp
        val bh = 10 * dp
        canvas.drawRoundRect(cx - bw, cy - bh, cx + bw, cy + bh, 2 * dp, 2 * dp, iconPaint)
        canvas.drawRect(cx - 4 * dp, cy - bh - 2 * dp, cx + 4 * dp, cy - bh + 3 * dp, iconPaint)
        canvas.drawLine(cx - 4 * dp, cy - 2 * dp, cx + 4 * dp, cy - 2 * dp, iconPaint)
        canvas.drawLine(cx - 4 * dp, cy + 3 * dp, cx + 4 * dp, cy + 3 * dp, iconPaint)

        message?.let {
            canvas.drawText(it, width / 2f, centerY(barTextPaint, barHeight / 2f), barTextPaint)
        }
    }

    private fun centerY(p: Paint, cy: Float) = cy - (p.descent() + p.ascent()) / 2f

    private fun displayText(k: Key): String {
        if (!k.cased || shift == Shift.OFF || k.text == "ß") return k.text
        return k.text.uppercase()
    }

    private fun drawKey(canvas: Canvas, kr: KeyRect, pressed: Boolean) {
        val k = kr.key
        val r = kr.rect
        val radius = 6 * dp
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
                val hasUp = k.up != null
                val hasDown = k.down != null
                val offset = when {
                    hasUp -> 5 * dp
                    hasDown -> -5 * dp
                    else -> 0f
                }
                canvas.drawText(text, r.centerX(), centerY(p, r.centerY() + offset), p)
                k.up?.let { canvas.drawText(it, r.centerX(), r.top + hintPaint.textSize + 1 * dp, hintPaint) }
                k.down?.let { canvas.drawText(it, r.centerX(), r.bottom - 4 * dp, hintPaint) }
            }
            Action.SPACE -> {
                val name = if (languageCount > 1 && mode != Mode.EMOTICONS) "‹  ${language.displayName}  ›"
                else language.displayName
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
            else -> canvas.drawText(k.label, r.centerX(), centerY(funcLabelPaint, r.centerY()), funcLabelPaint)
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
            val text = if (t.target?.key?.cased == true && shift != Shift.OFF) opt.uppercase() else opt
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
        val longPress = Runnable { startPopup(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                listener.onDelete()
                postDelayed(this, 50)
            }
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
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

        if (y < barHeight) {
            touches[id] = Touch(id, null, true, x, y)
            return
        }
        val kr = keyAt(x, y) ?: return
        val t = Touch(id, kr, false, x, y)
        touches[id] = t
        listener.onKeyFeedback()
        val k = kr.key
        when (k.action) {
            Action.DELETE -> {
                listener.onDelete()
                postDelayed(t.repeat, 400)
            }
            Action.CHAR -> if (k.longPress.isNotEmpty()) postDelayed(t.longPress, 320)
            else -> {}
        }
        invalidate()
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val t = touches[id] ?: return
        t.lastX = x
        t.lastY = y
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
            Action.SPACE -> if (!t.languageSwitched && mode != Mode.EMOTICONS && abs(dx) > spaceSwipeThreshold && abs(dx) > abs(dy)) {
                t.languageSwitched = true
                listener.onLanguageSwipe(if (dx > 0) 1 else -1)
            }
            Action.CHAR -> {
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
    }

    /** Schließt eine Berührung ab und löst die passende Aktion aus. */
    private fun finish(t: Touch) {
        touches.remove(t.id)
        cancelTimers(t)

        if (t.onBar) {
            if (clipRect.contains(t.lastX, t.lastY) && clipRect.contains(t.x0, t.y0)) listener.onClipboard()
            invalidate()
            return
        }
        val k = t.target?.key ?: return

        t.popup?.let { options ->
            val opt = options[t.popupIndex]
            typeText(if (k.cased && shift != Shift.OFF) opt.uppercase() else opt, k.cased)
            invalidate()
            return
        }

        when (k.action) {
            Action.CHAR -> when {
                t.swipe == 1 && k.up != null -> typeText(k.up, false)
                t.swipe == -1 && k.down != null -> typeText(k.down, false)
                else -> typeText(displayText(k), k.cased)
            }
            Action.SPACE -> if (!t.languageSwitched) listener.onSpace()
            Action.ENTER -> listener.onEnter()
            Action.SHIFT -> onShiftTap()
            Action.TO_LETTERS -> setMode(Mode.LETTERS)
            Action.TO_SYMBOLS -> setMode(Mode.SYMBOLS)
            Action.TO_SYMBOLS2 -> setMode(Mode.SYMBOLS2)
            Action.TO_EMOTICONS -> {
                emoticonPage = 0
                setMode(Mode.EMOTICONS)
            }
            Action.PAGE_PREV -> {
                emoticonPage = (emoticonPage - 1 + Layouts.emoticonPages) % Layouts.emoticonPages
                setMode(Mode.EMOTICONS)
            }
            Action.PAGE_NEXT -> {
                emoticonPage = (emoticonPage + 1) % Layouts.emoticonPages
                setMode(Mode.EMOTICONS)
            }
            Action.DELETE -> {}
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

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        shift = when (shift) {
            Shift.OFF -> Shift.ON
            Shift.ON -> if (now - lastShiftTap < 350) Shift.LOCK else Shift.OFF
            Shift.LOCK -> Shift.OFF
        }
        lastShiftTap = now
        invalidate()
    }
}
