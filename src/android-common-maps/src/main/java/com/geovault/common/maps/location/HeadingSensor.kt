package com.geovault.common.maps.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import kotlin.math.abs

/**
 * Reads the device rotation vector and exposes a degrees-from-north bearing.
 *
 * Design goals:
 * - One lifecycle owner per caller (start/stop pair). No shared singleton so tests and hosts
 *   that live under different lifecycles can't trip each other.
 * - Heading is smoothed across frames to avoid the jitter you get when the low-level sensor
 *   flips by a few tenths of a degree on every sample.
 * - Screen rotation is compensated (landscape/upside-down renders usable bearings).
 * - [start] is a no-op when the device has no rotation-vector sensor, so callers can always
 *   call it; [isAvailable] exposes that state for UI affordances.
 */
class HeadingSensor(context: Context) {
    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean = rotationVectorSensor != null

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothedBearing: Float? = null
    private var listener: SensorEventListener? = null

    private var onBearing: ((Float) -> Unit)? = null

    /**
     * Begin receiving bearing updates.
     *
     * [onBearingDegrees] is invoked on the main-looper-posted sensor callback with a degrees
     * value normalized to `[0, 360)`. The exponential smoothing factor [smoothingAlpha] lives
     * in `[0, 1]`: higher values follow the sensor more tightly (1.0 = no smoothing).
     *
     * Idempotent: calling [start] while already running replaces the callback without
     * re-registering the sensor listener.
     */
    fun start(
        smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,
        onBearingDegrees: (Float) -> Unit,
    ) {
        onBearing = onBearingDegrees
        if (listener != null) return
        val sensor = rotationVectorSensor ?: return
        val manager = sensorManager ?: return
        val observer = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                val bearing = computeBearing(event.values) ?: return
                val smoothed = smoothBearing(bearing, smoothingAlpha)
                onBearing?.invoke(smoothed)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = observer
        manager.registerListener(observer, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    /**
     * Stop delivering updates. Safe to call multiple times.
     */
    fun stop() {
        val manager = sensorManager
        val observer = listener
        if (manager != null && observer != null) {
            manager.unregisterListener(observer)
        }
        listener = null
        onBearing = null
        smoothedBearing = null
    }

    private fun computeBearing(rotationValues: FloatArray): Float? {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationValues)
        val (axisX, axisY) = screenRotationAxes()
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
        SensorManager.getOrientation(remappedMatrix, orientation)
        val azimuthRad = orientation[0]
        val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
        return ((azimuthDeg + 360f) % 360f)
    }

    private fun screenRotationAxes(): Pair<Int, Int> {
        // `WindowManager.getDefaultDisplay()` was deprecated in API 30. `Context.getDisplay()`
        // is the documented replacement but requires a UI context — we only have the app
        // context here — so we route through `DisplayManager` to fetch the default display.
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }

    private fun smoothBearing(newBearing: Float, alpha: Float): Float {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        val last = smoothedBearing
        if (last == null || clampedAlpha >= 1f) {
            smoothedBearing = newBearing
            return newBearing
        }
        // Shortest-arc interpolation so the puck doesn't do a full 360 spin on the 359→1 wrap.
        var delta = newBearing - last
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val next = ((last + delta * clampedAlpha) + 360f) % 360f
        // Avoid thrash-writes when the sensor is essentially noise-bound.
        if (abs(next - last) < MIN_BEARING_DELTA_DEG) {
            return last
        }
        smoothedBearing = next
        return next
    }

    companion object {
        private const val DEFAULT_SMOOTHING_ALPHA = 0.15f
        private const val MIN_BEARING_DELTA_DEG = 0.25f
    }
}
