package com.geovault.tracker.ui

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

    @Test
    fun tintForTrackerColorHex_usesMainBlueForNull_andTrackerDefaultForInvalidHex() {
        val nullTint = TrackerChevronStylePolicy.tintForTrackerColorHex(null)
        val invalidTint = TrackerChevronStylePolicy.tintForTrackerColorHex("not-a-color")

        assertEquals(GeoVaultColorTokens.MainBlue, nullTint)
        assertEquals(GeoVaultColorTokens.Blue400, invalidTint)
    }

    @Test
    fun tintForTrackerColorHex_matchesParseHexPolicyForValidHex() {
        val hex = "#AA33CC"
        val expected = androidx.compose.ui.graphics.Color(parseHexToColorInt(hex))
        val actual = TrackerChevronStylePolicy.tintForTrackerColorHex(hex)

        assertEquals(expected, actual)
    }
}
