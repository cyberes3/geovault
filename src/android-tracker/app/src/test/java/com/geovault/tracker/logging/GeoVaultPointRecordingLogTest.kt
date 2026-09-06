package com.geovault.tracker.logging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeoVaultPointRecordingLogTest {

    @Test
    fun i_doesNotThrowWhenRecordingDisabled() {
        GeoVaultPointRecordingLog.i("test", "positioning_raw_fix track=test")
    }

    @Test
    fun exportToDownloads_returnsFalseWhenRecordingDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(GeoVaultPointRecordingLog.exportToDownloads(context))
    }
}
