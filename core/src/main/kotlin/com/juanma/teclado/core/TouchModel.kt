package com.juanma.teclado.core

import kotlin.math.ln

/** A key's visible rectangle on screen (pixels). [letter] is null for non-adaptive keys. */
class KeyBox(
    val index: Int,
    val letter: Char?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val w get() = right - left
    val h get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * Decides which key a touch belongs to, the way iOS does it: the drawn keys never
 * change, but the invisible touch areas of the letters that are likely to come next
 * grow, and those of unlikely letters shrink.
 *
 * Each letter key is modelled as a 2D Gaussian centred on the key (shifted by where
 * this particular user tends to hit it). The winning key maximises
 *     log P(touch | key) + priorWeight · log P(key | letters typed so far)
 *
 * Safety rails so it never feels "wrong":
 *  - a touch in the central [core] area of a letter always produces that letter;
 *  - keys like space, shift or delete keep exactly their drawn shape;
 *  - a key can only steal touches within [maxReach] of its own edge.
 */
class TouchModel {
    var enabled = true
    var sigma = 0.45f        // std-dev of touches, in key widths/heights
    var priorWeight = 0.75f
    var core = 0.30f         // half-size of the protected centre, in key sizes (0.5 = whole key)
    var maxReach = 0.32f     // how far beyond its edge a key can grow, in key sizes
    var learningRate = 0.04f

    /** Per-letter personal offset of the user's touches (fraction of key size). */
    private val offsets = HashMap<Char, FloatArray>()

    fun resolve(x: Float, y: Float, keys: List<KeyBox>, prior: Map<Char, Float>?): KeyBox? {
        val visible = keys.firstOrNull { it.contains(x, y) }
        if (visible != null && visible.letter == null) return visible
        if (!enabled) return visible ?: nearest(x, y, keys)
        if (visible != null && inCore(visible, x, y)) return visible

        var best: KeyBox? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (k in keys) {
            val c = k.letter ?: continue
            val reachX = k.w * maxReach
            val reachY = k.h * maxReach
            if (x < k.left - reachX || x >= k.right + reachX || y < k.top - reachY || y >= k.bottom + reachY) continue
            val score = score(k, c, x, y, prior)
            if (score > bestScore) {
                bestScore = score
                best = k
            }
        }
        return best ?: visible ?: nearest(x, y, keys)
    }

    private fun score(k: KeyBox, c: Char, x: Float, y: Float, prior: Map<Char, Float>?): Float {
        val off = offsets[c]
        val mx = k.cx + (off?.get(0) ?: 0f) * k.w
        val my = k.cy + (off?.get(1) ?: 0f) * k.h
        val dx = (x - mx) / (sigma * k.w)
        val dy = (y - my) / (sigma * k.h)
        var s = -0.5f * (dx * dx + dy * dy)
        if (prior != null) {
            val p = prior[c] ?: 0f
            s += priorWeight * ln(p.coerceAtLeast(MIN_PRIOR))
        }
        return s
    }

    private fun inCore(k: KeyBox, x: Float, y: Float): Boolean {
        val hx = k.w * core
        val hy = k.h * core
        return x >= k.cx - hx && x <= k.cx + hx && y >= k.cy - hy && y <= k.cy + hy
    }

    private fun nearest(x: Float, y: Float, keys: List<KeyBox>): KeyBox? = keys.minByOrNull {
        val dx = x - x.coerceIn(it.left, it.right)
        val dy = y - y.coerceIn(it.top, it.bottom)
        dx * dx + dy * dy
    }

    /** Called for touches of a word the user accepted as typed: adapts to their aim. */
    fun learn(key: KeyBox, x: Float, y: Float) {
        val c = key.letter ?: return
        val o = offsets.getOrPut(c) { FloatArray(2) }
        val ox = ((x - key.cx) / key.w).coerceIn(-0.5f, 0.5f)
        val oy = ((y - key.cy) / key.h).coerceIn(-0.5f, 0.5f)
        o[0] = (o[0] + learningRate * (ox - o[0])).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        o[1] = (o[1] + learningRate * (oy - o[1])).coerceIn(-MAX_OFFSET, MAX_OFFSET)
    }

    fun exportOffsets(): String = buildString {
        for ((c, o) in offsets) append(c).append('\t').append(o[0]).append('\t').append(o[1]).append('\n')
    }

    fun importOffsets(text: String) {
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 3 || p[0].length != 1) continue
            val x = p[1].toFloatOrNull() ?: continue
            val y = p[2].toFloatOrNull() ?: continue
            offsets[p[0][0]] = floatArrayOf(x, y)
        }
    }

    fun clearOffsets() = offsets.clear()

    companion object {
        const val MIN_PRIOR = 0.004f
        const val MAX_OFFSET = 0.25f
    }
}
