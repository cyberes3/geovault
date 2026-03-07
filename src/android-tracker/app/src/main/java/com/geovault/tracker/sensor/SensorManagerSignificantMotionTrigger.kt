package com.geovault.tracker.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "SigMotionTrigger"

/**
 * [SignificantMotionTrigger] backed by [SensorManager] and [Sensor.TYPE_SIGNIFICANT_MOTION].
 * [onTrigger] is posted to the main looper so it runs on the main thread.
 */
class SensorManagerSignificantMotionTrigger(private val context: Context) : SignificantMotionTrigger {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var listener: TriggerEventListener? = null
    private var pendingCallback: (() -> Unit)? = null

    override fun isAvailable(): Boolean = sensor != null

    override fun request(onTrigger: () -> Unit) {
        if (sensor == null || sensorManager == null) return
        cancel()
        pendingCallback = onTrigger
        listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                Log.d(TAG, "Significant motion detected")
                mainHandler.post {
                    pendingCallback?.invoke()
                    pendingCallback = null
                    listener = null
                }
            }
        }
        sensorManager.requestTriggerSensor(listener, sensor)
        Log.d(TAG, "Requested significant motion trigger")
    }

    override fun cancel() {
        if (sensor != null && listener != null && sensorManager != null) {
            sensorManager.cancelTriggerSensor(listener, sensor)
        }
        pendingCallback = null
        listener = null
    }
}
