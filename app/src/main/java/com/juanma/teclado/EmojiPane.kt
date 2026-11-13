package com.juanma.teclado

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import com.juanma.teclado.core.EmojiData
import kotlin.math.abs
import kotlin.math.pow

/**
 * Emoji grid drawn inside [KeyboardView]. Scrolling follows the finger 1:1, keeps the
 * finger's momentum on release (iOS deceleration curve), resists softly past the ends
 * (rubber band) and can be grabbed at any moment, even mid-animation.
 */
class EmojiPane(private val host: View, private val listener: Listener) {

    interface Listener {
        fun onEmoji(emoji: String)
        fun onDelete(repeat: Int)
        fun onCloseEmoji()
    }

    private class Section(val title: String, val icon: String, val emojis: List<String>) {
        var top = 0f
    }

    private val dp = host.resources.displayMetrics.density
    private val cols = 8
    private val headerH = 30 * dp
    private val barH = 46 * dp

    var colors = Colors()

    class Colors(
        val bg: Int = 0xFFD3D6DC.toInt(),
        val text: Int = 0xFF000000.toInt(),
        val sub: Int = 0xFF6B6E74.toInt(),
        val special: Int = 0xFFABB0BA.toInt(),
        val pressed: Int = 0x33000000,
    )

    private var sections: List<Section> = emptyList()
    private var area = RectF()
    private var grid = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private var contentH = 0f
    private val maxScroll get() = (contentH - grid.height()).coerceAtLeast(0f)

    var recents: List<String> = emptyList()
        set(v) { if (field != v) { field = v; rebuild() } }

    // ---- scroll state
    private var scroll = 0f
    private var momentum = 0f // px/s, 0 = none
    private val spring = Spring(0f, damping = 1f, response = 0.45f, precision = 0.5f)
    private var springing = false
    private val indicator = Spring(0f, damping = 1f, response = 0.25f, precision = 0.5f)

    // ---- touch state
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragStartScroll = 0f
    private var dragging = false
    private var pressedEmoji: String? = null
    private var pressedRect: RectF? = null
    private val pressFade = Spring(0f, damping = 1f, response = 0.2f)
    private var deletePressed = false
    private var deleteRepeat = 0
    private val repeater: Runnable = object : Runnable {
        override fun run() {
            deleteRepeat++
            listener.onDelete(deleteRepeat)
            host.postDelayed(this, if (deleteRepeat < 12) 70L else 110L)
        }
    }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun layout(r: RectF) {
        area = RectF(r)
        grid = RectF(r.left, r.top, r.right, r.bottom - barH)
        cellW = r.width() / cols
        cellH = minOf(cellW, 48 * dp)
        rebuild()
    }

    private fun rebuild() {
        val list = ArrayList<Section>()
        if (recents.isNotEmpty()) list.add(Section("Usados con frecuencia", "🕘", recents))
        for (c in EmojiData.CATEGORIES) list.add(Section(c.name, c.icon, c.emojis))
        var y = 0f
        for (s in list) {
            s.top = y
            y += headerH + ((s.emojis.size + cols - 1) / cols) * cellH
        }
        contentH = y + 8 * dp
        sections = list
        scroll = scroll.coerceIn(0f, maxScroll)
    }

    // ------------------------------------------------------------------ animation

    /** Advances animations; returns true while something is still moving. */
    fun step(dt: Float): Boolean {
        var moving = false
        if (springing) {
            moving = spring.step(dt)
            scroll = spring.value
            if (!moving) springing = false
        } else if (momentum != 0f) {
            // iOS scroll deceleration: velocity decays by 0.998 per millisecond.
            momentum *= 0.998f.pow(dt * 1000f)
            scroll += momentum * dt
            if (scroll < 0f || scroll > maxScroll) {
                // Hit an end while moving: hand the velocity to a spring -> soft bounce.
                springing = true
                spring.start(scroll, momentum, scroll.coerceIn(0f, maxScroll))
                momentum = 0f
            } else if (abs(momentum) < 12f) momentum = 0f
            moving = true
        }
        val idx = currentSection().toFloat()
        if (indicator.target != idx) indicator.animateTo(idx)
        moving = indicator.step(dt) || moving
        moving = pressFade.step(dt) || moving
        return moving
    }

    private fun currentSection(): Int {
        var idx = 0
        for ((i, s) in sections.withIndex()) if (s.top <= scroll + headerH) idx = i
        if (scroll >= maxScroll - 1 && maxScroll > 0) idx = sections.size - 1
        return idx
    }

    // ------------------------------------------------------------------ drawing

    fun draw(canvas: Canvas, alpha: Float) {
        val a = (alpha * 255).toInt()
        canvas.save()
        canvas.clipRect(grid)
        for (s in sections) {
            val sy = grid.top + s.top - scroll
            if (sy > grid.bottom) break
            val rows = (s.emojis.size + cols - 1) / cols
            if (sy + headerH + rows * cellH < grid.top) continue
            // Section titles: small, a little letter-spacing for legibility.
            text.textAlign = Paint.Align.LEFT
            text.textSize = 12.5f * dp
            text.letterSpacing = 0.04f
            text.color = withAlpha(colors.sub, alpha)
            canvas.drawText(s.title.uppercase(), grid.left + 12 * dp, sy + headerH * 0.68f, text)
            text.textAlign = Paint.Align.CENTER
            text.letterSpacing = 0f
            text.textSize = minOf(cellH * 0.62f, 30 * dp)
            text.alpha = a
            for ((i, e) in s.emojis.withIndex()) {
                val cx = grid.left + (i % cols + 0.5f) * cellW
                val cy = sy + headerH + (i / cols + 0.5f) * cellH
                if (cy + cellH < grid.top || cy - cellH > grid.bottom) continue
                if (e == pressedEmoji && pressedRect != null) {
                    fill.color = withAlpha(colors.pressed, pressFade.value * alpha)
                    val r = 6 * dp
                    canvas.drawRoundRect(cx - cellW / 2 + 2 * dp, cy - cellH / 2 + 2 * dp, cx + cellW / 2 - 2 * dp,
                        cy + cellH / 2 - 2 * dp, r, r, fill)
                }
                canvas.drawText(e, cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
        }
        canvas.restore()

        // Soft scroll-edge fades instead of hard divider lines.
        val fadeH = 10 * dp
        fill.shader = LinearGradient(0f, grid.top, 0f, grid.top + fadeH, withAlpha(colors.bg, alpha),
            withAlpha(colors.bg, 0f), Shader.TileMode.CLAMP)
        canvas.drawRect(grid.left, grid.top, grid.right, grid.top + fadeH, fill)
        fill.shader = LinearGradient(0f, grid.bottom - fadeH, 0f, grid.bottom, withAlpha(colors.bg, 0f),
            withAlpha(colors.bg, alpha), Shader.TileMode.CLAMP)
        canvas.drawRect(grid.left, grid.bottom - fadeH, grid.right, grid.bottom, fill)
        fill.shader = null

        drawBar(canvas, alpha)
    }

    private fun barSlots(): Int = sections.size + 2 // ABC + categories + delete

    private fun slotRect(i: Int): RectF {
        val w = area.width() / barSlots()
        return RectF(area.left + i * w, area.bottom - barH, area.left + (i + 1) * w, area.bottom)
    }

    private fun drawBar(canvas: Canvas, alpha: Float) {
        // Category pill glides to the visible section.
        val first = slotRect(1)
        val w = first.width()
        val px = first.left + indicator.value * w
        fill.color = withAlpha(colors.special, alpha * 0.7f)
        val inset = 5 * dp
        canvas.drawRoundRect(px + inset, first.top + inset, px + w - inset, first.bottom - inset, 9 * dp, 9 * dp, fill)

        for (i in 0 until barSlots()) {
            val r = slotRect(i)
            val label = when (i) {
                0 -> "ABC"
                barSlots() - 1 -> "⌫"
                else -> sections[i - 1].icon
            }
            text.textAlign = Paint.Align.CENTER
            text.color = withAlpha(colors.text, alpha)
            text.letterSpacing = if (i == 0) 0.02f else 0f
            text.textSize = if (i == 0) 14 * dp else 19 * dp
            if (i == barSlots() - 1 && deletePressed) {
                fill.color = withAlpha(colors.pressed, alpha)
                canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, 9 * dp, 9 * dp, fill)
            }
            canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
        text.letterSpacing = 0f
    }

    // ------------------------------------------------------------------ touch

    fun onTouch(e: MotionEvent) {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y >= grid.bottom) {
                    barDown(x)
                    return
                }
                // Grab: stop any momentum/spring exactly where it is (interruptible).
                momentum = 0f
                springing = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y
                dragStartScroll = scroll
                dragging = false
                pressedEmoji = emojiAt(x, y)
                pressedRect = if (pressedEmoji != null) RectF() else null
                if (pressedEmoji != null) {
                    pressFade.snap(1f) // highlight instantly on touch-down
                    host.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracker == null) return
                tracker?.addMovement(e)
                if (!dragging && abs(y - downY) > 8 * dp) {
                    dragging = true
                    pressFade.animateTo(0f)
                    downY = y // start tracking from here: no jump after the slop
                }
                if (dragging) {
                    val raw = dragStartScroll - (y - downY)
                    scroll = when {
                        raw < 0f -> -rubberband(-raw, grid.height())
                        raw > maxScroll -> maxScroll + rubberband(raw - maxScroll, grid.height())
                        else -> raw
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (deletePressed) { barUp(); return }
                val t = tracker ?: return
                t.addMovement(e)
                t.computeCurrentVelocity(1000)
                val v = -t.yVelocity
                t.recycle(); tracker = null
                if (dragging) {
                    if (scroll < 0f || scroll > maxScroll) {
                        springing = true
                        spring.start(scroll, v, scroll.coerceIn(0f, maxScroll))
                    } else {
                        momentum = if (abs(v) > 50f) v else 0f
                    }
                } else {
                    pressedEmoji?.let { listener.onEmoji(it) }
                    pressFade.animateTo(0f)
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                tracker?.recycle(); tracker = null
                dragging = false
                pressFade.animateTo(0f)
                if (deletePressed) barUp()
                if (scroll < 0f || scroll > maxScroll) {
                    springing = true
                    spring.start(scroll, 0f, scroll.coerceIn(0f, maxScroll))
                }
            }
        }
    }

    private fun barDown(x: Float) {
        val i = ((x - area.left) / (area.width() / barSlots())).toInt().coerceIn(0, barSlots() - 1)
        host.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (i) {
            0 -> listener.onCloseEmoji()
            barSlots() - 1 -> {
                deletePressed = true
                deleteRepeat = 0
                listener.onDelete(0)
                host.postDelayed(repeater, 400)
            }
            else -> {
                // Jump to the category with a critically damped spring, from where we are now.
                momentum = 0f
                springing = true
                spring.start(scroll, 0f, sections[i - 1].top.coerceIn(0f, maxScroll))
            }
        }
    }

    private fun barUp() {
        deletePressed = false
        host.removeCallbacks(repeater)
    }

    private fun emojiAt(x: Float, y: Float): String? {
        val cy = y - grid.top + scroll
        for (s in sections) {
            val start = s.top + headerH
            val rows = (s.emojis.size + cols - 1) / cols
            if (cy >= start && cy < start + rows * cellH) {
                val idx = ((cy - start) / cellH).toInt() * cols + ((x - grid.left) / cellW).toInt().coerceIn(0, cols - 1)
                return s.emojis.getOrNull(idx)
            }
        }
        return null
    }

    /** Apple's rubber-band: the further past the edge, the less the content follows. */
    private fun rubberband(overshoot: Float, dimension: Float, c: Float = 0.55f): Float =
        (overshoot * dimension * c) / (dimension + c * overshoot)
}
