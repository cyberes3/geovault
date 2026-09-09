package com.geovault.tracker.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.tracker.streaming.FakeLiveStreamHostPort
import com.geovault.tracker.streaming.FakeLiveStreamPersistPort
import com.geovault.tracker.streaming.LiveStreamSubscriptionRepository
import com.geovault.tracker.streaming.StreamIntent
import com.geovault.tracker.streaming.StreamingOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MapSessionLeaseOrchestrationTest {

    @Test
    fun resumeWriteThenRosterRefresh_sameMapLeaseDispatchesOnce() = runTest {
        val persist = FakeLiveStreamPersistPort()
        val host = FakeLiveStreamHostPort(persist)
        val app: Context = ApplicationProvider.getApplicationContext()
        val repository = LiveStreamSubscriptionRepository(
            appContext = app,
            persist = persist,
            host = host,
            dispatchDebounceMs = 0L,
            scope = this,
        )
        val intent = StreamIntent(
            trackerIds = setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            displayName = "Trail",
        )

        repository.setLease(StreamingOwner.MAP, intent)
        advanceUntilIdle()
        repository.setLease(StreamingOwner.MAP, intent)
        advanceUntilIdle()

        assertEquals(1, host.startedIds.size)
        assertEquals(setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), host.startedIds.single())
    }
}
