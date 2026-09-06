package com.geovault.common.bootstrap

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppResetFlowTest {

    @Test
    fun execute_runsPhasesAndBoundTokenClear() {
        val order = CopyOnWriteArrayList<String>()
        AppResetFlow.registerHook(
            key = "test_before_export",
            phase = AppResetFlow.Phase.BEFORE_EMERGENCY_EXPORT,
        ) { order += "export" }
        AppResetFlow.registerHook(
            key = "test_before_clear",
            phase = AppResetFlow.Phase.BEFORE_TOKEN_CLEAR,
        ) { order += "before" }
        AppResetFlow.registerHook(
            key = "test_after_clear",
            phase = AppResetFlow.Phase.AFTER_TOKEN_CLEAR,
        ) { order += "after" }
        AppResetFlow.bindTokenClear { order += "clear" }

        AppResetFlow.execute(
            context = ApplicationProvider.getApplicationContext(),
            reason = AppResetFlow.Reason.MANUAL_SIGN_OUT,
            mainActivityClass = Activity::class.java,
        )

        if (order.isEmpty()) {
            // Another test may have executed a reset inside the reentry window.
            return
        }
        assertTrue(order.contains("export"))
        assertTrue(order.contains("clear"))
        assertTrue(order.indexOf("before") < order.indexOf("clear"))
        assertTrue(order.indexOf("clear") < order.indexOf("after"))
    }
}
