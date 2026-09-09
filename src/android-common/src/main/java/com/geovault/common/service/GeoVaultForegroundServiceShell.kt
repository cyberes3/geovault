package com.geovault.common.service

import android.app.Notification
import android.app.Service
import com.geovault.common.logging.GeoVaultCaptureLog

/**
 * Promotes a [Service] to the foreground. Every `startForegroundService` path must call
 * [promote] — including Stop and failed reshow — then stop if that was the only reason to
 * be up. Failure is a [PromotionResult], not a thrown exception.
 *
 * Two concurrent foreground services remain legal: tracking uses location type;
 * streaming uses dataSync type.
 */
class GeoVaultForegroundServiceShell(
    private val service: Service,
    private val notificationId: Int,
    private val foregroundServiceType: Int,
    private val tag: String,
) {
    sealed class PromotionResult {
        data object Promoted : PromotionResult()
        data class Failed(val error: Exception) : PromotionResult()
    }

    fun promote(notification: Notification, fallback: Notification? = null): PromotionResult {
        return try {
            service.startForeground(notificationId, notification, foregroundServiceType)
            PromotionResult.Promoted
        } catch (error: Exception) {
            GeoVaultCaptureLog.e(tag, "startForeground failed notificationId=$notificationId", error)
            if (fallback == null) {
                return PromotionResult.Failed(error)
            }
            try {
                service.startForeground(notificationId, fallback, foregroundServiceType)
                PromotionResult.Promoted
            } catch (fallbackError: Exception) {
                GeoVaultCaptureLog.e(
                    tag,
                    "fallback startForeground failed notificationId=$notificationId",
                    fallbackError,
                )
                PromotionResult.Failed(fallbackError)
            }
        }
    }

    fun stopForeground(removeNotification: Boolean) {
        service.stopForeground(
            if (removeNotification) {
                Service.STOP_FOREGROUND_REMOVE
            } else {
                Service.STOP_FOREGROUND_DETACH
            },
        )
    }
}
