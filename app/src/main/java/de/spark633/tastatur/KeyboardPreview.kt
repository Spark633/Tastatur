package de.spark633.tastatur

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Vorschau der Tastatur für die Aussehen-Einstellungen: Hintergrundbild,
 * Farben und Tasten wie in der echten Tastatur. Bild nach oben/unten ziehen
 * wählt den sichtbaren Ausschnitt.
 */
@SuppressLint("ViewConstructor")
class KeyboardPreview(context: Context, private val prefs: Prefs) : View(context) {

    private val dp = resources.displayMetrics.density
    private val screenW = resources.displayMetrics.widthPixels.toFloat()
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var bitmap: Bitmap? = null
    private var loadedVersion = -1L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val rows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
    private val ups = listOf("1234567890", "@#&*-+=()", "")
    private val downs = listOf("", "", "_€'\":;/")

    /** Echte Tastaturhöhe, verkleinert auf die Breite der Vorschau. */
    private fun keyboardHeight(w: Float): Float {
        val real = 44 * dp + 4 * prefs.rowHeightDp * dp + 4 * dp
        return real * (w / screenW)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, keyboardHeight(w.toFloat()).toInt())
    }

    /** Nach Änderungen in den Einstellungen neu zeichnen (Bild bei Bedarf neu laden). */
    fun refresh() {
        if (prefs.bgVersion != loadedVersion) {
            loadedVersion = prefs.bgVersion
            bitmap = if (prefs.bgVersion == 0L) null else try { Background.load(context) } catch (_: Throwable) { null }
        }
        requestLayout()
        invalidate()
    }

    private fun alpha(color: Int, a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDraw(canvas: Canvas) {
        if (loadedVersion != prefs.bgVersion) refresh()
        val w = width.toFloat()
        val h = height.toFloat()
        val k = w / screenW // Maßstab gegenüber der echten Tastatur
        val t = Theme.byId(prefs.theme)

        // Hintergrund und Bild
        fill.color = t.background
        canvas.drawRect(0f, 0f, w, h, fill)
        bitmap?.let { bmp ->
            val src = Background.sourceRect(bmp.width, bmp.height, w, h,
                prefs.bgZoom / 100f, prefs.bgPositionX / 100f, prefs.bgPosition / 100f)
            canvas.drawBitmap(bmp, src, RectF(0f, 0f, w, h), bmpPaint)
            fill.color = alpha(if (t.light) Color.WHITE else Color.BLACK, prefs.bgDim / 100f)
            canvas.drawRect(0f, 0f, w, h, fill)
        }

        // Farben wie in der Tastatur
        val op = prefs.keyOpacity / 100f
        val keyAlpha = if (t.light) 0.5f + op * 0.5f else op
        val keyColor = alpha(t.key, keyAlpha)
        val funcColor = alpha(t.functionKey, if (t.light) keyAlpha else keyAlpha * 0.55f)
        val radius = prefs.cornerRadiusDp * dp * k
        val scaleText = prefs.textScale / 100f
        text.color = t.text
        text.textSize = sp(23f) * scaleText * k
        hint.color = t.hint
        hint.textSize = sp(12f) * scaleText * k

        // Leiste mit Beispiel-Vorschlägen
        val bar = 44 * dp * k
        text.textSize = sp(17f) * k
        canvas.drawText("Hallo", w / 2f, bar / 2f - (text.descent() + text.ascent()) / 2f, text)
        text.textSize = sp(23f) * scaleText * k

        val rowH = prefs.rowHeightDp * dp * k
        val unit = w / 10f
        val gapH = 3 * dp * k
        val gapV = 4 * dp * k
        fun key(x: Float, y: Float, units: Float, label: String, function: Boolean, up: String?, down: String?) {
            val r = RectF(x + gapH, y + gapV, x + units * unit - gapH, y + rowH - gapV)
            fill.color = if (function) funcColor else keyColor
            canvas.drawRoundRect(r, radius, radius, fill)
            val showHints = prefs.showHints
            val off = when {
                showHints && up != null -> 4 * dp * k
                showHints && down != null -> -4 * dp * k
                else -> 0f
            }
            canvas.drawText(label, r.centerX(), r.centerY() + off - (text.descent() + text.ascent()) / 2f, text)
            if (showHints) {
                up?.let { canvas.drawText(it, r.centerX(), r.top + hint.textSize + dp * k, hint) }
                down?.let { canvas.drawText(it, r.centerX(), r.bottom - 3 * dp * k, hint) }
            }
        }
        rows.forEachIndexed { i, letters ->
            val y = bar + i * rowH
            var x = (10 - letters.length - if (i == 2) 3 else 0) / 2f * unit
            if (i == 2) {
                key(0f, y, 1.5f, "⇧", true, null, null)
                x = 1.5f * unit
            }
            letters.forEachIndexed { j, c ->
                key(x, y, 1f, c.toString(), false,
                    ups[i].getOrNull(j)?.toString(), downs[i].getOrNull(j)?.toString())
                x += unit
            }
            if (i == 2) key(x, y, 1.5f, "⌫", true, null, null)
        }
        val y = bar + 3 * rowH
        key(0f, y, 1.5f, "123", true, null, null)
        key(1.5f * unit, y, 1f, "^.^", true, null, null)
        key(2.5f * unit, y, 1f, ",", false, null, null)
        key(3.5f * unit, y, 4f, "Deutsch", false, null, null)
        key(7.5f * unit, y, 1f, ".", false, null, null)
        key(8.5f * unit, y, 1.5f, "↵", true, null, null)

        if (bitmap != null) {
            hint.color = t.text
            canvas.drawText("Bild ziehen = verschieben · zwei Finger = zoomen", w / 2f, h - 2 * dp, hint)
        }
    }

    private var lastX = 0f
    private var lastY = 0f

    // Genaue Werte während der Geste (Einstellungen speichern nur ganze Prozent)
    private var zoomF = 100f
    private var posXF = 50f
    private var posYF = 50f

    /** Zwei Finger: zoomen. */
    private val pinch = android.view.ScaleGestureDetector(context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                zoomF = (zoomF * d.scaleFactor).coerceIn(100f, 300f)
                val z = zoomF.toInt()
                if (z != prefs.bgZoom) {
                    prefs.bgZoom = z
                    invalidate()
                }
                return true
            }
        })

    /** Ein Finger: Bild verschieben (waagerecht und senkrecht). */
    private fun pan(dx: Float, dy: Float) {
        val bmp = bitmap ?: return
        val scale = max(width.toFloat() / bmp.width, height.toFloat() / bmp.height) * (zoomF / 100f)
        val hiddenX = bmp.width * scale - width
        val hiddenY = bmp.height * scale - height
        if (hiddenX > 1f) {
            posXF = (posXF - dx / hiddenX * 100f).coerceIn(0f, 100f)
            val x = Math.round(posXF)
            if (x != prefs.bgPositionX) prefs.bgPositionX = x
        }
        if (hiddenY > 1f) {
            posYF = (posYF - dy / hiddenY * 100f).coerceIn(0f, 100f)
            val y = Math.round(posYF)
            if (y != prefs.bgPosition) prefs.bgPosition = y
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                zoomF = prefs.bgZoom.toFloat()
                posXF = prefs.bgPositionX.toFloat()
                posYF = prefs.bgPosition.toFloat()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Nach dem Zoomen nicht springen
                lastX = e.getX(0)
                lastY = e.getY(0)
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !pinch.isInProgress) {
                pan(e.x - lastX, e.y - lastY)
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
