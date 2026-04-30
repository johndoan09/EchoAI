package com.echoai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.echoai.sensor.NullWorldOrientation
import com.echoai.sensor.WorldOrientationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the locked dual-CAMCORDER capture topology. Emits aligned 1 s windows with 50%
 * overlap on a hot SharedFlow.
 *
 * Lifecycle:
 *  - [start] launches the capture coroutine on Dispatchers.IO.
 *  - [stop] cancels the coroutine; recorders are released in the finally block.
 *  - State transitions visible via [state]: STOPPED → STARTING → RUNNING → STOPPED|ERROR.
 *
 * Threading: the SharedFlow is configured with DROP_OLDEST so a slow downstream
 * collector cannot back-pressure the capture loop. If the pipeline can't keep up, we
 * drop windows rather than risk dropped audio frames at the HAL.
 *
 * IMU hook: [orientationProvider] is sampled once per emitted window. NullWorldOrientation
 * by default; swap in a `Sensor.TYPE_ROTATION_VECTOR`-backed implementation later.
 */
class AudioCaptureManager(
    private val context: Context,
    private val orientationProvider: WorldOrientationProvider = NullWorldOrientation,
) {

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _windows = MutableSharedFlow<AudioWindow>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val windows: SharedFlow<AudioWindow> = _windows.asSharedFlow()

    private var captureJob: Job? = null
    @Volatile private var lastError: String? = null

    fun start(scope: CoroutineScope) {
        if (_state.value != State.STOPPED && _state.value != State.ERROR) return
        if (!hasMicPermission()) {
            lastError = "RECORD_AUDIO permission not granted"
            _state.value = State.ERROR
            return
        }
        _state.value = State.STARTING
        lastError = null
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                runCapture()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message}"
                _state.value = State.ERROR
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        _state.value = State.STOPPED
    }

    fun lastErrorMessage(): String? = lastError

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun runCapture() {
        val (bottomDev, backDev) = findMicDevices()
            ?: error("S25 Ultra mic devices not found (need bottom + back BUILTIN_MIC)")

        val rec1 = buildStereoRecorder(bottomDev) ?: error("Failed to build bottom recorder")
        val rec2 = try {
            buildStereoRecorder(backDev) ?: run {
                rec1.release(); error("Failed to build back recorder")
            }
        } catch (t: Throwable) {
            rec1.release(); throw t
        }

        try {
            rec1.startRecording()
            rec2.startRecording()
            if (rec1.recordingState != AudioRecord.RECORDSTATE_RECORDING ||
                rec2.recordingState != AudioRecord.RECORDSTATE_RECORDING
            ) {
                error("HAL refused concurrent capture (rec1=${rec1.recordingState}, rec2=${rec2.recordingState})")
            }
            _state.value = State.RUNNING

            // Discard ~200 ms of HAL-warmup audio per recorder before emitting windows.
            val warmup = ShortArray(WARMUP_FRAMES * CHANNELS_PER_REC)
            readFully(rec1, warmup)
            readFully(rec2, warmup)

            // Per-channel ring buffers (4 channels × 2 s capacity).
            val ringSize = WINDOW_FRAMES * 2
            val bottomLRing = ShortArray(ringSize)
            val bottomRRing = ShortArray(ringSize)
            val backLRing = ShortArray(ringSize)
            val backRRing = ShortArray(ringSize)

            var bottomFramesWritten = 0L
            var backFramesWritten = 0L
            var nextWindowStartFrame = 0L
            var windowCounter = 0L

            val readBuf1 = ShortArray(HOP_FRAMES * CHANNELS_PER_REC)
            val readBuf2 = ShortArray(HOP_FRAMES * CHANNELS_PER_REC)

            while (currentCoroutineContext().isActive) {
                val read1 = readFully(rec1, readBuf1)
                val read2 = readFully(rec2, readBuf2)
                if (read1 == 0 || read2 == 0) {
                    error("AudioRecord.read returned 0 — capture interrupted")
                }
                val frames1 = read1 / CHANNELS_PER_REC
                val frames2 = read2 / CHANNELS_PER_REC

                // Append rec1 → bottom rings.
                for (i in 0 until frames1) {
                    val pos = ((bottomFramesWritten + i) % ringSize).toInt()
                    bottomLRing[pos] = readBuf1[2 * i]
                    bottomRRing[pos] = readBuf1[2 * i + 1]
                }
                bottomFramesWritten += frames1

                // Append rec2 → back rings.
                for (i in 0 until frames2) {
                    val pos = ((backFramesWritten + i) % ringSize).toInt()
                    backLRing[pos] = readBuf2[2 * i]
                    backRRing[pos] = readBuf2[2 * i + 1]
                }
                backFramesWritten += frames2

                // Emit aligned windows whenever both rings have caught up past the next boundary.
                val available = minOf(bottomFramesWritten, backFramesWritten)
                while (available >= nextWindowStartFrame + WINDOW_FRAMES) {
                    val window = extractWindow(
                        startFrame = nextWindowStartFrame,
                        bottomL = bottomLRing,
                        bottomR = bottomRRing,
                        backL = backLRing,
                        backR = backRRing,
                        ringSize = ringSize,
                        windowId = windowCounter,
                    )
                    _windows.emit(window)
                    windowCounter++
                    nextWindowStartFrame += HOP_FRAMES
                }
            }
        } finally {
            runCatching { rec1.stop() }
            runCatching { rec2.stop() }
            rec1.release()
            rec2.release()
            if (_state.value != State.ERROR) _state.value = State.STOPPED
        }
    }

    private fun extractWindow(
        startFrame: Long,
        bottomL: ShortArray, bottomR: ShortArray,
        backL: ShortArray, backR: ShortArray,
        ringSize: Int,
        windowId: Long,
    ): AudioWindow {
        val bL = ShortArray(WINDOW_FRAMES)
        val bR = ShortArray(WINDOW_FRAMES)
        val cL = ShortArray(WINDOW_FRAMES)
        val cR = ShortArray(WINDOW_FRAMES)
        for (i in 0 until WINDOW_FRAMES) {
            val pos = ((startFrame + i) % ringSize).toInt()
            bL[i] = bottomL[pos]
            bR[i] = bottomR[pos]
            cL[i] = backL[pos]
            cR[i] = backR[pos]
        }
        return AudioWindow(
            frameNumber = windowId,
            captureTimestampNanos = System.nanoTime(),
            sampleRate = SAMPLE_RATE,
            bottomLeft = bL,
            bottomRight = bR,
            backLeft = cL,
            backRight = cR,
            worldOrientation = orientationProvider.snapshot(),
        )
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildStereoRecorder(device: AudioDeviceInfo): AudioRecord? {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(AUDIO_SOURCE)
                .setAudioFormat(format)
                .setBufferSizeInBytes(RECORDER_BUFFER_BYTES)
                .build()
        } catch (e: Exception) {
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return null
        }
        if (!r.setPreferredDevice(device)) {
            r.release(); return null
        }
        return r
    }

    private fun findMicDevices(): Pair<AudioDeviceInfo, AudioDeviceInfo>? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val builtIns = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val bottom = builtIns.firstOrNull { it.address == "bottom" } ?: return null
        val back = builtIns.firstOrNull { it.address == "back" } ?: return null
        return bottom to back
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

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_FRAMES = 16_000     // 1 s
        const val HOP_FRAMES = 8_000         // 50% overlap → emit every 500 ms
        const val WARMUP_FRAMES = 3_200      // ~200 ms HAL warmup discard

        private const val CHANNELS_PER_REC = 2
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.CAMCORDER
        private const val RECORDER_BUFFER_BYTES = SAMPLE_RATE * CHANNELS_PER_REC * 2 * 2 // ~2 s
    }
}
