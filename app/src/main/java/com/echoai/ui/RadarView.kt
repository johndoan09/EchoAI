package com.echoai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.echoai.R
import com.echoai.domain.SoundEvent
import kotlin.math.min

/**
 * 2-D device-frame radar — **placeholder**.
 *
 * Visual structure follows the design handoff:
 *   - 4 concentric elliptical rings (25 / 50 / 75 / 100 % of the available radius)
 *   - vertical + horizontal axis lines
 *   - FRONT / REAR / L / R directional labels
 *   - center pip (dark circle + white inner dot)
 *   - rotating sweep + 3 staggered pulse rings while listening
 *
 * The localization team is still working out how raw GCC-PHAT estimates should map onto
 * this surface, so this view intentionally does **not** plot the SoundEvent stream yet.
 * `setEvents(...)` keeps the stored list around so the wiring in MainActivity stays
 * intact; replace `drawEventDots()` once the mapping is finalized.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    @Suppress("unused")
    private var events: List<SoundEvent> = emptyList()

    private var listening: Boolean = false

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        letterSpacing = 0.06f
        alpha = 204
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.6f)
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val pipFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pipInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var sweepDeg: Float = 0f
    private var pulseProgress: Float = 0f

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SWEEP_PERIOD_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            sweepDeg = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PULSE_PERIOD_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulseProgress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    init {
        ringPaint.color = ContextCompat.getColor(context, R.color.radar_ring_idle)
        axisPaint.color = ContextCompat.getColor(context, R.color.radar_axis)
        labelPaint.color = ContextCompat.getColor(context, R.color.muted)
        sweepPaint.color = 0x59000000.toInt()      // rgba(0,0,0,0.35)
        pulsePaint.color = 0x80000000.toInt()      // rgba(0,0,0,0.5)
        pipFillPaint.color = ContextCompat.getColor(context, R.color.radar_pip)
        pipInnerPaint.color = ContextCompat.getColor(context, R.color.surface)
    }

    /** Wiring point for the localization team — kept on the API surface so the
     *  rest of the pipeline doesn't need to change when real plotting lands. */
    fun setEvents(events: List<SoundEvent>) {
        this.events = events
        // Intentional no-op for now — see class kdoc.
    }

    fun setListening(active: Boolean) {
        if (listening == active) return
        listening = active
        ringPaint.color = ContextCompat.getColor(
            context,
            if (active) R.color.radar_ring_active else R.color.radar_ring_idle,
        )
        if (active) {
            sweepAnimator.start()
            pulseAnimator.start()
        } else {
            sweepAnimator.cancel()
            pulseAnimator.cancel()
            sweepDeg = 0f
            pulseProgress = 0f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator.cancel()
        pulseAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val labelInset = dp(20f)
        val maxRx = (width / 2f) - labelInset
        val maxRy = (height / 2f) - labelInset
        if (maxRx <= 0f || maxRy <= 0f) return

        // Elliptical rings
        for (frac in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
            val rx = maxRx * frac
            val ry = maxRy * frac
            canvas.drawOval(RectF(cx - rx, cy - ry, cx + rx, cy + ry), ringPaint)
        }

        // Axes
        canvas.drawLine(cx, cy - maxRy, cx, cy + maxRy, axisPaint)
        canvas.drawLine(cx - maxRx, cy, cx + maxRx, cy, axisPaint)

        // Direction labels
        val baseline = labelPaint.fontMetrics
        val textOffset = (-(baseline.ascent + baseline.descent)) / 2f
        canvas.drawText("FRONT", cx, cy - maxRy - dp(8f), labelPaint)
        canvas.drawText("REAR", cx, cy + maxRy + dp(16f), labelPaint)
        val sideLabelPaint = Paint(labelPaint)
        sideLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("L", dp(2f), cy + textOffset, sideLabelPaint)
        sideLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("R", width - dp(2f), cy + textOffset, sideLabelPaint)

        if (listening) {
            // Pulse rings — 3 staggered animations from center
            val baseRadius = dp(6f)
            val maxScale = 3.2f
            for (i in 0..2) {
                val phase = ((pulseProgress + i / 3f) % 1f)
                val scale = lerp(0.2f, maxScale, phase)
                val alpha = ((1f - phase) * 0.8f * 255f).toInt().coerceIn(0, 255)
                pulsePaint.alpha = alpha
                canvas.drawCircle(cx, cy, baseRadius * scale, pulsePaint)
            }

            // Sweep line
            val sweepRad = Math.toRadians((sweepDeg - 90f).toDouble())
            val sx = cx + maxRx * Math.cos(sweepRad).toFloat()
            val sy = cy + maxRy * Math.sin(sweepRad).toFloat()
            canvas.drawLine(cx, cy, sx, sy, sweepPaint)
        }

        // Center pip
        canvas.drawCircle(cx, cy, dp(5f), pipFillPaint)
        canvas.drawCircle(cx, cy, dp(2f), pipInnerPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        private const val SWEEP_PERIOD_MS = 3000L
        private const val PULSE_PERIOD_MS = 2500L
    }
}
