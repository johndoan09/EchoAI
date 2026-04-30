package com.echoai.audio

/**
 * One time-aligned slice of dual-stream stereo audio. All four channels share the same
 * frame range so cross-channel math (RMS ratios, GCC-PHAT, etc.) is well-defined.
 *
 * @property frameNumber monotonically increasing window id, starts at 0 each capture session
 * @property captureTimestampNanos System.nanoTime() snapshot at window emit
 * @property sampleRate sample rate of all four channels (e.g., 16_000)
 * @property bottomLeft left channel of bottomRec (CAMCORDER × bottom)
 * @property bottomRight right channel of bottomRec
 * @property backLeft left channel of backRec (CAMCORDER × back)
 * @property backRight right channel of backRec
 * @property worldOrientation optional quaternion [w, x, y, z] in world frame at capture
 *   instant; null when no IMU provider is wired. Pass-through for downstream fusion.
 */
data class AudioWindow(
    val frameNumber: Long,
    val captureTimestampNanos: Long,
    val sampleRate: Int,
    val bottomLeft: ShortArray,
    val bottomRight: ShortArray,
    val backLeft: ShortArray,
    val backRight: ShortArray,
    val worldOrientation: FloatArray?,
) {
    val frameCount: Int get() = bottomLeft.size
}
