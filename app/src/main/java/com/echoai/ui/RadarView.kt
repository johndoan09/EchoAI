package com.echoai.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.echoai.domain.SoundEvent
import kotlin.math.min
import kotlin.math.sin

/**
 * 2-D device-frame radar.
 *  - Vertical axis: front (top) ↔ back (bottom), driven by `frontBackBias`.
 *  - Horizontal axis: left ↔ right, driven by `azimuthDegrees`.
 *  - Each event is a dot colored by urgency tier, sized by confidence.
 *
 * Currently device-frame only. When `WorldOrientationProvider` is wired, transform
 * each event's stored device-frame position into world frame and rotate the radar
 * background instead of the dots, so sources stay fixed as the user rotates the phone.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var events: List<SoundEvent> = emptyList()

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

    fun setEvents(events: List<SoundEvent>) {
        this.events = events
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

        canvas.drawText("FRONT", cx, cy - r - 6f, labelPaint)
        canvas.drawText("BACK", cx, cy + r + 28f, labelPaint)
        canvas.drawText("L", cx - r - 14f, cy + 10f, labelPaint)
        canvas.drawText("R", cx + r + 14f, cy + 10f, labelPaint)

        canvas.drawCircle(cx, cy, 14f, userPaint)
        canvas.drawCircle(cx, cy, 22f, userRingPaint)

        for (event in events) {
            val az = event.devicePosition.azimuthDegrees() ?: 0f
            val xNorm = sin(Math.toRadians(az.toDouble())).toFloat()
            // CAMCORDER's per-recorder AGC suppresses raw RMS bias to ±0.05–0.15 in
            // typical use; amplify here so the y-axis is visually meaningful. The raw
            // (unamplified) bias is logged in the diagnostics CSV for honest analysis.
            val yNorm = (-event.devicePosition.frontBackBias * FRONT_BACK_SENSITIVITY)
                .coerceIn(-1f, 1f)
            val ex = cx + xNorm * r * 0.82f
            val ey = cy + yNorm * r * 0.82f

            val dotR = 14f + 22f * event.confidence.coerceIn(0f, 1f)
            dotPaint.color = event.urgency.color
            dotPaint.alpha = 255
            canvas.drawCircle(ex, ey, dotR, dotPaint)

            val labelX = ex + dotR + 6f
            val labelY = ey + 10f
            canvas.drawText(event.label, labelX, labelY, dotLabelShadowPaint)
            canvas.drawText(event.label, labelX, labelY, dotLabelPaint)
        }
    }

    companion object {
        /** Multiplier applied to frontBackBias before clamping. CSV analysis shows raw bias
         *  rarely exceeds ±0.17 (CAMCORDER AGC normalizes per-recorder), so 8× uses more of
         *  the radar y-range. Note: this can't fix the underlying signal weakness — when both
         *  arrays are roughly equidistant from the source, RMS bias is genuinely small.
         *  Spectral-shadow front/back is the proper next signal source. */
        private const val FRONT_BACK_SENSITIVITY = 8f
    }
}
