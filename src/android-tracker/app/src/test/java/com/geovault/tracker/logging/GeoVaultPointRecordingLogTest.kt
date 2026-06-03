package com.geovault.tracker.logging

import org.junit.Test

class GeoVaultPointRecordingLogTest {

    @Test
    fun i_doesNotThrowWhenRecordingDisabled() {
        GeoVaultPointRecordingLog.i("test", "positioning_raw_fix track=test")
    }
}
