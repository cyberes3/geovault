package com.geovault.common.maps.location

import android.hardware.SensorManager
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener

/**
 * A [CompassEngine] whose heading values come from outside MapLibre — specifically from our
 * shared [HeadingSensor] stream.
 *
 * MapLibre's default [org.maplibre.android.location.LocationComponentCompassEngine] reads
 * `Sensor.TYPE_ROTATION_VECTOR` itself with a hard-coded 100 ms throttle and snaps the puck to
 * each new value (we set `compassAnimationEnabled(false)`). On a 60 fps render loop that
 * looks "snappy" — the puck visibly stair-steps. Worse, it competes with our own
 * [HeadingSensor] for the same physical sensor: two parallel sensor streams, two smoothing
 * pipelines, and a puck whose bearing diverges from the camera bearing.
 *
 * By installing this engine via `LocationComponent.setCompassEngine(...)`, the puck's
 * [org.maplibre.android.location.modes.RenderMode.COMPASS] bearing is driven by whoever calls
 * [pushHeading] — we wire that to the same [HeadingSensor] callback that drives the camera, so
 * the puck and the map stay in lock-step from a single sensor source. At our ~60 Hz emit
 * cadence, MapLibre's internal animator interpolates each push over ~16 ms (the actual
 * elapsed time between calls), producing continuously-smooth puck rotation that exactly
 * matches the camera bearing.
 *
 * Listener registration is idempotent — MapLibre's own
 * [org.maplibre.android.location.LocationComponent.updateCompassListenerState] toggles the
 * subscription whenever the layer or camera starts / stops consuming compass, so this class
 * just maintains the invariant that listeners receive every push that arrives while they are
 * registered.
 */
class GeoVaultHeadingCompassEngine : CompassEngine {
    private val listeners: MutableList<CompassListener> = mutableListOf()

    @Volatile
    private var lastHeading: Float = 0f

    override fun addCompassListener(compassListener: CompassListener) {
        if (!listeners.contains(compassListener)) {
            listeners += compassListener
        }
    }

    override fun removeCompassListener(compassListener: CompassListener) {
        listeners -= compassListener
    }

    override fun getLastHeading(): Float = lastHeading

    /**
     * MapLibre uses this to surface device-compass calibration warnings; we always report
     * "high accuracy" because [HeadingSensor] is fed by `TYPE_ROTATION_VECTOR`, which the
     * platform already fuses with magnetic + gyro and only emits when calibrated.
     */
    override fun getLastAccuracySensorStatus(): Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    /**
     * Push a new heading (degrees clockwise from north, `[0, 360)`) into MapLibre's location
     * pipeline. Notifies every registered [CompassListener] in registration order — MapLibre
     * registers exactly one internal listener per active consumer (the layer in
     * [org.maplibre.android.location.modes.RenderMode.COMPASS], and/or the camera in
     * `TRACKING_COMPASS` / `NORTH_COMPASS`), so this is normally one or two callbacks per push.
     */
    fun pushHeading(headingDegrees: Float) {
        lastHeading = headingDegrees
        // Snapshot to tolerate listener mutations during dispatch (MapLibre's internal listener
        // registration is idempotent but we don't want to ConcurrentModificationException if
        // a future caller adds/removes from inside the callback).
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onCompassChanged(headingDegrees)
        }
    }
}
