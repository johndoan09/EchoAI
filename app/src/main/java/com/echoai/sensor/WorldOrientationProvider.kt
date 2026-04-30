package com.echoai.sensor

/**
 * Hook for IMU-aided tracking. Implementations capture the device's current world-frame
 * orientation (from `Sensor.TYPE_ROTATION_VECTOR`) and return it as a quaternion.
 *
 * Intentionally empty until IMU integration is wired — keeps `AudioCaptureManager`
 * decoupled from the sensor pipeline and keeps `AudioWindow` shape stable as the
 * implementation lands later.
 */
interface WorldOrientationProvider {
    /** Returns quaternion [w, x, y, z] in world frame, or null if unavailable. */
    fun snapshot(): FloatArray?
}

/** Null implementation used until the real IMU provider lands. */
object NullWorldOrientation : WorldOrientationProvider {
    override fun snapshot(): FloatArray? = null
}
