package com.echoai.audio

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MicrophoneInfo
import android.os.Build
import androidx.annotation.RequiresPermission

/**
 * Read-only probe: dumps everything Android exposes about input audio devices and active
 * microphones, plus a one-shot test of whether the HAL accepts a 4-channel index-mask capture.
 * No state, no service, safe to call from a background dispatcher.
 */
object MicCapabilityProbe {

    private const val SAMPLE_RATE = 16_000

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun run(context: Context): String = buildString {
        appendLine("=== Input AudioDeviceInfo ===")
        appendLine(enumerateInputs(context))
        appendLine()
        appendLine("=== 4-channel index-mask probe (0xF) ===")
        appendLine(probeFourChannel())
        appendLine()
        appendLine("=== Active microphones during stereo capture ===")
        appendLine(probeActiveMics())
        appendLine()
        appendLine("=== AudioSource routing probe ===")
        appendLine(probeAudioSources())
    }

    private fun enumerateInputs(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputs.isEmpty()) return "(no input devices)"
        return buildString {
            for (d in inputs) {
                appendLine("- id=${d.id}  type=${typeName(d.type)}  product='${d.productName}'")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appendLine("    address='${d.address}'")
                }
                appendLine("    channelCounts=${d.channelCounts.toList()}")
                appendLine("    channelMasks=${d.channelMasks.map { "0x%X".format(it) }}")
                appendLine("    channelIndexMasks=${d.channelIndexMasks.map { "0x%X".format(it) }}")
                appendLine("    sampleRates=${d.sampleRates.toList()}")
                appendLine("    encodings=${d.encodings.toList()}")
            }
        }.trimEnd()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun probeFourChannel(): String = runCatching {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelIndexMask(0xF) // request 4 indexed channels
            .build()
        val bufBytes = SAMPLE_RATE * 4 * 2 // 1 s of 4ch s16
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufBytes)
            .build()
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                "constructor returned but state=${recorder.state} — HAL refused 4-channel index mask."
            } else {
                recorder.startRecording()
                val running = recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                if (!running) {
                    runCatching { recorder.stop() }
                    "constructor OK but recordingState=${recorder.recordingState} — start failed silently."
                } else {
                    val frames = SAMPLE_RATE / 4
                    val out = ShortArray(frames * 4)
                    val n = recorder.read(out, 0, out.size)
                    recorder.stop()
                    val perChannelRms = (0 until 4).map { ch ->
                        var s = 0.0
                        for (i in 0 until n / 4) {
                            val v = out[i * 4 + ch].toDouble()
                            s += v * v
                        }
                        Math.sqrt(s / (n / 4).coerceAtLeast(1))
                    }
                    "SUCCESS — read $n shorts (${n / 4} frames @ 4ch). Per-channel RMS: " +
                        perChannelRms.joinToString { "%.0f".format(it) } +
                        ".\nTrue 4-channel capture is available on this device."
                }
            }
        } finally {
            recorder.release()
        }
    }.getOrElse { e -> "FAILED: ${e::class.simpleName}: ${e.message}" }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun probeActiveMics(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "(API 28+ required for getActiveMicrophones)"
        }
        return runCatching {
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2 * 2))
                .build()
            try {
                if (recorder.state != AudioRecord.STATE_INITIALIZED) return@runCatching "AudioRecord not initialized"
                recorder.startRecording()
                // Read briefly so routing actually engages before querying.
                recorder.read(ShortArray(SAMPLE_RATE / 4), 0, SAMPLE_RATE / 4)
                val mics = recorder.activeMicrophones
                recorder.stop()
                if (mics.isEmpty()) {
                    "(getActiveMicrophones() returned empty — Samsung HAL often does not populate this)"
                } else formatMics(mics)
            } finally {
                recorder.release()
            }
        }.getOrElse { e -> "FAILED: ${e::class.simpleName}: ${e.message}" }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun probeAudioSources(): String {
        val sources = listOf(
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
        )
        return buildString {
            for ((src, name) in sources) {
                val line = runCatching {
                    val minBuf = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    val recorder = AudioRecord(
                        src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2 * 2)
                    )
                    try {
                        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                            "$name: state != INITIALIZED"
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            recorder.startRecording()
                            recorder.read(ShortArray(SAMPLE_RATE / 4), 0, SAMPLE_RATE / 4)
                            val mics = recorder.activeMicrophones
                            recorder.stop()
                            val ids = mics.map { "id=${it.id}@${micLocation(it.location)}" }
                            "$name: ${if (ids.isEmpty()) "(no mic info)" else ids.joinToString()}"
                        } else {
                            "$name: OK (mic info needs API 28)"
                        }
                    } finally {
                        recorder.release()
                    }
                }.getOrElse { e -> "$name: FAILED ${e.message}" }
                appendLine(line)
            }
        }.trimEnd()
    }

    private fun formatMics(mics: List<MicrophoneInfo>): String = buildString {
        for (m in mics) {
            appendLine("- description='${m.description}'  type=${typeName(m.type)}  id=${m.id}")
            appendLine("    address='${m.address}'  location=${micLocation(m.location)}  group=${m.group}/${m.indexInTheGroup}")
            appendLine("    position=${formatCoord(m.position)}  orientation=${formatCoord(m.orientation)}")
            appendLine("    directionality=${micDirectionality(m.directionality)}")
            val mapping = m.channelMapping.joinToString { "ch${it.first}->${micChannelMapping(it.second)}" }
            appendLine("    channelMapping=[${mapping}]")
        }
    }.trimEnd()

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        else -> "type=$t"
    }

    private fun micLocation(loc: Int) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) when (loc) {
        MicrophoneInfo.LOCATION_MAINBODY -> "MAINBODY"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "MAINBODY_MOVABLE"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "PERIPHERAL"
        MicrophoneInfo.LOCATION_UNKNOWN -> "UNKNOWN"
        else -> "loc=$loc"
    } else "loc=$loc"

    private fun micDirectionality(d: Int) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) when (d) {
        MicrophoneInfo.DIRECTIONALITY_OMNI -> "OMNI"
        MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "BI"
        MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "CARDIOID"
        MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "HYPER_CARDIOID"
        MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "SUPER_CARDIOID"
        MicrophoneInfo.DIRECTIONALITY_UNKNOWN -> "UNKNOWN"
        else -> "dir=$d"
    } else "dir=$d"

    private fun micChannelMapping(m: Int) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) when (m) {
        MicrophoneInfo.CHANNEL_MAPPING_DIRECT -> "DIRECT"
        MicrophoneInfo.CHANNEL_MAPPING_PROCESSED -> "PROCESSED"
        else -> "$m"
    } else "$m"

    private fun formatCoord(c: MicrophoneInfo.Coordinate3F): String {
        // Coordinate3F.POSITION_UNKNOWN is a sentinel float for unset metadata.
        fun f(v: Float) = if (v >= 1e20f || v <= -1e20f) "?" else "%.3f".format(v)
        return "(${f(c.x)}, ${f(c.y)}, ${f(c.z)})"
    }
}
