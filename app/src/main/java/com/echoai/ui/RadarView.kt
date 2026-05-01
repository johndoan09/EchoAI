package com.echoai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.echoai.R
import com.echoai.domain.SoundEvent
import com.echoai.domain.Urgency
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 2-D device-frame radar — **placeholder**.
 *
 * Visual structure follows the design handoff:
 *   - 4 concentric circular rings (25 / 50 / 75 / 100 % of the available radius)
 *   - vertical + horizontal axis lines
 *   - FRONT / REAR / L / R directional labels
 *   - center pip (dark circle + white inner dot)
 *   - rotating sweep + 3 staggered pulse rings while listening
 *
 * SoundEvent locations are plotted using device-frame azimuth + front/back bias.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

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
    private val eventHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val eventDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

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
        invalidate()
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
        val maxRadius = min((width / 2f) - labelInset, (height / 2f) - labelInset)
        if (maxRadius <= 0f) return

        // Circular rings
        for (frac in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
            canvas.drawCircle(cx, cy, maxRadius * frac, ringPaint)
        }

        // Axes
        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, axisPaint)
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, axisPaint)

        // Direction labels
        val baseline = labelPaint.fontMetrics
        val textOffset = (-(baseline.ascent + baseline.descent)) / 2f
        canvas.drawText("FRONT", cx, cy - maxRadius - dp(8f), labelPaint)
        canvas.drawText("REAR", cx, cy + maxRadius + dp(16f), labelPaint)
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
            val sx = cx + maxRadius * Math.cos(sweepRad).toFloat()
            val sy = cy + maxRadius * Math.sin(sweepRad).toFloat()
            canvas.drawLine(cx, cy, sx, sy, sweepPaint)
        }

        drawEventDots(canvas, cx, cy, maxRadius)

        // Center pip
        canvas.drawCircle(cx, cy, dp(5f), pipFillPaint)
        canvas.drawCircle(cx, cy, dp(2f), pipInnerPaint)
    }

    private fun drawEventDots(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        if (!listening || events.isEmpty()) return

        events.take(MAX_VISIBLE_EVENTS).forEachIndexed { index, event ->
            val az = event.devicePosition.azimuthDegrees()
            val angleDeg = az ?: fallbackAngleFor(index)
            val frontBack = event.devicePosition.frontBackBias.coerceIn(-1f, 1f)
            val radius = (0.38f + kotlin.math.abs(frontBack) * 0.42f + index * 0.08f).coerceIn(0.32f, 0.88f)
            val rad = Math.toRadians((angleDeg - 90f).toDouble())
            val x = cx + radius * maxRadius * cos(rad).toFloat()
            val y = cy + radius * maxRadius * sin(rad).toFloat()
            val color = urgencyColor(event.urgency)
            val textColor = urgencyTextColor(event.urgency)

            eventHaloPaint.color = color
            eventHaloPaint.alpha = 38
            canvas.drawCircle(x, y, dp(10f), eventHaloPaint)
            eventDotPaint.color = color
            eventDotPaint.alpha = 255
            canvas.drawCircle(x, y, dp(5.6f), eventDotPaint)

            val label = event.label.take(18)
            val chipWidth = (chipTextPaint.measureText(label) + dp(16f)).coerceAtLeast(dp(42f))
            val chipHeight = dp(20f)
            val chipX = if (x < cx) x - chipWidth - dp(8f) else x + dp(8f)
            val chipY = if (y < cy) y - chipHeight - dp(8f) else y + dp(8f)
            val clampedX = chipX.coerceIn(dp(6f), width - chipWidth - dp(6f))
            val clampedY = chipY.coerceIn(dp(6f), height - chipHeight - dp(6f))

            chipPaint.color = color
            chipPaint.alpha = 36
            val rect = RectF(clampedX, clampedY, clampedX + chipWidth, clampedY + chipHeight)
            canvas.drawRoundRect(rect, chipHeight / 2f, chipHeight / 2f, chipPaint)
            chipTextPaint.color = textColor
            chipTextPaint.alpha = 255
            val fm = chipTextPaint.fontMetrics
            val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, rect.centerX(), baseline, chipTextPaint)
        }
    }

    private fun fallbackAngleFor(index: Int): Float = when (index % 4) {
        0 -> 35f
        1 -> 140f
        2 -> 225f
        else -> 315f
    }

    private fun urgencyColor(urgency: Urgency): Int = when (urgency) {
        Urgency.CRITICAL -> Color.rgb(214, 58, 47)
        Urgency.HIGH -> Color.rgb(212, 112, 10)
        Urgency.MEDIUM -> Color.rgb(168, 136, 10)
        Urgency.LOW -> Color.rgb(42, 127, 196)
    }

    private fun urgencyTextColor(urgency: Urgency): Int = when (urgency) {
        Urgency.CRITICAL -> Color.rgb(184, 46, 36)
        Urgency.HIGH -> Color.rgb(184, 92, 0)
        Urgency.MEDIUM -> Color.rgb(135, 108, 8)
        Urgency.LOW -> Color.rgb(31, 101, 160)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    companion object {
        private const val SWEEP_PERIOD_MS = 3000L
        private const val PULSE_PERIOD_MS = 2500L
        private const val MAX_VISIBLE_EVENTS = 4
    }
}
