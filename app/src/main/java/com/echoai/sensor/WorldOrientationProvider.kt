package com.echoai.sensor

/**
 * Source of device world-frame orientation. Implementations capture sensor state from
 * `Sensor.TYPE_ROTATION_VECTOR`; consumers can read the latest snapshot or the derived
 * yaw (compass-style azimuth around the vertical world axis).
 */
interface WorldOrientationProvider {
    /** Quaternion [w, x, y, z] in world frame, or null if unavailable. */
    fun snapshot(): FloatArray?

    /**
     * Yaw in degrees in [0, 360). Direction the phone's "top" (Y axis) is pointing in
     * the world: 0 = north, 90 = east, 180 = south, 270 = west. Returns null if no
     * sensor sample has arrived yet.
     */
    fun yawDegrees(): Float?
}

/** Null implementation used until the real IMU provider lands. */
object NullWorldOrientation : WorldOrientationProvider {
    override fun snapshot(): FloatArray? = null
    override fun yawDegrees(): Float? = null
}
