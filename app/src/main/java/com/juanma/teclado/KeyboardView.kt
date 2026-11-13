package com.juanma.teclado

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.juanma.teclado.core.KeyBox
import com.juanma.teclado.core.KeySpec
import com.juanma.teclado.core.KeyType
import com.juanma.teclado.core.KeyboardSpec
import com.juanma.teclado.core.Layouts
import com.juanma.teclado.core.TouchModel
import kotlin.math.abs
import kotlin.math.max

enum class ShiftState { OFF, ON, LOCKED }

/**
 * Draws the suggestion bar + keys and turns touches into key presses. The drawn keys
 * never change size; which key a touch belongs to is decided by [TouchModel] using the
 * letter probabilities in [prior].
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** [box] is null when the letter didn't come from a direct tap (e.g. accent popup). */
        fun onLetter(text: String, box: KeyBox?, x: Float, y: Float)
        fun onChar(text: String)
        fun onSpace()
        fun onDelete(repeat: Int)
        fun onEnter()
        fun onShift()
        fun onMode(type: KeyType)
        fun onSuggestion(index: Int)
        fun onCursorMove(steps: Int)
    }

    // ---------------------------------------------------------------- public state

    var touchModel = TouchModel()

    var spec: KeyboardSpec = Layouts.LETTERS
        set(v) { if (field !== v) { field = v; layoutKeys(); invalidate() } }

    var shift = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate() } }

    var enterLabel = "⏎"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Three suggestion slots; [boldSlot] is drawn bold, [quoteSlot] in quotes ("as typed"). */
    private var slots = listOf("", "", "")
    private var boldSlot = -1
    private var quoteSlot = -1

    fun setSuggestions(s: List<String>, bold: Int, quote: Int) {
        slots = s; boldSlot = bold; quoteSlot = quote
        invalidate()
    }

    /** Probability of each letter being next. null = no adaptation. */
    var prior: Map<Char, Float>? = null
        set(v) { field = v; if (showZones) computeZones() }

    var showZones = false
        set(v) { field = v; if (v) computeZones() else zoneLines = null; invalidate() }

    var haptics = true
    var sound = false

    var keyHeightDp = 54
        set(v) { if (field != v) { field = v; requestLayout() } }

    // ---------------------------------------------------------------- geometry

    private val dp = resources.displayMetrics.density
    private val stripH get() = 44 * dp
    private val keyH get() = keyHeightDp * dp
    private val topPad get() = 4 * dp
    private val bottomPad get() = 6 * dp
    private val sidePad get() = 3 * dp
    private val gapX get() = 3 * dp
    private val gapY get() = 5 * dp

    private class Key(val spec: KeySpec, val rect: RectF, val box: KeyBox)

    private var keys: List<Key> = emptyList()
    private var boxes: List<KeyBox> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = spec.rows.size
        val h = (stripH + topPad + rows * keyH + bottomPad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
    }

    private fun layoutKeys() {
        if (width == 0) return
        val unit = (width - 2 * sidePad) / 10f
        val list = ArrayList<Key>()
        spec.rows.forEachIndexed { r, row ->
            val top = stripH + topPad + r * keyH
            var x = 0f
            for (ks in row) {
                val l = sidePad + x * unit
                val rr = sidePad + (x + ks.width) * unit
                // The touch box is the full cell (no gaps), the drawn key is inset.
                val box = KeyBox(list.size, ks.letter, l, top, rr, top + keyH)
                val rect = RectF(l + gapX / 2, top + gapY / 2, rr - gapX / 2, top + keyH - gapY / 2)
                list.add(Key(ks, rect, box))
                x += ks.width
            }
        }
        // Stretch the outermost keys' touch boxes to the screen edges.
        keys = list.map { k ->
            val b = k.box
            val l = if (b.left <= sidePad + 1) 0f else b.left
            val r = if (b.right >= width - sidePad - 1) width.toFloat() else b.right
            val btm = if (b.bottom >= stripH + topPad + spec.rows.size * keyH - 1) height.toFloat() else b.bottom
            Key(k.spec, k.rect, KeyBox(b.index, b.letter, l, b.top, r, btm))
        }
        boxes = keys.map { it.box }
        if (showZones) computeZones()
    }

    // ---------------------------------------------------------------- colours

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val cBg = if (dark) 0xFF1F1F21.toInt() else 0xFFD3D6DC.toInt()
    private val cKey = if (dark) 0xFF5F5F62.toInt() else Color.WHITE
    private val cSpecial = if (dark) 0xFF3A3A3D.toInt() else 0xFFABB0BA.toInt()
    private val cPressed = if (dark) 0xFF838387.toInt() else 0xFFE6E8EC.toInt()
    private val cShadow = if (dark) 0xFF0E0E0F.toInt() else 0xFF888B90.toInt()
    private val cText = if (dark) Color.WHITE else Color.BLACK
    private val cSub = if (dark) 0xFFB0B0B5.toInt() else 0xFF55585E.toInt()
    private val cAccent = 0xFF0A84FF.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(cBg)
        drawStrip(canvas)
        val radius = 6 * dp
        for (k in keys) {
            val pressed = pointers.values.any { it.key === k && it.mode != Mode.TRACKPAD }
            val special = k.spec.type != KeyType.LETTER && k.spec.type != KeyType.CHAR && k.spec.type != KeyType.SPACE
            var color = when {
                pressed -> if (special) cKey else cPressed
                special -> cSpecial
                else -> cKey
            }
            var labelColor = cText
            if (k.spec.type == KeyType.SHIFT && shift != ShiftState.OFF) {
                color = Color.WHITE
                labelColor = Color.BLACK
            }
            fill.color = cShadow
            canvas.drawRoundRect(k.rect.left, k.rect.top + 1.2f * dp, k.rect.right, k.rect.bottom + 1.2f * dp, radius, radius, fill)
            fill.color = color
            canvas.drawRoundRect(k.rect, radius, radius, fill)
            drawLabel(canvas, k, labelColor)
        }
        if (trackpadActive()) {
            fill.color = (cBg and 0x00FFFFFF) or 0xB0000000.toInt()
            canvas.drawRect(0f, stripH, width.toFloat(), height.toFloat(), fill)
        }
        zoneLines?.let {
            line.color = cAccent
            line.strokeWidth = 1.5f * dp
            canvas.drawLines(it, line)
        }
        for (p in pointers.values) {
            when (p.mode) {
                Mode.NORMAL -> if (p.key.spec.type == KeyType.LETTER || p.key.spec.type == KeyType.CHAR) drawPreview(canvas, p.key)
                Mode.POPUP -> drawPopup(canvas, p)
                else -> {}
            }
        }
    }

    private fun label(k: Key): String = when (k.spec.type) {
        KeyType.LETTER -> if (shift != ShiftState.OFF) k.spec.label.uppercase() else k.spec.label
        KeyType.ENTER -> enterLabel
        KeyType.SHIFT -> if (shift == ShiftState.LOCKED) "⇪" else "⇧"
        else -> k.spec.label
    }

    private fun drawLabel(canvas: Canvas, k: Key, color: Int) {
        val l = label(k)
        text.color = color
        text.typeface = Typeface.DEFAULT
        text.textSize = when {
            k.spec.type == KeyType.SPACE -> 15 * dp
            l.length > 1 && k.spec.type != KeyType.ENTER -> 16 * dp
            k.spec.type == KeyType.ENTER && l.length > 1 -> 15 * dp
            k.spec.type == KeyType.LETTER -> 23 * dp
            else -> 21 * dp
        }
        if (k.spec.type == KeyType.SPACE) text.color = cSub
        val y = k.rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(l, k.rect.centerX(), y, text)
    }

    private fun drawStrip(canvas: Canvas) {
        val w = width / 3f
        line.color = cSub
        line.strokeWidth = 1f * dp
        for (i in 1..2) canvas.drawLine(i * w, stripH * 0.28f, i * w, stripH * 0.72f, line)
        for (i in 0..2) {
            var s = slots.getOrElse(i) { "" }
            if (s.isEmpty()) continue
            if (i == quoteSlot) s = "«$s»"
            text.color = cText
            text.textSize = 17 * dp
            text.typeface = if (i == boldSlot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            s = ellipsize(s, w - 12 * dp)
            if (i == stripPressed) {
                fill.color = cSpecial
                canvas.drawRoundRect(i * w + 4 * dp, 4 * dp, (i + 1) * w - 4 * dp, stripH - 4 * dp, 8 * dp, 8 * dp, fill)
            }
            canvas.drawText(s, i * w + w / 2, stripH / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun drawPreview(canvas: Canvas, k: Key) {
        val w = k.rect.width() * 1.35f
        val h = k.rect.height() * 1.15f
        val cx = k.rect.centerX().coerceIn(w / 2, width - w / 2)
        val bottom = k.rect.top + k.rect.height() * 0.15f
        val r = RectF(cx - w / 2, max(0f, bottom - h), cx + w / 2, bottom)
        fill.color = cShadow
        canvas.drawRoundRect(r.left, r.top + 1.5f * dp, r.right, r.bottom + 1.5f * dp, 8 * dp, 8 * dp, fill)
        fill.color = cKey
        canvas.drawRoundRect(r, 8 * dp, 8 * dp, fill)
        text.color = cText
        text.typeface = Typeface.DEFAULT
        text.textSize = 30 * dp
        canvas.drawText(label(k), r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun popupRect(p: Pointer): RectF {
        val n = p.key.spec.variants.size
        val cell = p.key.rect.width() * 1.05f
        val w = cell * n
        val left = (p.key.rect.left).coerceIn(2 * dp, max(2 * dp, width - w - 2 * dp))
        val bottom = p.key.rect.top + p.key.rect.height() * 0.1f
        return RectF(left, max(0f, bottom - keyH * 0.95f), left + w, bottom)
    }

    private fun drawPopup(canvas: Canvas, p: Pointer) {
        val r = popupRect(p)
        fill.color = cShadow
        canvas.drawRoundRect(r.left, r.top + 1.5f * dp, r.right, r.bottom + 1.5f * dp, 8 * dp, 8 * dp, fill)
        fill.color = cKey
        canvas.drawRoundRect(r, 8 * dp, 8 * dp, fill)
        val vs = p.key.spec.variants
        val cell = r.width() / vs.size
        for ((i, v) in vs.withIndex()) {
            val cr = RectF(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom)
            if (i == p.popupIndex) {
                fill.color = cAccent
                canvas.drawRoundRect(cr.left + 2 * dp, cr.top + 2 * dp, cr.right - 2 * dp, cr.bottom - 2 * dp, 6 * dp, 6 * dp, fill)
            }
            text.color = if (i == p.popupIndex) Color.WHITE else cText
            text.typeface = Typeface.DEFAULT
            text.textSize = 24 * dp
            canvas.drawText(caseOf(v), cr.centerX(), cr.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }

    // ---------------------------------------------------------------- touch-zone overlay

    private var zoneLines: FloatArray? = null

    /** Draws the current (invisible) borders between letter keys, for debugging/curiosity. */
    private fun computeZones() {
        if (width == 0 || keys.isEmpty()) return
        val step = 4 * dp
        val top = stripH + topPad
        val bottom = stripH + topPad + (spec.rows.size - 1) * keyH // skip bottom row
        val cols = (width / step).toInt()
        val rows = ((bottom - top) / step).toInt()
        val p = if (spec === Layouts.LETTERS) prior else null
        val grid = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val k = touchModel.resolve(c * step + step / 2, top + r * step + step / 2, boxes, p)
            grid[r * cols + c] = if (k?.letter != null) k.index else -1
        }
        val out = ArrayList<Float>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val g = grid[r * cols + c]
            if (g < 0) continue
            val x = c * step
            val y = top + r * step
            if (c + 1 < cols && grid[r * cols + c + 1] != g && grid[r * cols + c + 1] >= 0) {
                out += x + step; out += y; out += x + step; out += y + step
            }
            if (r + 1 < rows && grid[(r + 1) * cols + c] != g && grid[(r + 1) * cols + c] >= 0) {
                out += x; out += y + step; out += x + step; out += y + step
            }
        }
        zoneLines = out.toFloatArray()
        invalidate()
    }

    // ---------------------------------------------------------------- touch handling

    private enum class Mode { NORMAL, POPUP, TRACKPAD, DONE }

    private inner class Pointer(val id: Int, val key: Key, val x: Float, val y: Float) {
        var mode = Mode.NORMAL
        var popupIndex = 0
        var lastTrackX = x
        var repeat = 0
        val longPress = Runnable { onLongPress(this) }
        val repeater: Runnable = object : Runnable {
            override fun run() {
                repeat++
                listener.onDelete(repeat)
                feedback(key)
                postDelayed(this, if (repeat < 12) 70L else 110L)
            }
        }
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private var stripPressed = -1
    private var stripPointer = -1

    private fun trackpadActive() = pointers.values.any { it.mode == Mode.TRACKPAD }

    private fun caseOf(s: String) = if (shift != ShiftState.OFF) s.uppercase() else s

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                up(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (p in pointers.values) cancelCallbacks(p)
                pointers.clear()
                stripPressed = -1
                invalidate()
            }
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (y < stripH) {
            if (pointers.isEmpty()) {
                stripPointer = id
                stripPressed = (x / (width / 3f)).toInt().coerceIn(0, 2)
                if (slots.getOrElse(stripPressed) { "" }.isEmpty()) stripPressed = -1
                invalidate()
            }
            return
        }
        // Fast typists press the next key before releasing the previous one:
        // commit pending taps first so the prediction sees the right letters.
        for (p in pointers.values) if (p.mode == Mode.NORMAL && p.key.spec.type != KeyType.SPACE &&
            p.key.spec.type != KeyType.DELETE) {
            cancelCallbacks(p)
            commit(p)
            p.mode = Mode.DONE
        }
        val p0 = if (spec === Layouts.LETTERS) prior else null
        val box = touchModel.resolve(x, y, boxes, p0) ?: return
        val key = keys[box.index]
        val p = Pointer(id, key, x, y)
        pointers[id] = p
        feedback(key)
        when (key.spec.type) {
            KeyType.DELETE -> {
                listener.onDelete(0)
                postDelayed(p.repeater, 400)
            }
            KeyType.SPACE -> postDelayed(p.longPress, 450)
            else -> if (key.spec.variants.isNotEmpty()) postDelayed(p.longPress, 380)
        }
        invalidate()
    }

    private fun onLongPress(p: Pointer) {
        if (!pointers.containsValue(p) || p.mode != Mode.NORMAL) return
        if (p.key.spec.type == KeyType.SPACE) {
            p.mode = Mode.TRACKPAD
        } else {
            p.mode = Mode.POPUP
            p.popupIndex = 0
        }
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == stripPointer && stripPressed >= 0) {
            val s = (x / (width / 3f)).toInt().coerceIn(0, 2)
            if (s != stripPressed || y > stripH * 1.5f) { stripPressed = -1; invalidate() }
            return
        }
        val p = pointers[id] ?: return
        when (p.mode) {
            Mode.NORMAL -> if (p.key.spec.type == KeyType.SPACE && abs(x - p.x) > 14 * dp) {
                // Slide on the space bar = move the cursor (like iOS' trackpad).
                removeCallbacks(p.longPress)
                p.mode = Mode.TRACKPAD
                p.lastTrackX = x
                invalidate()
            }
            Mode.POPUP -> {
                val r = popupRect(p)
                val n = p.key.spec.variants.size
                val idx = ((x - r.left) / (r.width() / n)).toInt().coerceIn(0, n - 1)
                if (idx != p.popupIndex) { p.popupIndex = idx; invalidate() }
            }
            Mode.TRACKPAD -> {
                val stepPx = 11 * dp
                val steps = ((x - p.lastTrackX) / stepPx).toInt()
                if (steps != 0) {
                    p.lastTrackX += steps * stepPx
                    listener.onCursorMove(steps)
                }
            }
            Mode.DONE -> {}
        }
    }

    private fun up(id: Int, x: Float, y: Float) {
        if (id == stripPointer) {
            val s = stripPressed
            stripPointer = -1
            stripPressed = -1
            if (s >= 0 && y < stripH * 1.5f) listener.onSuggestion(s)
            invalidate()
            return
        }
        val p = pointers.remove(id) ?: return
        cancelCallbacks(p)
        if (p.mode != Mode.DONE) commit(p)
        invalidate()
    }

    private fun cancelCallbacks(p: Pointer) {
        removeCallbacks(p.longPress)
        removeCallbacks(p.repeater)
    }

    private fun commit(p: Pointer) {
        val s = p.key.spec
        when (p.mode) {
            Mode.POPUP -> {
                val v = caseOf(s.variants[p.popupIndex])
                if (v[0].isLetter()) listener.onLetter(v, null, p.x, p.y) else listener.onChar(v)
            }
            Mode.NORMAL -> when (s.type) {
                KeyType.LETTER -> listener.onLetter(caseOf(s.text), p.key.box, p.x, p.y)
                KeyType.CHAR -> listener.onChar(s.text)
                KeyType.SPACE -> listener.onSpace()
                KeyType.ENTER -> listener.onEnter()
                KeyType.SHIFT -> listener.onShift()
                KeyType.DELETE -> {}
                KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS -> listener.onMode(s.type)
            }
            else -> {}
        }
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun feedback(k: Key) {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (sound) {
            val fx = when (k.spec.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, -1f)
        }
    }
}
