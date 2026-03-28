package com.geovault.tracker.fragments.map

import org.junit.Assert.assertTrue
import org.junit.Test

class MapLockStateCodecTest {
    @Test
    fun fromPersisted_trackerFollowWithoutTarget_restoresPendingFollowIntent() {
        val restored = MapLockStateCodec.fromPersisted(
            PersistedMapLockState(
                mode = MapLockMode.TRACKER_FOLLOW,
                targetLat = null,
                targetLon = null,
                needsInitialZoom = true
            )
        )
        assertTrue(restored is MapLockState.TrackerFollowPending)
    }
}
