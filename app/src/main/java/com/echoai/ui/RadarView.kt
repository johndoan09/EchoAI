package com.echoai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.echoai.domain.SoundEvent
import kotlin.math.min
import kotlin.math.sin

/**
 * 2-D device-frame radar. Axes match the user's mental model when the phone is held
 * flat (screen up, camera facing ground):
 *  - Vertical axis (TOP edge ↔ BOT edge, long axis): driven by `bottomIld`, the
 *    within-pair RMS asymmetry that empirically captures long-axis source direction
 *    on this device (see CSV; ±0.6 swings on clear speech).
 *  - Horizontal axis (L ↔ R, short axis): held at center per-event. The phone's mic
 *    geometry has no usable short-axis baseline (top mics are 2 mm apart). Lateral
 *    direction is recovered via the IMU rotational halo around the perimeter, not from
 *    per-window per-event geometry.
 *
 * Each event renders as a dot on the long axis. The belief halo around the perimeter
 * shows world-frame source direction integrated over rotation.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var events: List<SoundEvent> = emptyList()

    // Vector-accumulator pointer: a single arrow toward the estimated world-frame source
    // direction, transformed into device frame using current phoneYaw. Replaces the
    // earlier per-bin Bayesian halo. Magnitude controls arrow length and brightness;
    // null direction = below confidence threshold, no pointer drawn.
    private var pointerDirectionDeg: Float? = null
    private var pointerMagnitudeNorm: Float = 0f  // expected to be in [0, 1] after caller normalization
    private var phoneYawDegrees: Float = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2A2F36.toInt()
        strokeWidth = 1.5f
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF3A4049.toInt()
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt()
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F6FEB.toInt()
        style = Paint.Style.FILL
    }
    private val userRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x551F6FEB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE7EBF0.toInt()
        textSize = 30f
        textAlign = Paint.Align.LEFT
    }
    private val dotLabelShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC0F1115.toInt()
        textSize = 30f
        textAlign = Paint.Align.LEFT
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val pointerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFB347.toInt()
    }
    private val pointerHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFB347.toInt()
    }

    fun setEvents(events: List<SoundEvent>) {
        this.events = events
        invalidate()
    }

    /**
     * Set the rotational-localizer pointer. [directionDeg] is the estimated world-frame
     * source direction in degrees [0, 360), or null to hide the pointer. [magnitudeNorm]
     * is the normalized confidence in [0, 1]; controls arrow length and opacity.
     * [phoneYawDegrees] is the current world-frame heading of the phone's TOP edge —
     * used to transform the world-frame direction into device-frame for display, so the
     * arrow stays anchored to the world as the phone rotates.
     */
    fun setPointer(directionDeg: Float?, magnitudeNorm: Float, phoneYawDegrees: Float) {
        this.pointerDirectionDeg = directionDeg
        this.pointerMagnitudeNorm = magnitudeNorm.coerceIn(0f, 1f)
        this.phoneYawDegrees = phoneYawDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) * 0.92f

        for (frac in floatArrayOf(0.33f, 0.66f, 1.0f)) {
            canvas.drawCircle(cx, cy, r * frac, ringPaint)
        }
        canvas.drawLine(cx - r, cy, cx + r, cy, axisPaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, axisPaint)

        canvas.drawText("TOP", cx, cy - r - 6f, labelPaint)
        canvas.drawText("BOT", cx, cy + r + 28f, labelPaint)
        canvas.drawText("L", cx - r - 14f, cy + 10f, labelPaint)
        canvas.drawText("R", cx + r + 14f, cy + 10f, labelPaint)

        drawPointer(canvas, cx, cy, r)

        canvas.drawCircle(cx, cy, 14f, userPaint)
        canvas.drawCircle(cx, cy, 22f, userRingPaint)

        for (event in events) {
            // The hardware's only usable directional signal is the within-pair ILD, which
            // empirically captures the phone's *long axis* (TOP↔BOT), not the short axis.
            // CSV evidence: bot_ild swings ±0.6 with strong long-axis sources, while
            // fb_bias is AGC-flattened to ±0.07. So:
            //  - Y axis (TOP↔BOT, long axis): bot_ild — the strong signal goes here.
            //  - X axis (L↔R, short axis): no usable hardware signal. Held at center.
            //    Lateral source direction is recovered via the IMU rotational halo, not
            //    from per-window mic-pair geometry.
            val xNorm = 0f
            val yNorm = (event.devicePosition.bottomIld * Y_SENSITIVITY)
                .coerceIn(-1f, 1f)
            val ex = cx + xNorm * r * 0.82f
            val ey = cy + yNorm * r * 0.82f

            val dotR = 14f + 22f * event.confidence.coerceIn(0f, 1f)
            dotPaint.color = colorForConfidence(event.confidence)
            canvas.drawCircle(ex, ey, dotR, dotPaint)

            val labelX = ex + dotR + 6f
            val labelY = ey + 10f
            canvas.drawText(event.label, labelX, labelY, dotLabelShadowPaint)
            canvas.drawText(event.label, labelX, labelY, dotLabelPaint)
        }
    }

    /**
     * Draw a single pointer arrow from the radar center toward the world-frame source
     * direction, transformed into device frame using `phoneYawDegrees`. As the phone
     * rotates, the arrow stays anchored to the world — visualizing what the rotational
     * accumulator believes is the source direction. Length and opacity scale with the
     * accumulator's normalized magnitude. Below the threshold (`pointerDirectionDeg ==
     * null`), nothing is drawn.
     */
    private fun drawPointer(canvas: android.graphics.Canvas, cx: Float, cy: Float, r: Float) {
        val direction = pointerDirectionDeg ?: return
        val intensity = pointerMagnitudeNorm
        if (intensity <= 0f) return

        // World→device frame, then to canvas convention (0° at top of radar = up).
        val deviceAngle = direction - phoneYawDegrees
        val canvasRad = Math.toRadians((deviceAngle - 90f).toDouble())

        // Length: minimum visible 35% of radius, scaling up to 90% at full magnitude.
        val minLen = r * 0.35f
        val maxLen = r * 0.90f
        val arrowLen = minLen + (maxLen - minLen) * intensity

        val tipX = cx + arrowLen * kotlin.math.cos(canvasRad).toFloat()
        val tipY = cy + arrowLen * kotlin.math.sin(canvasRad).toFloat()

        val alpha = (90 + 165 * intensity).toInt().coerceIn(60, 255)
        pointerLinePaint.alpha = alpha
        pointerLinePaint.strokeWidth = 6f + 8f * intensity
        canvas.drawLine(cx, cy, tipX, tipY, pointerLinePaint)

        pointerHeadPaint.alpha = alpha
        canvas.drawCircle(tipX, tipY, 12f + 10f * intensity, pointerHeadPaint)
    }

    private fun colorForConfidence(c: Float): Int {
        val clamp = c.coerceIn(0f, 1f)
        // Blue (240°) → cyan → green → yellow → red (0°)
        val hue = 240f * (1f - clamp)
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
    }

    companion object {
        /** Amplifier on `bot_ild` before clamping for the Y axis. CSV shows confident
         *  detections with bot_ild ~±0.6, so 1.5× lets typical sources reach ~±0.9 on the
         *  radar without saturating. */
        private const val Y_SENSITIVITY = 1.5f
    }
}
