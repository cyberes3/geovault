package com.geovault.tracker.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.geovault.common.ui.theme.GeoVaultColorTokens
import com.geovault.tracker.parseHexToColorInt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerChevronStylePolicyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun tintForTrackerColorHex_usesPrimaryBlueFallbackForNullAndInvalidHex() {
        val nullTint = TrackerChevronStylePolicy.tintForTrackerColorHex(null, context)
        val invalidTint = TrackerChevronStylePolicy.tintForTrackerColorHex("not-a-color", context)

        assertEquals(GeoVaultColorTokens.PrimaryBlue, nullTint)
        assertEquals(GeoVaultColorTokens.PrimaryBlue, invalidTint)
    }

    @Test
    fun tintForTrackerColorHex_matchesParseHexPolicyForValidHex() {
        val hex = "#AA33CC"
        val expected = androidx.compose.ui.graphics.Color(parseHexToColorInt(hex, context))
        val actual = TrackerChevronStylePolicy.tintForTrackerColorHex(hex, context)

        assertEquals(expected, actual)
    }
}
