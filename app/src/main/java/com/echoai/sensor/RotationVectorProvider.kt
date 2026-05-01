package com.echoai.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reads `Sensor.TYPE_ROTATION_VECTOR` (Android's fused gyro+accel+mag orientation) and
 * exposes the latest quaternion + derived yaw via [WorldOrientationProvider]. Used by
 * the rotational-aperture localizer to map per-window long-axis bias measurements into
 * a world-frame belief distribution.
 *
 * Lifecycle:
 *  - [start] registers the sensor listener at SENSOR_DELAY_GAME (~20 ms updates) — fast
 *    enough that each audio window has a fresh orientation snapshot.
 *  - [stop] unregisters. Always call from the same lifecycle scope as audio capture.
 */
class RotationVectorProvider(context: Context) : WorldOrientationProvider {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile private var latestQuat: FloatArray? = null
    @Volatile private var latestYaw: Float? = null

    private val listener = object : SensorEventListener {
        private val tmpQuat = FloatArray(4)

        override fun onSensorChanged(event: SensorEvent) {
            // Android writes [w, x, y, z] into the output array.
            SensorManager.getQuaternionFromVector(tmpQuat, event.values)
            val q = tmpQuat.copyOf()
            latestQuat = q
            latestYaw = yawDegreesFromQuaternion(q)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start(): Boolean {
        val sensor = rotationSensor ?: return false
        return sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }

    val isAvailable: Boolean get() = rotationSensor != null

    override fun snapshot(): FloatArray? = latestQuat?.copyOf()
    override fun yawDegrees(): Float? = latestYaw
}
