package com.jarvis.agent.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * The face of the agent: a reactor core that breathes when idle, swells with the user's
 * voice while listening, spins while thinking and ripples while speaking.
 *
 * Drawn by hand rather than animated with a library so it stays one file and no dependency,
 * and so the microphone level can drive it frame by frame.
 */
class ReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    /** Pulled every frame so the core follows the microphone without listener spam. */
    var levelProvider: (() -> Float)? = null

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()

    private var smoothedLevel = 0f
    private var phase = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - dp(6f)
        if (radius <= 0f) return

        val target = (levelProvider?.invoke() ?: 0f).coerceIn(0f, 1f)
        smoothedLevel += (target - smoothedLevel) * 0.22f
        phase += 0.016f

        val accent = accentColor()
        val energy = when (mode) {
            Mode.IDLE -> 0.10f + 0.03f * sin(phase * 1.2f)
            Mode.LISTENING -> 0.20f + smoothedLevel * 0.55f
            Mode.THINKING -> 0.28f + 0.10f * sin(phase * 5f)
            Mode.SPEAKING -> 0.32f + 0.14f * sin(phase * 9f)
            Mode.ERROR -> 0.26f
        }

        // Outer halo.
        halo.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(alpha(accent, (70 + energy * 120).toInt()), alpha(accent, 0)),
            floatArrayOf(0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, halo)

        // Static rings.
        ring.strokeWidth = dp(1f)
        ring.color = alpha(accent, 55)
        canvas.drawCircle(cx, cy, radius * 0.94f, ring)
        ring.color = alpha(accent, 28)
        canvas.drawCircle(cx, cy, radius * 0.72f, ring)

        // Tick marks around the rim, like an instrument dial.
        tick.color = alpha(accent, 70)
        val tickCount = 48
        for (i in 0 until tickCount) {
            val angle = (i.toFloat() / tickCount) * 2f * Math.PI.toFloat()
            val lit = (i % 6 == 0)
            val r0 = radius * if (lit) 0.96f else 0.98f
            val px = cx + r0 * kotlin.math.cos(angle)
            val py = cy + r0 * sin(angle)
            canvas.drawCircle(px, py, dp(if (lit) 1.6f else 0.9f), tick)
        }

        // Counter-rotating arcs.
        val spin = (phase * 55f) % 360f
        arc.color = accent
        arc.strokeWidth = dp(3f)
        setBox(cx, cy, radius * 0.84f)
        canvas.drawArc(box, spin, 64f, false, arc)
        canvas.drawArc(box, spin + 180f, 64f, false, arc)

        arc.color = alpha(accent, 150)
        arc.strokeWidth = dp(2f)
        setBox(cx, cy, radius * 0.63f)
        canvas.drawArc(box, -spin * 1.7f, 100f, false, arc)
        canvas.drawArc(box, -spin * 1.7f + 175f, 40f, false, arc)

        // The core itself.
        val coreRadius = (radius * (0.26f + energy * 0.48f)).coerceAtLeast(dp(6f))
        core.shader = RadialGradient(
            cx, cy, coreRadius,
            intArrayOf(alpha(Color.WHITE, 225), accent, alpha(accent, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, core)

        if (isShown) postInvalidateOnAnimation()
    }

    private fun setBox(cx: Float, cy: Float, r: Float) {
        box.set(cx - r, cy - r, cx + r, cy + r)
    }

    private fun accentColor(): Int = when (mode) {
        Mode.IDLE -> 0xFF2E7F94.toInt()
        Mode.LISTENING -> 0xFF35E0F5.toInt()
        Mode.THINKING -> 0xFF9A7BFF.toInt()
        Mode.SPEAKING -> 0xFF43E08A.toInt()
        Mode.ERROR -> 0xFFFF5F6D.toInt()
    }

    private fun alpha(color: Int, a: Int): Int = Color.argb(
        a.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
