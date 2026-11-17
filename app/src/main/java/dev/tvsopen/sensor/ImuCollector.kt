package dev.tvsopen.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dev.tvsopen.log.Log

/**
 * Captures accelerometer and gyroscope samples into the same log sink as
 * the BLE frames, so a ride produces one interleaved record.
 *
 * One line per sample, tagged IMU, formatted as:
 *   accel,<x>,<y>,<z>,<sensor_ns>,<elapsed_ns>
 *   gyro,<x>,<y>,<z>,<sensor_ns>,<elapsed_ns>
 *
 * Two clocks are recorded: the sensor timestamp (monotonic, best for
 * aligning with GPS) and elapsedRealtimeNanos at write time (wall-ish,
 * best for aligning with the BLE frame timestamps which use
 * System.currentTimeMillis).
 */
class ImuCollector(
    private val context: Context,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accel: Sensor? = null
    private var gyro: Sensor? = null

    fun start() {
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "accelerometer registered")
        } ?: Log.w(TAG, "no accelerometer on this device")
        gyro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            Log.i(TAG, "gyroscope registered")
        } ?: Log.w(TAG, "no gyroscope on this device")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "sensors unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val kind = when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> "accel"
            Sensor.TYPE_GYROSCOPE -> "gyro"
            else -> return
        }
        val v = event.values
        val elapsedNs = SystemClock.elapsedRealtimeNanos()
        Log.i(
            TAG,
            "$kind,${v[0]},${v[1]},${v[2]},${event.timestamp},$elapsedNs",
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.w(TAG, "accuracy changed: ${sensor?.name} -> $accuracy")
    }

    private companion object {
        const val TAG = "IMU"
    }
}