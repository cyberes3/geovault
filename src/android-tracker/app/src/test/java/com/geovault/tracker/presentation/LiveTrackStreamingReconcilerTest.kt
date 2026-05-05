package com.geovault.tracker.presentation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LiveTrackStreamingReconcilerTest {

    @Test
    fun reconcileAndInvalidate_smokeServicePipeline() = runBlocking {
        val app: Context = ApplicationProvider.getApplicationContext()
        val reconciler = LiveTrackStreamingReconciler(app)
        val state = TrackerMapUiState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            streamTargetIds = emptySet(),
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "t1", isRunning = false),
        )
        reconciler.reconcile(
            state = state,
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )
        reconciler.invalidateDedupe()
        reconciler.reconcile(
            state = state,
            effectiveDisplayedId = "t1",
            effectiveDisplayedName = "One",
            streamRuntime = LiveStreamRuntimeSnapshot(),
        )
        reconciler.stopForegroundStreaming()
    }
}
