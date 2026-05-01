package com.echoai.diagnostics

import android.content.Context
import com.echoai.audio.AudioWindow
import com.echoai.domain.LocalizationResult
import com.echoai.ml.LabeledScore
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes per-window pipeline state to a CSV file in the app's external files dir
 * (app-private, no permission needed). Pull with:
 *   adb -s <serial> pull /storage/emulated/0/Android/data/com.echoai/files/<file>
 *
 * Columns capture both the 1 s and the 250 ms peak-energy localization scales so the
 * CSV is enough to A/B which window length is producing more consistent lag estimates,
 * and to check for systematic per-channel RMS bias that would explain consistent
 * directional skew (e.g., one mic louder than its pair after AGC).
 */
class DiagnosticsLogger private constructor(
    private val writer: PrintWriter,
    val file: File,
) {
    init {
        writer.println(HEADER)
        writer.flush()
    }

    fun log(
        window: AudioWindow,
        topLabel: LabeledScore?,
        full: LocalizationResult,
        sub: LocalizationResult,
        monoRms: Float,
        noiseFloor: Float,
        snrDb: Float,
        yamnetSkipped: Boolean,
        floorUpdated: Boolean,
        azimuthIldDeg: Float?,
        azimuthLagDeg: Float?,
        phoneYawDeg: Float?,
        beliefPeakDeg: Float?,
        beliefIntensity: Float?,
    ) {
        val cols = listOf(
            window.frameNumber.toString(),
            window.captureTimestampNanos.toString(),
            (topLabel?.label ?: "").csvEscape(),
            "%.4f".format(topLabel?.confidence ?: 0f),
            "%.1f".format(full.bottomLeftRms), "%.1f".format(full.bottomRightRms),
            "%.1f".format(full.backLeftRms), "%.1f".format(full.backRightRms),
            "%.1f".format(full.bottomRms), "%.1f".format(full.backRms),
            "%.4f".format(full.frontBackBias),
            "%.4f".format(full.bottomIld), "%.4f".format(full.backIld),
            full.crossPairLag.samples.toString(), "%.3f".format(full.crossPairLag.confidence),
            full.withinPairBottom.samples.toString(), "%.3f".format(full.withinPairBottom.confidence),
            full.withinPairBack.samples.toString(), "%.3f".format(full.withinPairBack.confidence),
            sub.crossPairLag.samples.toString(), "%.3f".format(sub.crossPairLag.confidence),
            sub.withinPairBottom.samples.toString(), "%.3f".format(sub.withinPairBottom.confidence),
            sub.withinPairBack.samples.toString(), "%.3f".format(sub.withinPairBack.confidence),
            "%.1f".format(monoRms),
            "%.1f".format(noiseFloor),
            "%.2f".format(snrDb),
            if (yamnetSkipped) "1" else "0",
            if (floorUpdated) "1" else "0",
            azimuthIldDeg?.let { "%.2f".format(it) } ?: "",
            azimuthLagDeg?.let { "%.2f".format(it) } ?: "",
            phoneYawDeg?.let { "%.2f".format(it) } ?: "",
            beliefPeakDeg?.let { "%.2f".format(it) } ?: "",
            beliefIntensity?.let { "%.4f".format(it) } ?: "",
        )
        writer.println(cols.joinToString(","))
    }

    fun flush() = writer.flush()

    fun close() {
        writer.flush()
        writer.close()
    }

    private fun String.csvEscape(): String =
        if (contains(',') || contains('"') || contains('\n')) {
            "\"" + replace("\"", "\"\"") + "\""
        } else this

    companion object {
        private const val HEADER =
            "frame,capture_ts_ns,top_label,top_conf," +
                "bot_l_rms,bot_r_rms,bk_l_rms,bk_r_rms," +
                "bot_mono_rms,bk_mono_rms,fb_bias," +
                "bot_ild,bk_ild," +
                "cross_lag_1s,cross_conf_1s,bot_lag_1s,bot_conf_1s,bk_lag_1s,bk_conf_1s," +
                "cross_lag_250,cross_conf_250,bot_lag_250,bot_conf_250,bk_lag_250,bk_conf_250," +
                "mono_rms,noise_floor,snr_db,yamnet_skipped,floor_updated," +
                "azimuth_ild_deg,azimuth_lag_deg,phone_yaw_deg,belief_peak_deg,belief_intensity"

        fun start(context: Context): DiagnosticsLogger {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            dir.mkdirs()
            val name = "diag_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val file = File(dir, name)
            val writer = PrintWriter(FileWriter(file))
            return DiagnosticsLogger(writer, file)
        }
    }
}
