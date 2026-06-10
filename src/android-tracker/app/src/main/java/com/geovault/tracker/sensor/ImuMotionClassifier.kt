package com.geovault.tracker.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.geovault.common.logging.GeoVaultCaptureLog
import kotlin.math.sqrt

/**
 * Classifies device motion from AOSP inertial sensors, providing a GPS-independent
 * signal that breaks the circular dependency between GPS filtering and motion mode.
 *
 * Uses [Sensor.TYPE_LINEAR_ACCELERATION] to compute acceleration variance over a 10 s
 * rolling window, and [Sensor.TYPE_STEP_DETECTOR] to measure step rate over a 30 s
 * rolling window. Both sensor callbacks are routed to the main thread via [Handler],
 * matching the threading model of [SensorManagerSignificantMotionTrigger].
 *
 * A classification is only emitted once it has been **stable for [STABILITY_REQUIRED_MS]**,
 * then re-emitted every [HEARTBEAT_MS] as a heartbeat. This stability gate prevents
 * transient sensor noise from influencing the mode engine.
 *
 * Degrades gracefully:
 * - No step detector / no [android.Manifest.permission.ACTIVITY_RECOGNITION]: PEDESTRIAN
 *   classification unavailable; STATIONARY and VEHICULAR still work from variance alone.
 * - No linear acceleration: STATIONARY and VEHICULAR unavailable; only PEDESTRIAN from steps.
 * - Neither sensor: always emits UNKNOWN (equivalent to no IMU, system unchanged).
 */
class ImuMotionClassifier(
    private val context: Context,
    private val onClassification: (ImuMotionContext) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val linearAccelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val stepDetectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Mutable state — accessed only from the main thread (sensor callbacks routed via mainHandler)
    private val accelSamples = ArrayDeque<AccelSample>()
    private val stepTimestampsMs = ArrayDeque<Long>()
    private var pendingClassification: ImuClassification = ImuClassification.UNKNOWN
    private var pendingClassificationSinceMs: Long = 0L
    private var lastEmittedClassification: ImuClassification? = null
    private var lastEmittedAtMs: Long = 0L

    private var accelListener: SensorEventListener? = null
    private var stepListener: SensorEventListener? = null

    fun start() {
        // Anchor the stability window to now so the first sensor events don't
        // inherit a stale pendingClassificationSinceMs of 0 and emit prematurely.
        pendingClassificationSinceMs = clock()

        val linearAccelAvailable = linearAccelSensor != null
        val stepDetectorAvailable = stepDetectorSensor != null
        val activityRecognitionGranted = hasActivityRecognitionPermission()

        GeoVaultCaptureLog.i(
            TAG,
            "imu_classifier_started linearAccelAvailable=$linearAccelAvailable " +
                "stepDetectorAvailable=$stepDetectorAvailable " +
                "activityRecognitionGranted=$activityRecognitionGranted",
        )

        if (sensorManager == null) return

        linearAccelSensor?.let { sensor ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = onAccelEvent(event)
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensorManager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                ACCEL_MAX_REPORT_LATENCY_US,
                mainHandler,
            )
            accelListener = listener
        }

        stepDetectorSensor?.let { sensor ->
            if (activityRecognitionGranted) {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) = onStepEvent(event)
                    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
                }
                sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    STEP_MAX_REPORT_LATENCY_US,
                    mainHandler,
                )
                stepListener = listener
            } else {
                GeoVaultCaptureLog.i(TAG, "imu_sensor_fallback reason=activity_recognition_not_granted")
            }
        }
    }

    fun stop() {
        sensorManager?.let { sm ->
            accelListener?.let { sm.unregisterListener(it) }
            stepListener?.let { sm.unregisterListener(it) }
        }
        accelListener = null
        stepListener = null
        accelSamples.clear()
        stepTimestampsMs.clear()
        pendingClassification = ImuClassification.UNKNOWN
        pendingClassificationSinceMs = 0L
        lastEmittedClassification = null
        lastEmittedAtMs = 0L
        GeoVaultCaptureLog.i(TAG, "imu_classifier_stopped")
    }

    // region Sensor event handlers

    private fun onAccelEvent(event: SensorEvent) {
        val nowMs = clock()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        accelSamples.addLast(AccelSample(nowMs, magnitude))
        evaluate(nowMs)
    }

    private fun onStepEvent(event: SensorEvent) {
        val nowMs = clock()
        stepTimestampsMs.addLast(nowMs)
        evaluate(nowMs)
    }

    // endregion

    // region Classification pipeline

    private fun evaluate(nowMs: Long) {
        pruneOldSamples(nowMs)
        val variance = computeVariance()
        val stepRate = computeStepRate(nowMs)
        val (classification, confidence) = classify(variance, stepRate)

        if (classification == pendingClassification) {
            val stableForMs = nowMs - pendingClassificationSinceMs
            if (stableForMs >= STABILITY_REQUIRED_MS) {
                val isFirstEmission = classification != lastEmittedClassification
                val isHeartbeat = nowMs - lastEmittedAtMs >= HEARTBEAT_MS
                if (isFirstEmission || isHeartbeat) {
                    lastEmittedClassification = classification
                    lastEmittedAtMs = nowMs
                    onClassification(
                        ImuMotionContext(
                            classification = classification,
                            confidence = confidence,
                            accelerationVarianceMps4 = variance,
                            stepRatePerMinute = stepRate,
                        ),
                    )
                }
            }
        } else {
            GeoVaultCaptureLog.d(
                TAG,
                "imu_pending_change from=$pendingClassification to=$classification " +
                    "variance=$variance stepRate=$stepRate samples=${accelSamples.size}",
            )
            pendingClassification = classification
            pendingClassificationSinceMs = nowMs
        }
    }

    private fun pruneOldSamples(nowMs: Long) {
        val accelCutoffMs = nowMs - VARIANCE_WINDOW_MS
        while (accelSamples.isNotEmpty() && accelSamples.first().timestampMs < accelCutoffMs) {
            accelSamples.removeFirst()
        }
        val stepCutoffMs = nowMs - STEP_RATE_WINDOW_MS
        while (stepTimestampsMs.isNotEmpty() && stepTimestampsMs.first() < stepCutoffMs) {
            stepTimestampsMs.removeFirst()
        }
    }

    private fun computeVariance(): Float {
        val n = accelSamples.size
        if (n < MIN_ACCEL_SAMPLES) return 0f
        val mean = accelSamples.sumOf { it.magnitude.toDouble() }.toFloat() / n
        return accelSamples.sumOf { val d = (it.magnitude - mean).toDouble(); d * d }.toFloat() / n
    }

    private fun computeStepRate(nowMs: Long): Float {
        val count = stepTimestampsMs.size
        if (count == 0) return 0f
        // Divide by the full window duration, not the elapsed time since the first observed step.
        // Using elapsed time produces a spuriously high rate when only one or two steps have been
        // seen (e.g. 1 step in 1 ms → 60 000 steps/min). The full-window denominator is
        // conservative: the rate is a lower bound until the window is saturated, which is the
        // correct behaviour — PEDESTRIAN should not fire until walking is sustained.
        return count.toFloat() * 60_000f / STEP_RATE_WINDOW_MS
    }

    private fun classify(variance: Float, stepRate: Float): Pair<ImuClassification, Float> =
        classify(variance, stepRate, hasAccelData = accelSamples.size >= MIN_ACCEL_SAMPLES)

    // endregion

    private fun hasActivityRecognitionPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private data class AccelSample(val timestampMs: Long, val magnitude: Float)

    companion object {
        private const val TAG = "ImuMotionClassifier"

        /**
         * Returns true if the device has at least one sensor that [ImuMotionClassifier] can use.
         * When false the classifier will always emit [ImuClassification.UNKNOWN], so callers can
         * use this to warn the user that IMU-assisted mode switching is unavailable.
         */
        fun isAvailable(context: Context): Boolean {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null ||
                sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null
        }


        /**
         * Pure classification dispatch. Exposed as `internal` so unit tests can exercise all
         * branches without constructing [SensorEvent]s or requiring an Android context.
         *
         * @param variance     linear acceleration magnitude variance over the rolling window, m²/s⁴
         * @param stepRate     foot step rate over the rolling window, steps/min
         * @param hasAccelData whether the acceleration window has enough samples for a
         *                     valid variance estimate; false forces UNKNOWN on variance-only paths
         */
        internal fun classify(
            variance: Float,
            stepRate: Float,
            hasAccelData: Boolean,
        ): Pair<ImuClassification, Float> {
            // Step rate takes priority — confirmed foot motion cannot be VEHICULAR.
            if (stepRate >= PEDESTRIAN_STEP_RATE_MIN) {
                val confidence = (stepRate / PEDESTRIAN_STEP_RATE_FULL_CONFIDENCE).coerceAtMost(1f)
                return ImuClassification.PEDESTRIAN to confidence
            }

            // Variance-based discrimination requires a populated window.
            if (!hasAccelData) {
                return ImuClassification.UNKNOWN to 0f
            }

            if (variance < STATIONARY_VARIANCE_CEILING) {
                val confidence = 1f - (variance / STATIONARY_VARIANCE_CEILING)
                return ImuClassification.STATIONARY to confidence
            }

            if (stepRate < VEHICULAR_MAX_STEP_RATE && variance > VEHICULAR_VARIANCE_FLOOR) {
                val confidence = ((variance - VEHICULAR_VARIANCE_FLOOR) / VEHICULAR_CONFIDENCE_SCALE)
                    .coerceAtMost(1f)
                return ImuClassification.VEHICULAR to confidence
            }

            return ImuClassification.UNKNOWN to 0f
        }

        // Accel delivers at SENSOR_DELAY_NORMAL (~5 Hz) with no batching so events arrive
        // regardless of whether the device has FIFO hardware for this sensor type.
        // Step detector is event-driven so a generous batch window is fine.
        private const val ACCEL_MAX_REPORT_LATENCY_US = 0
        private const val STEP_MAX_REPORT_LATENCY_US  = 5_000_000   // 5 s

        // Variance computation window
        private const val VARIANCE_WINDOW_MS  = 10_000L             // 10 s
        private const val MIN_ACCEL_SAMPLES   = 5

        // Step rate computation window
        private const val STEP_RATE_WINDOW_MS = 30_000L             // 30 s

        // Classification thresholds
        private const val PEDESTRIAN_STEP_RATE_MIN          = 40f   // steps/min
        private const val PEDESTRIAN_STEP_RATE_FULL_CONFIDENCE = 80f
        private const val VEHICULAR_MAX_STEP_RATE            = 10f  // steps/min
        private const val STATIONARY_VARIANCE_CEILING        = 0.04f // m²/s⁴
        private const val VEHICULAR_VARIANCE_FLOOR           = 0.03f // m²/s⁴
        private const val VEHICULAR_CONFIDENCE_SCALE         = 0.10f

        // Stability gate
        private const val STABILITY_REQUIRED_MS = 15_000L           // 15 s stable before first emit
        private const val HEARTBEAT_MS          = 15_000L           // re-emit every 15 s while stable
    }
}
