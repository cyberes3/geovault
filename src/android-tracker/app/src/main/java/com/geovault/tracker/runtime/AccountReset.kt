package com.geovault.tracker.runtime

import android.content.Context
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.db.AppDatabase
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.settings.TrackerSettingsRepository

/**
 * Ordered account wipe: stop + watchdog cancel, registered slices, Room queue,
 * empty runtime document, persist shouldBeRunning=false, null services/engine.
 */
class AccountReset(
    private val context: Context,
    private val settingsRepository: TrackerSettingsRepository,
    private val extraSlices: MutableList<(Context) -> Unit> = mutableListOf(),
    private val afterPersistSlices: MutableList<(Context) -> Unit> = mutableListOf(),
) {
    fun registerSlice(slice: (Context) -> Unit) {
        extraSlices += slice
    }

    fun registerAfterPersistSlice(slice: (Context) -> Unit) {
        afterPersistSlices += slice
    }

    fun execute(reason: String) {
        GeoVaultCaptureLog.i(TAG, "execute reason=$reason")
        val engine = TrackerRuntimeEngine.get(context)
        engine.handle(TrackerRuntimeCommands.Stop(reason = "account_reset:$reason"))
        engine.cancelWatchdog()
        extraSlices.forEach { slice ->
            runCatching { slice(context) }.onFailure { error ->
                GeoVaultCaptureLog.e(TAG, "slice failed reason=$reason", error)
            }
        }
        AppDatabase.getDatabase(context).locationDao().deleteAll()
        TrackerRuntimeStore.replaceEmpty()
        afterPersistSlices.forEach { slice ->
            runCatching { slice(context) }.onFailure { error ->
                GeoVaultCaptureLog.e(TAG, "after-persist slice failed reason=$reason", error)
            }
        }
        TrackerAppServices.resetInstance()
        TrackerRuntimeEngine.resetInstance()
        TrackerRuntimeStore.attach(context.applicationContext)
    }

    companion object {
        private const val TAG = "AccountReset"
    }
}
