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
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.juanma.teclado.core.KeyBox
import com.juanma.teclado.core.KeySpec
import com.juanma.teclado.core.KeyType
import com.juanma.teclado.core.KeyboardSpec
import com.juanma.teclado.core.Layouts
import com.juanma.teclado.core.TextUtil
import com.juanma.teclado.core.TouchModel
import kotlin.math.abs
import kotlin.math.max

enum class ShiftState { OFF, ON, LOCKED }

/**
 * Draws the suggestion bar + keys and turns touches into key presses. The drawn keys
 * never change size; which key a touch belongs to is decided by [TouchModel] using the
 * letter probabilities in [prior].
 *
 * Motion follows Apple's fluid-interface rules: feedback on touch-down with no delay,
 * springs (no fixed-duration animations) so everything is interruptible, things leave
 * the way they came, and "remove animations" in system settings is respected.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context), EmojiPane.Listener {

    interface Listener {
        /** [box] is null when the letter didn't come from a direct tap (e.g. accent popup). */
        fun onLetter(text: String, box: KeyBox?, x: Float, y: Float)
        fun onChar(text: String)
        fun onEmoji(emoji: String)
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
        set(v) {
            if (field != v) {
                field = v
                shiftAnim.animateTo(if (v == ShiftState.OFF) 0f else 1f)
                invalidate()
            }
        }

    var enterLabel = "⏎"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Three suggestion slots; [boldSlot] is drawn bold, [quoteSlot] in quotes ("as typed"). */
    private var slots = listOf("", "", "")
    private var boldSlot = -1
    private var quoteSlot = -1

    fun setSuggestions(s: List<String>, bold: Int, quote: Int) {
        if (s == slots && bold == boldSlot && quote == quoteSlot) return
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

    /** Shows/hides the emoji panel (slides up from below, leaves the same way). */
    var emojiVisible = false
        set(v) {
            if (field != v) {
                field = v
                emojiAnim.animateTo(if (v) 1f else 0f)
                cancelAllPointers()
                invalidate()
            }
        }

    var recentEmojis: List<String>
        get() = emojiPane.recents
        set(v) { emojiPane.recents = v }

    // ---------------------------------------------------------------- geometry

    private val dp = resources.displayMetrics.density
    private val stripH get() = 44 * dp
    private val keyH get() = keyHeightDp * dp
    private val topPad get() = 5 * dp
    private val bottomPad get() = 6 * dp
    private val sidePad get() = 3 * dp
    private val gapX get() = 5.5f * dp
    private val gapY get() = 10 * dp

    private class Key(val spec: KeySpec, val rect: RectF, val box: KeyBox)

    private var keys: List<Key> = emptyList()
    private var boxes: List<KeyBox> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (stripH + topPad + spec.rows.size * keyH + bottomPad).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutKeys()
        emojiPane.layout(RectF(0f, stripH, w.toFloat(), h.toFloat()))
    }

    private fun layoutKeys() {
        if (width == 0) return
        val unit = (width - 2 * sidePad) / 10f
        val list = ArrayList<Key>()
        val lastBottom = stripH + topPad + spec.rows.size * keyH
        spec.rows.forEachIndexed { r, row ->
            val top = stripH + topPad + r * keyH
            var x = 0f
            for (ks in row) {
                var l = sidePad + x * unit
                var rr = sidePad + (x + ks.width) * unit
                val rect = RectF(l + gapX / 2, top + gapY / 2, rr - gapX / 2, top + keyH - gapY / 2)
                // The touch box is the full cell (no gaps), stretched to the screen edges.
                if (x == 0f) l = 0f
                if (abs(x + ks.width - 10f) < 0.01f) rr = width.toFloat()
                val bottom = if (top + keyH >= lastBottom - 1) height.toFloat() else top + keyH
                list.add(Key(ks, rect, KeyBox(list.size, ks.letter, l, top, rr, bottom)))
                x += ks.width
            }
        }
        keys = list
        boxes = list.map { it.box }
        keyAnims.clear()
        if (showZones) computeZones()
    }

    // ---------------------------------------------------------------- colours & type

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val cBg = if (dark) 0xFF1E1E20.toInt() else 0xFFD2D5DB.toInt()
    private val cKey = if (dark) 0xFF5C5C5F.toInt() else Color.WHITE
    private val cSpecial = if (dark) 0xFF38383B.toInt() else 0xFFA9AFB9.toInt()
    private val cPressed = if (dark) 0xFF7E7E83.toInt() else 0xFFE3E5E9.toInt()
    private val cSpecialPressed = if (dark) 0xFF5C5C5F.toInt() else Color.WHITE
    private val cShadow = if (dark) 0xFF0B0B0C.toInt() else 0xFF878A90.toInt()
    private val cText = if (dark) Color.WHITE else Color.BLACK
    private val cSub = if (dark) 0xFFA8A8AE.toInt() else 0xFF5E6168.toInt()
    private val cEdge = if (dark) 0x14FFFFFF else 0x80FFFFFF.toInt()
    private val cAccent = 0xFF0A84FF.toInt()

    private val regular = Typeface.DEFAULT
    private val semibold: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.DEFAULT_BOLD

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    private val emojiPane = EmojiPane(this, this).apply {
        colors = EmojiPane.Colors(
            bg = cBg, text = cText, sub = cSub, special = cSpecial,
            pressed = if (dark) 0x40FFFFFF else 0x26000000,
        )
    }

    // ---------------------------------------------------------------- springs

    private val keyAnims = HashMap<Int, Spring>()          // press highlight per key
    private val shiftAnim = Spring(0f, response = 0.18f)
    private val trackpadAnim = Spring(0f, response = 0.25f)
    private val popupAnim = Spring(0f, response = 0.2f)
    private val popupSelAnim = Spring(0f, response = 0.12f)
    private val stripAnims = Array(3) { Spring(0f, response = 0.2f) }
    private val emojiAnim = Spring(0f, response = 0.32f)

    private fun keyAnim(k: Key) = keyAnims.getOrPut(k.box.index) { Spring(0f, response = 0.18f) }

    private var lastFrame = 0L

    /** Steps every spring; keeps drawing frames only while something is moving. */
    private fun stepAnimations(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else (now - lastFrame) / 1000f
        lastFrame = now
        var moving = false
        for (s in keyAnims.values) moving = s.step(dt) || moving
        moving = shiftAnim.step(dt) || moving
        moving = trackpadAnim.step(dt) || moving
        moving = popupAnim.step(dt) || moving
        moving = popupSelAnim.step(dt) || moving
        for (s in stripAnims) moving = s.step(dt) || moving
        moving = emojiAnim.step(dt) || moving
        moving = emojiPane.step(dt) || moving
        if (!moving) lastFrame = 0L
        return moving
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val moving = stepAnimations()
        canvas.drawColor(cBg)
        // A faint bright top edge, like light catching the edge of a material.
        line.color = cEdge
        line.strokeWidth = 1f
        canvas.drawLine(0f, 0.5f, width.toFloat(), 0.5f, line)
        drawStrip(canvas)

        val e = emojiAnim.value.coerceIn(0f, 1f)
        if (e < 0.999f) {
            canvas.save()
            canvas.translate(0f, e * 14 * dp)
            drawKeys(canvas, 1f - e)
            canvas.restore()
        }
        if (e > 0.001f) {
            canvas.save()
            canvas.translate(0f, (1f - e) * 28 * dp)
            emojiPane.draw(canvas, e)
            canvas.restore()
        }
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawKeys(canvas: Canvas, alpha: Float) {
        val radius = 6 * dp
        val dim = trackpadAnim.value.coerceIn(0f, 1f)
        for (k in keys) {
            val special = k.spec.type !in setOf(KeyType.LETTER, KeyType.CHAR, KeyType.SPACE)
            val press = keyAnims[k.box.index]?.value ?: 0f
            var color = if (special) lerpColor(cSpecial, cSpecialPressed, press) else lerpColor(cKey, cPressed, press)
            var labelColor = cText
            if (k.spec.type == KeyType.SHIFT) {
                val s = shiftAnim.value.coerceIn(0f, 1f)
                color = lerpColor(color, Color.WHITE, s)
                labelColor = lerpColor(cText, Color.BLACK, s)
            }
            // In trackpad mode the keys turn into a blank surface (labels fade out).
            color = lerpColor(color, cSpecial, dim * 0.6f)
            fill.color = withAlpha(cShadow, alpha * (1f - dim))
            canvas.drawRoundRect(k.rect.left, k.rect.top + 1.2f * dp, k.rect.right, k.rect.bottom + 1.2f * dp, radius, radius, fill)
            fill.color = withAlpha(color, alpha)
            canvas.drawRoundRect(k.rect, radius, radius, fill)
            drawLabel(canvas, k, withAlpha(labelColor, alpha * (1f - dim)))
        }
        zoneLines?.let {
            line.color = withAlpha(cAccent, alpha)
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
        text.typeface = regular
        // Size-specific tracking: small labels a touch looser, large glyphs neutral.
        text.letterSpacing = 0f
        text.textSize = when {
            k.spec.type == KeyType.EMOJI -> 22 * dp
            k.spec.type == KeyType.SPACE -> { text.letterSpacing = 0.01f; 15 * dp }
            l.length > 1 -> { text.letterSpacing = 0.01f; 15.5f * dp }
            k.spec.type == KeyType.LETTER -> 23 * dp
            else -> 21 * dp
        }
        if (k.spec.type == KeyType.SPACE) text.color = withAlpha(cSub, (color ushr 24) / 255f)
        val y = k.rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(l, k.rect.centerX(), y, text)
        text.letterSpacing = 0f
    }

    private fun drawStrip(canvas: Canvas) {
        val w = width / 3f
        line.color = withAlpha(cSub, 0.35f)
        line.strokeWidth = 1f * dp
        for (i in 1..2) canvas.drawLine(i * w, stripH * 0.3f, i * w, stripH * 0.7f, line)
        for (i in 0..2) {
            var s = slots.getOrElse(i) { "" }
            val press = stripAnims[i].value
            if (press > 0.01f) {
                fill.color = withAlpha(cSpecial, press)
                canvas.drawRoundRect(i * w + 4 * dp, 5 * dp, (i + 1) * w - 4 * dp, stripH - 5 * dp, 9 * dp, 9 * dp, fill)
            }
            if (s.isEmpty()) continue
            val emoji = TextUtil.isEmoji(s)
            if (i == quoteSlot) s = "«$s»"
            text.color = cText
            text.textSize = if (emoji) 22 * dp else 17 * dp
            text.typeface = if (i == boldSlot && !emoji) semibold else regular
            text.letterSpacing = 0f
            s = ellipsize(s, w - 12 * dp)
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
        val h = k.rect.height() * 1.25f
        val cx = k.rect.centerX().coerceIn(w / 2, width - w / 2)
        val bottom = k.rect.top + k.rect.height() * 0.1f
        val r = RectF(cx - w / 2, max(0f, bottom - h), cx + w / 2, bottom)
        fill.color = cShadow
        canvas.drawRoundRect(r.left, r.top + 1.5f * dp, r.right, r.bottom + 1.5f * dp, 9 * dp, 9 * dp, fill)
        fill.color = cKey
        canvas.drawRoundRect(r, 9 * dp, 9 * dp, fill)
        text.color = cText
        text.typeface = regular
        text.textSize = 32 * dp
        text.letterSpacing = -0.02f // large glyph: slightly tighter
        canvas.drawText(label(k), r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.letterSpacing = 0f
    }

    private fun popupRect(p: Pointer): RectF {
        val n = p.key.spec.variants.size
        val cell = p.key.rect.width() * 1.1f
        val w = cell * n
        val left = (p.key.rect.left).coerceIn(2 * dp, max(2 * dp, width - w - 2 * dp))
        val bottom = p.key.rect.top + p.key.rect.height() * 0.1f
        return RectF(left, max(0f, bottom - keyH), left + w, bottom)
    }

    private fun drawPopup(canvas: Canvas, p: Pointer) {
        val r = popupRect(p)
        val s = popupAnim.value.coerceIn(0f, 1.2f)
        canvas.save()
        // Grows out of the key that was pressed (anchored at its top centre).
        canvas.scale(0.6f + 0.4f * s, 0.6f + 0.4f * s, p.key.rect.centerX().coerceIn(r.left, r.right), r.bottom)
        val a = s.coerceIn(0f, 1f)
        fill.color = withAlpha(cShadow, a)
        canvas.drawRoundRect(r.left, r.top + 1.5f * dp, r.right, r.bottom + 1.5f * dp, 9 * dp, 9 * dp, fill)
        fill.color = withAlpha(cKey, a)
        canvas.drawRoundRect(r, 9 * dp, 9 * dp, fill)
        val vs = p.key.spec.variants
        val cell = r.width() / vs.size
        // Selection pill slides between options instead of jumping.
        val sx = r.left + popupSelAnim.value * cell
        fill.color = withAlpha(cAccent, a)
        canvas.drawRoundRect(sx + 2 * dp, r.top + 2 * dp, sx + cell - 2 * dp, r.bottom - 2 * dp, 7 * dp, 7 * dp, fill)
        for ((i, v) in vs.withIndex()) {
            val cx = r.left + (i + 0.5f) * cell
            text.color = withAlpha(if (i == p.popupIndex) Color.WHITE else cText, a)
            text.typeface = regular
            text.textSize = 24 * dp
            canvas.drawText(caseOf(v), cx, r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        canvas.restore()
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
    private var emojiTouch = false

    private fun caseOf(s: String) = if (shift != ShiftState.OFF) s.uppercase() else s

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The emoji panel owns the area below the strip while it's (becoming) visible.
        if (e.actionMasked == MotionEvent.ACTION_DOWN) emojiTouch = emojiVisible && e.y >= stripH
        if (emojiTouch) {
            emojiPane.onTouch(e)
            invalidate()
            return true
        }
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
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun cancelAllPointers() {
        for (p in pointers.values) {
            cancelCallbacks(p)
            keyAnim(p.key).animateTo(0f)
        }
        pointers.clear()
        if (stripPressed >= 0) stripAnims[stripPressed].animateTo(0f)
        stripPressed = -1
        stripPointer = -1
        trackpadAnim.animateTo(0f)
        invalidate()
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (y < stripH) {
            if (pointers.isEmpty()) {
                stripPointer = id
                stripPressed = (x / (width / 3f)).toInt().coerceIn(0, 2)
                if (slots.getOrElse(stripPressed) { "" }.isEmpty()) stripPressed = -1
                else stripAnims[stripPressed].snap(1f) // instant feedback on touch-down
                invalidate()
            }
            return
        }
        if (emojiVisible) return
        // Fast typists press the next key before releasing the previous one:
        // commit pending taps first so the prediction sees the right letters.
        for (p in pointers.values) if (p.mode == Mode.NORMAL && p.key.spec.type != KeyType.SPACE &&
            p.key.spec.type != KeyType.DELETE) {
            cancelCallbacks(p)
            commit(p)
            p.mode = Mode.DONE
            keyAnim(p.key).animateTo(0f)
        }
        val p0 = if (spec === Layouts.LETTERS) prior else null
        val box = touchModel.resolve(x, y, boxes, p0) ?: return
        val key = keys[box.index]
        val p = Pointer(id, key, x, y)
        pointers[id] = p
        keyAnim(key).snap(1f) // highlight + haptic on the same frame as the touch
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
            enterTrackpad(p, p.x)
        } else {
            p.mode = Mode.POPUP
            p.popupIndex = 0
            popupAnim.snap(0f)
            popupAnim.animateTo(1f)
            popupSelAnim.snap(0f)
        }
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun enterTrackpad(p: Pointer, x: Float) {
        p.mode = Mode.TRACKPAD
        p.lastTrackX = x
        trackpadAnim.animateTo(1f)
        keyAnim(p.key).animateTo(0f)
    }

    private fun move(id: Int, x: Float, y: Float) {
        if (id == stripPointer) {
            if (stripPressed >= 0) {
                val s = (x / (width / 3f)).toInt().coerceIn(0, 2)
                if (s != stripPressed || y > stripH * 1.5f) {
                    stripAnims[stripPressed].animateTo(0f) // dragged away = cancel
                    stripPressed = -1
                    invalidate()
                }
            }
            return
        }
        val p = pointers[id] ?: return
        when (p.mode) {
            Mode.NORMAL -> if (p.key.spec.type == KeyType.SPACE && abs(x - p.x) > 12 * dp) {
                // Slide on the space bar = move the cursor (like iOS' trackpad).
                removeCallbacks(p.longPress)
                enterTrackpad(p, x)
                invalidate()
            }
            Mode.POPUP -> {
                val r = popupRect(p)
                val n = p.key.spec.variants.size
                val idx = ((x - r.left) / (r.width() / n)).toInt().coerceIn(0, n - 1)
                if (idx != p.popupIndex) {
                    p.popupIndex = idx
                    popupSelAnim.animateTo(idx.toFloat())
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
            }
            Mode.TRACKPAD -> {
                val stepPx = 10 * dp
                val steps = ((x - p.lastTrackX) / stepPx).toInt()
                if (steps != 0) {
                    p.lastTrackX += steps * stepPx
                    listener.onCursorMove(steps)
                    if (haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
            if (s >= 0) {
                stripAnims[s].animateTo(0f)
                if (y < stripH * 1.5f) listener.onSuggestion(s)
            }
            invalidate()
            return
        }
        val p = pointers.remove(id) ?: return
        cancelCallbacks(p)
        if (p.mode == Mode.TRACKPAD) trackpadAnim.animateTo(0f)
        if (p.mode != Mode.DONE) commit(p)
        keyAnim(p.key).animateTo(0f) // highlight fades out; it appeared instantly
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
                KeyType.EMOJI, KeyType.TO_SYMBOLS, KeyType.TO_SYMBOLS2, KeyType.TO_LETTERS -> listener.onMode(s.type)
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

    // ---------------------------------------------------------------- EmojiPane.Listener

    override fun onEmoji(emoji: String) = listener.onEmoji(emoji)
    override fun onDelete(repeat: Int) = listener.onDelete(repeat)
    override fun onCloseEmoji() = listener.onMode(KeyType.TO_LETTERS)
}
