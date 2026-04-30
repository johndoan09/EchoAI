package com.echoai.audio

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Probes whether 3 and 4 concurrent AudioRecord instances can coexist on this device.
 * Independence verification per recipe: every stream must reach RECORDING, no bit-identical
 * pairs across stream channels, and pairwise normalized cross-correlation between mono-mixed
 * streams must stay below 0.95 (same-device pairs may run ~0.5–0.85; that's acceptable
 * as different processing of similar audio).
 */
object MultiStreamProbe {

    private const val SAMPLE_RATE = 16_000
    private const val DURATION_MS = 600
    private const val MAX_LAG_SAMPLES = 16

    private data class StreamSpec(
        val source: Int,
        val sourceLabel: String,
        val device: AudioDeviceInfo,
        val deviceLabel: String,
    ) {
        val label: String get() = "$sourceLabel×$deviceLabel"
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(context: Context): String {
        val devices = builtInMics(context)
        val bottom = devices.firstOrNull { it.second == "bottom" }?.first
            ?: return "(bottom mic not enumerated)"
        val back = devices.firstOrNull { it.second == "back" }?.first
            ?: return "(back mic not enumerated)"

        val mic = MediaRecorder.AudioSource.MIC
        val cam = MediaRecorder.AudioSource.CAMCORDER

        val sb = StringBuilder()
        sb.appendLine("Reading $DURATION_MS ms per recipe at $SAMPLE_RATE Hz. Make continuous noise.\n")

        sb.appendLine("=== 3-STREAM-A: MIC×bottom + MIC×back + CAMCORDER×bottom ===")
        sb.appendLine(verifyRecipe(listOf(
            StreamSpec(mic, "MIC", bottom, "bot"),
            StreamSpec(mic, "MIC", back, "back"),
            StreamSpec(cam, "CAMCO", bottom, "bot"),
        )))
        sb.appendLine()

        sb.appendLine("=== 3-STREAM-B: MIC×bottom + MIC×back + CAMCORDER×back ===")
        sb.appendLine(verifyRecipe(listOf(
            StreamSpec(mic, "MIC", bottom, "bot"),
            StreamSpec(mic, "MIC", back, "back"),
            StreamSpec(cam, "CAMCO", back, "back"),
        )))
        sb.appendLine()

        sb.appendLine("=== 4-STREAM (headline): MIC×{bot,back} + CAMCORDER×{bot,back} ===")
        sb.appendLine(verifyRecipe(listOf(
            StreamSpec(mic, "MIC", bottom, "bot"),
            StreamSpec(mic, "MIC", back, "back"),
            StreamSpec(cam, "CAMCO", bottom, "bot"),
            StreamSpec(cam, "CAMCO", back, "back"),
        )))

        return sb.toString().trimEnd()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun verifyRecipe(specs: List<StreamSpec>): String {
        val recorders = mutableListOf<Pair<StreamSpec, AudioRecord?>>()
        try {
            for (s in specs) recorders += s to buildStereo(s)
            val openCount = recorders.count { it.second?.state == AudioRecord.STATE_INITIALIZED }
            if (openCount < specs.size) {
                val failed = recorders.filter { it.second?.state != AudioRecord.STATE_INITIALIZED }
                    .joinToString(", ") { it.first.label }
                return "  OPEN_FAIL  $openCount/${specs.size} initialized.  failed: $failed"
            }

            for ((_, r) in recorders) r!!.startRecording()
            val recordingCount = recorders.count {
                it.second?.recordingState == AudioRecord.RECORDSTATE_RECORDING
            }
            if (recordingCount < specs.size) {
                val notRec = recorders.filter {
                    it.second?.recordingState != AudioRecord.RECORDSTATE_RECORDING
                }.joinToString(", ") { it.first.label }
                return "  START_FAIL  $recordingCount/${specs.size} recording.  failed: $notRec"
            }

            // Read all streams concurrently on separate threads so timing aligns.
            val frames = SAMPLE_RATE * DURATION_MS / 1000
            val buffers = recorders.map { ShortArray(frames * 2) }
            val readsOut = IntArray(recorders.size)
            val threads = recorders.indices.map { i ->
                Thread { readsOut[i] = readFully(recorders[i].second!!, buffers[i]) }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            for ((_, r) in recorders) runCatching { r!!.stop() }

            if (readsOut.any { it == 0 }) {
                val zeros = recorders.indices.filter { readsOut[it] == 0 }
                    .joinToString(", ") { recorders[it].first.label }
                return "  READ_0 on: $zeros"
            }

            // Per-stream stats
            val sb = StringBuilder()
            sb.appendLine("  All ${specs.size} streams open + recording. Read counts: ${readsOut.toList()}")
            sb.appendLine()

            val monos = mutableListOf<ShortArray>()
            val labels = mutableListOf<String>()
            val activeMics = mutableListOf<String>()
            sb.appendLine("  Per-stream:")
            for (i in recorders.indices) {
                val (spec, r) = recorders[i]
                val frames_i = readsOut[i] / 2
                val l = ShortArray(frames_i); val rCh = ShortArray(frames_i)
                for (k in 0 until frames_i) {
                    l[k] = buffers[i][2 * k]
                    rCh[k] = buffers[i][2 * k + 1]
                }
                val mono = downmix(l, rCh)
                val rmsM = rms(mono)
                val withinPair = peakXcorr(l, rCh, MAX_LAG_SAMPLES)
                val mics = activeMicAddrs(r!!)
                sb.appendLine(
                    "    %-14s  RMS=%-5d  L↔R=%.3f  mics=[%s]"
                        .format(spec.label, rmsM, withinPair, mics)
                )
                monos += mono
                labels += spec.label
                activeMics += mics
            }
            sb.appendLine()

            // Cross-stream pairwise mono correlations
            sb.appendLine("  Cross-stream mono correlations:")
            val n = monos.size
            var anyBitDup = false
            var maxCross = 0.0
            var maxLabel = ""
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    val corr = peakXcorr(monos[i], monos[j], MAX_LAG_SAMPLES)
                    val bitDup = bitIdentical(monos[i], monos[j])
                    if (bitDup) anyBitDup = true
                    if (corr > maxCross) { maxCross = corr; maxLabel = "${labels[i]} ↔ ${labels[j]}" }
                    sb.appendLine(
                        "    %-32s corr=%.3f%s"
                            .format("${labels[i]} ↔ ${labels[j]}", corr, if (bitDup) "  BIT-IDENTICAL" else "")
                    )
                }
            }
            sb.appendLine()

            val verdict = when {
                anyBitDup -> "DUPLICATED — at least one cross-stream pair is bit-identical."
                maxCross >= 0.95 -> "DUPLICATED — max cross-stream corr ${"%.3f".format(maxCross)} ($maxLabel) too high."
                maxCross >= 0.85 -> "PARTIAL — max corr ${"%.3f".format(maxCross)} ($maxLabel); same-device pairs likely sharing audio with light processing differences."
                else -> "INDEPENDENT — max cross-stream corr ${"%.3f".format(maxCross)}; recipe is viable."
            }
            sb.append("  VERDICT: $verdict")
            return sb.toString()
        } finally {
            for ((_, r) in recorders) r?.release()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildStereo(spec: StreamSpec): AudioRecord? {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(spec.source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(SAMPLE_RATE * 2 * 2)
                .build()
        } catch (e: Exception) { return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        if (!r.setPreferredDevice(spec.device)) { r.release(); return null }
        return r
    }

    private fun readFully(r: AudioRecord, buf: ShortArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = r.read(buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    private fun downmix(l: ShortArray, r: ShortArray): ShortArray {
        val n = minOf(l.size, r.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((l[i].toInt() + r[i].toInt()) / 2).toShort()
        }
        return out
    }

    private fun rms(s: ShortArray): Int {
        if (s.isEmpty()) return 0
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v.toDouble()
        return sqrt(sum / s.size).toInt()
    }

    private fun peakXcorr(a: ShortArray, b: ShortArray, maxLag: Int): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var sumA2 = 0.0
        var sumB2 = 0.0
        for (i in 0 until n) {
            sumA2 += a[i].toDouble() * a[i].toDouble()
            sumB2 += b[i].toDouble() * b[i].toDouble()
        }
        val denom = sqrt(sumA2 * sumB2)
        if (denom < 1e-9) return 0.0
        var best = 0.0
        for (lag in -maxLag..maxLag) {
            val start = if (lag >= 0) lag else 0
            val end = if (lag >= 0) n else n + lag
            var sum = 0.0
            for (i in start until end) sum += a[i].toDouble() * b[i - lag].toDouble()
            val c = abs(sum / denom)
            if (c > best) best = c
        }
        return best
    }

    private fun bitIdentical(a: ShortArray, b: ShortArray): Boolean {
        val n = minOf(a.size, b.size)
        if (n == 0) return false
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    private fun activeMicAddrs(r: AudioRecord): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "?"
        val mics = r.activeMicrophones
        if (mics.isEmpty()) return "?"
        return mics.joinToString(",") { it.address.ifBlank { "id${it.id}" } }
    }

    private fun builtInMics(context: Context): List<Pair<AudioDeviceInfo, String>> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            .map { d ->
                val addr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) d.address else ""
                d to addr.ifBlank { "id${d.id}" }
            }
    }
}
