package com.geovault.tracker.status

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.geovault.tracker.R
import com.geovault.tracker.services.TrackingUiStatus

object TrackingStatusPresentation {
    @JvmStatic
    @StringRes
    fun statusTextRes(status: TrackingUiStatus): Int {
        return when (status) {
            TrackingUiStatus.NOT_TRACKING -> R.string.not_tracking
            TrackingUiStatus.WAITING_FOR_GPS -> R.string.waiting_for_gps_reenabled
            TrackingUiStatus.LOCKING -> R.string.locking
            TrackingUiStatus.TRACKING_ACTIVE -> R.string.tracking_active
        }
    }

    @JvmStatic
    @ColorRes
    fun statusColorRes(status: TrackingUiStatus): Int {
        return when (status) {
            TrackingUiStatus.NOT_TRACKING -> R.color.primary_blue
            TrackingUiStatus.WAITING_FOR_GPS -> R.color.error_red
            TrackingUiStatus.LOCKING -> R.color.warning_yellow
            TrackingUiStatus.TRACKING_ACTIVE -> R.color.warning_yellow
        }
    }
}
