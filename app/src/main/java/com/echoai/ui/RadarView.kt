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

    // One halo per active YAMNet label. Each halo carries its own bin probabilities
    // (world-frame angles in bins of 360 / snapshot.size degrees), world-frame peak
    // angle, and the urgency color of the corresponding SoundEvent for tinting.
    // phoneYawDegrees rotates the world-frame display into device-frame so a fixed
    // source stays anchored on the radar as the phone rotates.
    data class LabelHalo(
        val label: String,
        val snapshot: FloatArray,
        val peakWorldAngle: Float,
        val urgencyColor: Int,
    )

    private var halos: List<LabelHalo> = emptyList()
    private var halosByLabel: Map<String, LabelHalo> = emptyMap()
    private var phoneYawDegrees: Float = 0f
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
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val beliefArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(BELIEF_ARC_STROKE_DP)
        strokeCap = Paint.Cap.BUTT
    }
    private val beliefPeakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
     * Combined setter: events + per-label halos + yaw in a single update with one
     * [invalidate]. Used by the live pipeline path to avoid two consecutive layouts
     * per audio window. Each [LabelHalo] carries its own world-frame bin distribution,
     * smoothed peak, and tint color (the corresponding event's urgency color). The
     * radar rotates world-frame angles into device frame via [phoneYawDegrees] so
     * halos stay anchored to the world as the phone rotates.
     */
    fun setData(
        events: List<SoundEvent>,
        halos: List<LabelHalo>,
        phoneYawDegrees: Float,
    ) {
        this.events = events
        this.halos = halos
        this.halosByLabel = halos.associateBy { it.label }
        this.phoneYawDegrees = phoneYawDegrees
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

        // Belief halo sits behind the sweep / labels so chips and arrows stay on top.
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
            // Prefer full 2D placement from this label's belief peak (world-frame,
            // rotated into device frame via phoneYawDegrees). Falls back to the legacy
            // Y-only placement from bottomIld when the belief is still flat — gives
            // sensible chip anchor positions in the first ~second before the IMU sweep has
            // accumulated enough evidence.
            val halo = halosByLabel[event.label]
            val haloSharp = halo != null &&
                halo.snapshot.isNotEmpty() &&
                halo.snapshot.max() > (1f / halo.snapshot.size) * HALO_SHARP_THRESHOLD

            val xNorm: Float
            val yNorm: Float
            if (haloSharp) {
                val deviceAngle = halo!!.peakWorldAngle - phoneYawDegrees
                val rad = Math.toRadians((deviceAngle - 90f).toDouble())
                xNorm = EVENT_RADIUS_NORM * kotlin.math.cos(rad).toFloat()
                yNorm = EVENT_RADIUS_NORM * kotlin.math.sin(rad).toFloat()
            } else {
                xNorm = 0f
                yNorm = (event.devicePosition.bottomIld * Y_SENSITIVITY).coerceIn(-1f, 1f)
            }
            // Slight per-event vertical jitter so multiple events don't perfectly overlap.
            val stagger = ((index % 2) * 2 - 1) * (index / 2) * 0.08f
            val x = cx + xNorm * maxRadius * 0.82f
            val y = cy + (yNorm + stagger).coerceIn(-1f, 1f) * maxRadius * 0.82f
            val color = urgencyColor(event.urgency)
            val textColor = urgencyTextColor(event.urgency)

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
     * Draw one belief halo per active label. Each halo is an arc segment per bin
     * (intensity-modulated alpha) plus a peak-marker arrow, both tinted with the
     * corresponding SoundEvent's urgency color. World-frame angles are rotated by
     * `-phoneYawDegrees` so the halos stay anchored to the world as the phone turns.
     */
    private fun drawBeliefHalo(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (halos.isEmpty()) return
        val haloRadius = r * 0.95f
        beliefArcRect.set(cx - haloRadius, cy - haloRadius, cx + haloRadius, cy + haloRadius)
        halos.forEach { drawOneHalo(canvas, cx, cy, haloRadius, it) }
    }

    private fun drawOneHalo(canvas: Canvas, cx: Float, cy: Float, haloRadius: Float, halo: LabelHalo) {
        val snapshot = halo.snapshot
        val n = snapshot.size
        if (n == 0) return
        val binSweepDeg = 360f / n

        var peakBelief = 0f
        for (v in snapshot) if (v > peakBelief) peakBelief = v
        val uniform = 1f / n
        // Share the "is this belief sharp enough to trust?" gate with drawEventDots so a
        // halo only renders once its peak is trustworthy — otherwise the arrow would
        // point at whatever bin wiggled above uniform, which drifts with decay noise.
        if (peakBelief <= uniform * HALO_SHARP_THRESHOLD) return

        val rgb = halo.urgencyColor and 0x00FFFFFF
        for (i in snapshot.indices) {
            val b = snapshot[i]
            if (b <= uniform) continue
            val intensity = ((b - uniform) / (peakBelief - uniform)).coerceIn(0f, 1f)
            // Cut the angular spread: only bins near the peak draw — avoids lighting half
            // the ring when the belief still has a broad shoulder or mirror lobe.
            if (intensity < HALO_ARC_INTENSITY_FLOOR) continue

            val worldAngle = i * binSweepDeg
            val deviceAngle = worldAngle - phoneYawDegrees
            val canvasStart = deviceAngle - 90f - binSweepDeg / 2f

            // Remap [floor..1] → [0..1] so the wedge stays visible after raising the floor.
            val t = ((intensity - HALO_ARC_INTENSITY_FLOOR) /
                (1f - HALO_ARC_INTENSITY_FLOOR)).coerceIn(0f, 1f)
            val alpha = (t * t * HALO_ARC_MAX_ALPHA).toInt().coerceIn(0, 255)
            beliefArcPaint.color = (alpha shl 24) or rgb
            canvas.drawArc(beliefArcRect, canvasStart, binSweepDeg, false, beliefArcPaint)
        }

        // Peak marker arrow at this label's world-frame peak, tinted to match.
        val peakDeviceAngle = halo.peakWorldAngle - phoneYawDegrees
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
        beliefPeakPaint.color = 0xFF000000.toInt() or rgb
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

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    companion object {
        fun urgencyColor(urgency: Urgency): Int = when (urgency) {
            Urgency.CRITICAL -> Color.rgb(214, 58, 47)
            Urgency.HIGH -> Color.rgb(212, 112, 10)
            Urgency.MEDIUM -> Color.rgb(168, 136, 10)
            Urgency.LOW -> Color.rgb(47, 143, 92)
        }

        fun urgencyTextColor(urgency: Urgency): Int = when (urgency) {
            Urgency.CRITICAL -> Color.rgb(184, 46, 36)
            Urgency.HIGH -> Color.rgb(184, 92, 0)
            Urgency.MEDIUM -> Color.rgb(135, 108, 8)
            Urgency.LOW -> Color.rgb(34, 107, 69)
        }

        private const val SWEEP_PERIOD_MS = 3000L
        private const val MAX_VISIBLE_EVENTS = 4

        /** Amplifier on `bot_ild` before clamping for the Y axis. CSV shows confident
         *  detections with bot_ild ~±0.6, so 1.5× lets typical sources reach ~±0.9 on the
         *  radar without saturating. Only used for the bottomIld fallback when a label's
         *  belief is still flat (rotation hasn't accumulated enough evidence yet). */
        private const val Y_SENSITIVITY = 1.5f

        /** Once `maxBelief > uniform * THRESHOLD`, trust the belief peak enough to place
         *  the event chip at its world-frame direction rather than the legacy Y-only
         *  bottomIld fallback. Slightly below the halo-render threshold so any halo that's
         *  visible also drives 2D chip placement. */
        private const val HALO_SHARP_THRESHOLD = 1.5f

        /** Stroke width (density-independent px passed to [RadarView.dp]) for belief arcs — thinner than before so the wedge reads as a pointer ring, not a thick donut. */
        private const val BELIEF_ARC_STROKE_DP = 5f
        /** Normalized intensity floor vs peak ([b-uniform]/[peak-uniform]); bins below this skip drawing — narrows angular glow vs drawing nearly full-ring shoulders. */
        private const val HALO_ARC_INTENSITY_FLOOR = 0.38f
        /** Peak arc alpha after remapping (0–255). */
        private const val HALO_ARC_MAX_ALPHA = 220f

        /** Radius (fraction of maxRadius) at which event chips anchor when belief-driven. Keeps labels near the perimeter halo ring. */
        private const val EVENT_RADIUS_NORM = 0.7f

        /** Minimum yaw change (degrees) before the choreographer-driven refresh issues
         *  a redraw. Prevents continuous invalidates while the phone is stationary. */
        private const val YAW_INVALIDATE_THRESHOLD_DEG = 0.5f
    }
}
