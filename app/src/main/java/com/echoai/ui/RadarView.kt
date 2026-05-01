package com.echoai.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.echoai.R
import com.echoai.domain.SoundEvent
import com.echoai.domain.Urgency
import com.echoai.sensor.WorldOrientationProvider
import kotlin.math.abs
import kotlin.math.min

/**
 * 2-D device-frame radar combining the design-handoff chrome with the rotational-aperture
 * belief halo from the imu-bayesian-belief branch.
 *
 * Visual structure:
 *   - 4 concentric circular rings + axis lines + FRONT/REAR/L/R labels (design handoff)
 *   - rotating sweep while listening (design handoff)
 *   - belief halo arc segments + peak arrow around the perimeter, world-frame anchored
 *     via the IMU yaw so a fixed source stays put as the phone rotates
 *   - per-event dots positioned along the long axis only (Y = bottomIld, X = center).
 *     The phone's mic geometry has no usable short-axis baseline; lateral direction is
 *     recovered through the IMU rotational halo, not from per-window per-event geometry.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var events: List<SoundEvent> = emptyList()
    private var listening: Boolean = false

    // Belief distribution from rotational-aperture localization. World-frame angles in
    // bins of (360 / size) degrees. phoneYawDegrees rotates the world-frame display into
    // device-frame: a fixed source stays anchored on the radar as the phone rotates.
    private var belief: FloatArray = FloatArray(0)
    private var phoneYawDegrees: Float = 0f
    private var peakWorldAngle: Float = 0f
    private val beliefArcRect = RectF()
    private val arrowPath = Path()

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
    private val beliefArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(11f)
        strokeCap = Paint.Cap.BUTT
    }
    private val beliefPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFB347.toInt()
    }

    private var sweepDeg: Float = 0f

    private val sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SWEEP_PERIOD_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            sweepDeg = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }
    private var orientationProvider: WorldOrientationProvider? = null
    private var yawRefreshScheduled = false
    private val yawCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            yawRefreshScheduled = false
            val provider = orientationProvider ?: return
            val newYaw = provider.yawDegrees() ?: phoneYawDegrees
            if (abs(angularDelta(newYaw, phoneYawDegrees)) > YAW_INVALIDATE_THRESHOLD_DEG) {
                phoneYawDegrees = newYaw
                invalidate()
            }
            scheduleYawRefresh()
        }
    }

    init {
        ringPaint.color = ContextCompat.getColor(context, R.color.radar_ring_idle)
        axisPaint.color = ContextCompat.getColor(context, R.color.radar_axis)
        labelPaint.color = ContextCompat.getColor(context, R.color.muted)
        sweepPaint.color = 0x59000000.toInt()      // rgba(0,0,0,0.35)
        pipFillPaint.color = ContextCompat.getColor(context, R.color.radar_pip)
        pipInnerPaint.color = ContextCompat.getColor(context, R.color.surface)
    }

    fun setEvents(events: List<SoundEvent>) {
        this.events = events
        invalidate()
    }

    /**
     * Update belief halo. [belief] is bin probabilities (length determines bin size in
     * degrees). [phoneYawDegrees] is the phone's current world-frame heading; passing it
     * lets the radar rotate world-frame angles into device frame so dots stay anchored
     * to the world as the phone rotates. [peakWorldAngle] is the smoothed peak direction
     * (world-frame degrees) used for the peak marker dot.
     */
    fun setBelief(belief: FloatArray, phoneYawDegrees: Float, peakWorldAngle: Float = 0f) {
        this.belief = belief
        this.phoneYawDegrees = phoneYawDegrees
        this.peakWorldAngle = peakWorldAngle
        invalidate()
    }

    /**
     * Combined setter: events + belief + yaw + peak in a single update with one
     * [invalidate]. Used by the live pipeline path to avoid two consecutive layouts
     * per audio window.
     */
    fun setData(
        events: List<SoundEvent>,
        belief: FloatArray,
        phoneYawDegrees: Float,
        peakWorldAngle: Float,
    ) {
        this.events = events
        this.belief = belief
        this.phoneYawDegrees = phoneYawDegrees
        this.peakWorldAngle = peakWorldAngle
        invalidate()
    }

    /**
     * Wire an [WorldOrientationProvider] to drive the halo rotation at display rate
     * (~60 fps) instead of the audio pipeline rate. Call with `null` to stop the
     * continuous refresh (e.g., when capture stops). Between audio-pipeline frames the
     * belief / events / peak stay frozen; only the world-to-device-frame rotation is
     * refreshed, so the halo and peak marker stay anchored to the world as the phone
     * rotates.
     */
    fun setOrientationProvider(provider: WorldOrientationProvider?) {
        orientationProvider = provider
        if (provider != null && isAttachedToWindow) {
            startYawRefresh()
        } else {
            stopYawRefresh()
        }
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
        } else {
            sweepAnimator.cancel()
            sweepDeg = 0f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (orientationProvider != null) startYawRefresh()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        stopYawRefresh()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val cx = width / 2f
        val cy = height / 2f
        val labelInset = dp(20f)
        val maxRadius = min((width / 2f) - labelInset, (height / 2f) - labelInset)
        if (maxRadius <= 0f) return

        for (frac in floatArrayOf(0.25f, 0.5f, 0.75f, 1f)) {
            canvas.drawCircle(cx, cy, maxRadius * frac, ringPaint)
        }

        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, axisPaint)
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, axisPaint)

        val baseline = labelPaint.fontMetrics
        val textOffset = (-(baseline.ascent + baseline.descent)) / 2f
        canvas.drawText("FRONT", cx, cy - maxRadius - dp(8f), labelPaint)
        canvas.drawText("REAR", cx, cy + maxRadius + dp(16f), labelPaint)
        val sideLabelPaint = Paint(labelPaint)
        sideLabelPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("L", dp(2f), cy + textOffset, sideLabelPaint)
        sideLabelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("R", width - dp(2f), cy + textOffset, sideLabelPaint)

        // Belief halo sits behind the sweep / event dots so interactive elements remain on top.
        drawBeliefHalo(canvas, cx, cy, maxRadius)

        if (listening) {
            val sweepRad = Math.toRadians((sweepDeg - 90f).toDouble())
            val sx = cx + maxRadius * Math.cos(sweepRad).toFloat()
            val sy = cy + maxRadius * Math.sin(sweepRad).toFloat()
            canvas.drawLine(cx, cy, sx, sy, sweepPaint)
        }

        drawEventDots(canvas, cx, cy, maxRadius)

        canvas.drawCircle(cx, cy, dp(5f), pipFillPaint)
        canvas.drawCircle(cx, cy, dp(2f), pipInnerPaint)
    }

    private fun drawEventDots(canvas: Canvas, cx: Float, cy: Float, maxRadius: Float) {
        if (!listening || events.isEmpty()) return

        events.take(MAX_VISIBLE_EVENTS).forEachIndexed { index, event ->
            // The hardware's only usable directional signal is the within-pair ILD, which
            // empirically captures the phone's *long axis* (TOP↔BOT). The X axis (L↔R)
            // has no usable hardware baseline (top mics are 2 mm apart), so dots are held
            // at center and lateral source direction is recovered via the IMU halo.
            val xNorm = 0f
            val yNorm = (event.devicePosition.bottomIld * Y_SENSITIVITY).coerceIn(-1f, 1f)
            // Slight per-event vertical jitter so multiple events don't perfectly overlap.
            val stagger = ((index % 2) * 2 - 1) * (index / 2) * 0.08f
            val x = cx + xNorm * maxRadius * 0.82f
            val y = cy + (yNorm + stagger).coerceIn(-1f, 1f) * maxRadius * 0.82f
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
            val textBaseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, rect.centerX(), textBaseline, chipTextPaint)
        }
    }

    /**
     * Draw the belief halo: an arc segment per bin colored by belief intensity, plus a
     * brighter peak marker at the smoothed peak angle. Each bin's *world-frame* angle is
     * rotated by `-phoneYawDegrees` so it lands at the correct *device-frame* position on
     * the radar — i.e., as the phone rotates, the halo stays anchored to the world.
     */
    private fun drawBeliefHalo(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val n = belief.size
        if (n == 0) return
        val haloRadius = r * 0.95f
        beliefArcRect.set(cx - haloRadius, cy - haloRadius, cx + haloRadius, cy + haloRadius)
        val binSweepDeg = 360f / n

        var peakBelief = 0f
        for (i in belief.indices) {
            if (belief[i] > peakBelief) peakBelief = belief[i]
        }
        val uniform = 1f / n
        if (peakBelief <= uniform * 1.05f) return

        for (i in belief.indices) {
            val b = belief[i]
            if (b <= uniform) continue
            val intensity = ((b - uniform) / (peakBelief - uniform)).coerceIn(0f, 1f)
            if (intensity < 0.05f) continue

            val worldAngle = i * binSweepDeg
            val deviceAngle = worldAngle - phoneYawDegrees
            val canvasStart = deviceAngle - 90f - binSweepDeg / 2f

            val alpha = (intensity * 220f).toInt().coerceIn(0, 255)
            beliefArcPaint.color = (alpha shl 24) or 0x00FFB347
            canvas.drawArc(beliefArcRect, canvasStart, binSweepDeg, false, beliefArcPaint)
        }

        // Peak marker: a radial triangular arrow pointing outward from the user toward
        // the world-frame source direction (rotated into device frame).
        val peakDeviceAngle = peakWorldAngle - phoneYawDegrees
        val peakRad = Math.toRadians((peakDeviceAngle - 90f).toDouble())
        val cosA = kotlin.math.cos(peakRad).toFloat()
        val sinA = kotlin.math.sin(peakRad).toFloat()
        val tipR = haloRadius - dp(2f)
        val baseR = haloRadius - dp(11f)
        val halfWidth = dp(4f)
        val tipX = cx + tipR * cosA
        val tipY = cy + tipR * sinA
        val baseCx = cx + baseR * cosA
        val baseCy = cy + baseR * sinA
        val perpX = -sinA
        val perpY = cosA
        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(baseCx + halfWidth * perpX, baseCy + halfWidth * perpY)
        arrowPath.lineTo(baseCx - halfWidth * perpX, baseCy - halfWidth * perpY)
        arrowPath.close()
        canvas.drawPath(arrowPath, beliefPeakPaint)
    }

    private fun scheduleYawRefresh() {
        if (yawRefreshScheduled || orientationProvider == null) return
        yawRefreshScheduled = true
        Choreographer.getInstance().postFrameCallback(yawCallback)
    }

    private fun startYawRefresh() = scheduleYawRefresh()

    private fun stopYawRefresh() {
        if (yawRefreshScheduled) {
            Choreographer.getInstance().removeFrameCallback(yawCallback)
            yawRefreshScheduled = false
        }
    }

    /** Shortest signed arc from [from] to [to], in (-180, 180]. */
    private fun angularDelta(to: Float, from: Float): Float {
        var d = to - from
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
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

    companion object {
        private const val SWEEP_PERIOD_MS = 3000L
        private const val MAX_VISIBLE_EVENTS = 4

        /** Amplifier on `bot_ild` before clamping for the Y axis. CSV shows confident
         *  detections with bot_ild ~±0.6, so 1.5× lets typical sources reach ~±0.9 on the
         *  radar without saturating. */
        private const val Y_SENSITIVITY = 1.5f

        /** Minimum yaw change (degrees) before the choreographer-driven refresh issues
         *  a redraw. Prevents continuous invalidates while the phone is stationary. */
        private const val YAW_INVALIDATE_THRESHOLD_DEG = 0.5f
    }
}
