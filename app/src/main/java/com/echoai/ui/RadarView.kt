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

    // Belief distribution from rotational-aperture localization. World-frame angles in
    // bins of (360 / size) degrees. phoneYawDegrees rotates the world-frame display into
    // device-frame: a fixed source stays anchored on the radar as the phone rotates.
    private var belief: FloatArray = FloatArray(0)
    private var phoneYawDegrees: Float = 0f
    private val beliefArcRect = android.graphics.RectF()

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
    private val beliefArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 36f
        strokeCap = Paint.Cap.BUTT
    }
    private val beliefPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFB347.toInt()
    }

    fun setEvents(events: List<SoundEvent>) {
        this.events = events
        invalidate()
    }

    /**
     * Update belief halo. [belief] is bin probabilities (length determines bin size in
     * degrees). [phoneYawDegrees] is the phone's current world-frame heading; passing it
     * lets the radar rotate world-frame angles into device frame so dots stay anchored
     * to the world as the phone rotates.
     */
    fun setBelief(belief: FloatArray, phoneYawDegrees: Float) {
        this.belief = belief
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

        drawBeliefHalo(canvas, cx, cy, r)

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
     * Draw the belief halo: an arc segment per bin colored by belief intensity, plus a
     * brighter peak marker at the argmax. Each bin's *world-frame* angle is rotated by
     * `-phoneYawDegrees` so it lands at the correct *device-frame* position on the radar
     * — i.e., as the phone rotates, the halo stays anchored to the world.
     */
    private fun drawBeliefHalo(canvas: android.graphics.Canvas, cx: Float, cy: Float, r: Float) {
        val n = belief.size
        if (n == 0) return
        val haloRadius = r * 0.95f
        beliefArcRect.set(cx - haloRadius, cy - haloRadius, cx + haloRadius, cy + haloRadius)
        val sweepDeg = 360f / n

        // Find peak for normalized opacity scaling.
        var peakBelief = 0f
        var peakIdx = 0
        for (i in belief.indices) {
            if (belief[i] > peakBelief) { peakBelief = belief[i]; peakIdx = i }
        }
        // Uniform belief is 1/n; only render bins meaningfully above that.
        val uniform = 1f / n
        if (peakBelief <= uniform * 1.05f) return  // distribution is essentially flat — skip halo

        for (i in belief.indices) {
            val b = belief[i]
            if (b <= uniform) continue
            // Map (b - uniform) → opacity in [0, 1] using peak as ceiling.
            val intensity = ((b - uniform) / (peakBelief - uniform)).coerceIn(0f, 1f)
            if (intensity < 0.05f) continue

            val worldAngle = i * sweepDeg
            // Convert world frame to device frame, then to canvas angle. Canvas drawArc
            // measures from +X axis (right) clockwise; we want 0° at TOP of radar (up),
            // 90° at RIGHT, etc. So canvasAngle = deviceAngle - 90°.
            val deviceAngle = worldAngle - phoneYawDegrees
            val canvasStart = deviceAngle - 90f - sweepDeg / 2f

            // Color: warm orange/red, alpha tied to intensity.
            val alpha = (intensity * 220f).toInt().coerceIn(0, 255)
            beliefArcPaint.color = (alpha shl 24) or 0x00FFB347
            canvas.drawArc(beliefArcRect, canvasStart, sweepDeg, false, beliefArcPaint)
        }

        // Peak marker: a small dot just inside the halo at the most-likely device angle.
        val peakWorldAngle = peakIdx * sweepDeg
        val peakDeviceAngle = peakWorldAngle - phoneYawDegrees
        val peakRad = Math.toRadians((peakDeviceAngle - 90f).toDouble())
        val px = cx + (haloRadius - 28f) * kotlin.math.cos(peakRad).toFloat()
        val py = cy + (haloRadius - 28f) * kotlin.math.sin(peakRad).toFloat()
        canvas.drawCircle(px, py, 12f, beliefPeakPaint)
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
