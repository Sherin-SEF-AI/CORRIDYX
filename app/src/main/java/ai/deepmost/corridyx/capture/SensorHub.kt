package ai.deepmost.corridyx.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber
import kotlin.math.sqrt

/**
 * 100 Hz IMU + light sensor. Maintains bounded ring buffers of gravity-removed vertical acceleration
 * and angular-rate magnitude; the analysis pipeline snapshots a window per tick for the roughness
 * head. Light sensor feeds the illumination cross-check. Runs on the SensorManager's own thread.
 */
class SensorHub(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    val lightSensorPresent: Boolean get() = light != null

    private val cap = 256 // ~2.5s at 100Hz
    private val vert = FloatArray(cap)
    private val gyroMag = FloatArray(cap)
    private var head = 0
    private var filled = 0
    private var lastNs = 0L

    // gravity low-pass (per-axis) for vertical projection
    private val gravity = FloatArray(3)
    private var gravityInit = false

    @Volatile var lightLux: Float? = null
        private set

    fun start() {
        accel?.let { sm.registerListener(this, it, 10_000) }   // ~100 Hz (us)
        gyro?.let { sm.registerListener(this, it, 10_000) }
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Timber.i("SensorHub started accel=%b gyro=%b light=%b", accel != null, gyro != null, light != null)
    }

    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            Sensor.TYPE_GYROSCOPE -> onGyro(e)
            Sensor.TYPE_LIGHT -> lightLux = e.values[0]
        }
    }

    private fun onAccel(e: SensorEvent) {
        val a = 0.8f
        if (!gravityInit) { gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]; gravityInit = true }
        gravity[0] = a * gravity[0] + (1 - a) * e.values[0]
        gravity[1] = a * gravity[1] + (1 - a) * e.values[1]
        gravity[2] = a * gravity[2] + (1 - a) * e.values[2]
        val gmag = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]) + 1e-6f
        // linear acceleration projected onto gravity direction = vertical acceleration
        val lx = e.values[0] - gravity[0]; val ly = e.values[1] - gravity[1]; val lz = e.values[2] - gravity[2]
        val vAcc = (lx * gravity[0] + ly * gravity[1] + lz * gravity[2]) / gmag
        synchronized(this) {
            vert[head] = vAcc
            head = (head + 1) % cap
            if (filled < cap) filled++
            lastNs = e.timestamp
        }
    }

    private fun onGyro(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        synchronized(this) {
            // align gyro into the same ring index position as the latest accel sample
            val idx = (head - 1 + cap) % cap
            gyroMag[idx] = mag
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Snapshot the most recent [windowMs] of vertical accel + gyro for one analysis tick. */
    fun snapshot(windowMs: Int = 1000): ImuWindow? = synchronized(this) {
        if (filled < 16) return null
        val rate = 100f
        val n = ((windowMs / 1000f) * rate).toInt().coerceAtMost(filled)
        val v = FloatArray(n); val g = FloatArray(n)
        for (i in 0 until n) {
            val idx = (head - n + i + cap) % cap
            v[i] = vert[idx]; g[i] = gyroMag[idx]
        }
        ImuWindow(v, g, rate, lastNs)
    }
}
