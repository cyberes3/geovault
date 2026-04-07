package com.geovault.tracker.params

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackerParamValueFormatterTest {

    private fun formatter(): TrackerParamValueFormatter {
        return TrackerParamValueFormatter(ApplicationProvider.getApplicationContext<Context>())
    }

    @Test
    fun labelForKey_usesTable() {
        val f = formatter()
        assertEquals("Battery", f.labelForKey("batt"))
        assertEquals("custom_key", f.labelForKey("custom_key"))
    }

    @Test
    fun format_bearing() {
        val f = formatter()
        assertEquals("45°", f.formatDisplay("bearing", 45.2))
    }

    @Test
    fun format_batt() {
        val f = formatter()
        assertEquals("90%", f.formatDisplay("batt", 90))
    }

}
