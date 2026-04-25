package com.geovault.common.maps.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface

/**
 * Reads the device rotation vector and exposes a degrees-from-north bearing.
 *
 * Design goals:
 * - One lifecycle owner per caller (start/stop pair). No shared singleton so tests and hosts
 *   that live under different lifecycles can't trip each other.
 * - Heading is smoothed across frames (defaults tuned for GeoVault maps: fast sampling
 *   with a minimum emit interval so map / puck updates are not tied to raw sensor jitter).
 * - Screen rotation is compensated (landscape/upside-down renders usable bearings).
 * - [start] is a no-op when the device has no rotation-vector sensor, so callers can always
 *   call it; [isAvailable] exposes that state for UI affordances.
 *
 * Sensor math runs on a dedicated [HandlerThread] so [SensorManager.SENSOR_DELAY_FASTEST] does
 * not flood the UI thread (which previously caused freezes when combined with map updates).
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
    private var lastEmitElapsedMs: Long = 0L

    private var onBearing: ((Float) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var pendingEmitBearing: Float = 0f
    private val flushEmitRunnable = Runnable {
        onBearing?.invoke(pendingEmitBearing)
    }

    /**
     * Begin receiving bearing updates.
     *
     * [onBearingDegrees] is invoked on the **main** thread with a degrees value normalized to
     * `[0, 360)`. The exponential smoothing factor [smoothingAlpha] lives
     * in `[0, 1]`: higher values follow the sensor more tightly (1.0 = no smoothing).
     *
     * [sensorDelay] is passed to [SensorManager.registerListener] (e.g. [SensorManager.SENSOR_DELAY_FASTEST]).
     *
     * When [minEmitIntervalMs] is greater than zero, smoothed bearings are still computed on
     * every sensor sample but the callback is invoked at most once per interval — the pattern
     * used to keep camera / puck motion steady (~60 Hz cap).
     *
     * Idempotent: calling [start] while already running replaces the callback without
     * re-registering the sensor listener.
     */
    fun start(
        smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,
        sensorDelay: Int = DEFAULT_SENSOR_DELAY,
        minEmitIntervalMs: Long = DEFAULT_MIN_EMIT_INTERVAL_MS,
        onBearingDegrees: (Float) -> Unit,
    ) {
        onBearing = onBearingDegrees
        if (listener != null) return
        val sensor = rotationVectorSensor ?: return
        val manager = sensorManager ?: return
        ensureSensorThread()
        val handler = sensorHandler ?: return
        val observer = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                val bearing = computeBearing(event.values) ?: return
                val smoothed = smoothBearing(bearing, smoothingAlpha)
                if (minEmitIntervalMs > 0L) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastEmitElapsedMs != 0L && now - lastEmitElapsedMs < minEmitIntervalMs) {
                        return
                    }
                    lastEmitElapsedMs = now
                }
                pendingEmitBearing = smoothed
                mainHandler.removeCallbacks(flushEmitRunnable)
                mainHandler.post(flushEmitRunnable)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        listener = observer
        lastEmitElapsedMs = 0L
        manager.registerListener(observer, sensor, sensorDelay, 0, handler)
    }

    private fun ensureSensorThread() {
        if (sensorThread != null) return
        val thread = HandlerThread("gv-heading-sensor").apply { start() }
        sensorThread = thread
        sensorHandler = Handler(thread.looper)
    }

    /**
     * Stop delivering updates. Safe to call multiple times.
     */
    fun stop() {
        mainHandler.removeCallbacks(flushEmitRunnable)
        val manager = sensorManager
        val observer = listener
        listener = null
        onBearing = null
        smoothedBearing = null
        lastEmitElapsedMs = 0L
        if (manager != null && observer != null) {
            manager.unregisterListener(observer)
        }
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
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
        // No dead-zone here on purpose: a min-delta filter makes slow physical rotation look
        // stair-stepped (the smoothed delta-per-frame stays under the threshold for several
        // frames, then jumps once accumulated change crosses it). No min-delta filter here so
        // slow physical rotation stays continuous.
        smoothedBearing = next
        return next
    }

    companion object {
        /** Default rotation-vector smoothing alpha (rotation-vector → map). */
        private const val DEFAULT_SMOOTHING_ALPHA = 0.35f

        /** Minimum interval between main-thread bearing callbacks (ms). */
        private const val DEFAULT_MIN_EMIT_INTERVAL_MS = 16L

        private val DEFAULT_SENSOR_DELAY: Int = SensorManager.SENSOR_DELAY_FASTEST
    }
}
