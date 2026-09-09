package com.geovault.tracker.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerRuntimeStoreInitTest {

    @Before
    fun setUp() {
        TrackerRuntimeEngine.resetForTests()
    }

    @Test
    fun attach_loadsPersistedOrchestration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TrackerRuntimeStore.attach(context)
        TrackerRuntimeStore.updateOrchestration {
            it.copy(shouldBeRunning = true, lifecycleState = RuntimeLifecycleState.RECOVERING)
        }
        assertEquals(true, TrackerRuntimeStore.value.shouldBeRunning)
        TrackerRuntimeStore.replaceEmpty()
        assertFalse(TrackerRuntimeStore.value.shouldBeRunning)
    }
}
