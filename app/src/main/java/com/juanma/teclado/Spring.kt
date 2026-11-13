package com.juanma.teclado

import android.animation.ValueAnimator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * A spring described the way Apple describes them: [damping] ratio (1 = no overshoot)
 * and [response] (seconds, roughly how fast it gets there). Springs always start from
 * where they are *now* and keep their velocity when the target changes, so every
 * animation can be interrupted or reversed mid-flight without a jump.
 */
class Spring(
    value: Float,
    var damping: Float = 1f,
    var response: Float = 0.3f,
    /** Distance from the target that counts as "arrived" (in the value's own units). */
    private val precision: Float = 0.001f,
) {
    var value = value
        private set
    var target = value
        private set
    var velocity = 0f

    val atRest get() = value == target && velocity == 0f

    /** Animate towards [t] from the current value, optionally inheriting a gesture's [withVelocity]. */
    fun animateTo(t: Float, withVelocity: Float? = null) {
        target = t
        if (withVelocity != null) velocity = withVelocity
        if (!animationsEnabled()) snap(t)
    }

    /** Continue from [from] moving at [withVelocity] (velocity hand-off from a gesture). */
    fun start(from: Float, withVelocity: Float, to: Float) {
        value = from; velocity = withVelocity; target = to
        if (!animationsEnabled()) snap(to)
    }

    /** Jump instantly (used for things that must respond on touch-down, with zero latency). */
    fun snap(v: Float) {
        value = v; target = v; velocity = 0f
    }

    /** Advances [dt] seconds. Returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (atRest) return false
        val omega = (2 * PI / response).toFloat()
        val k = omega * omega
        val c = 2 * damping * omega
        var t = min(dt, 0.064f) // after a stall, don't jump far
        while (t > 0f) {
            val h = min(t, 1f / 240f)
            val a = -k * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            t -= h
        }
        if (abs(value - target) < precision && abs(velocity) < precision * 10) snap(target)
        return !atRest
    }

    companion object {
        /** Honour "remove animations" / animator duration scale 0 (reduced motion). */
        fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()
    }
}

/** Linear interpolation between two ARGB colours. */
fun lerpColor(a: Int, b: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * u).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}

fun withAlpha(color: Int, alpha: Float): Int =
    (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24) or (color and 0x00FFFFFF)
