package com.geovault.common.ui.components

import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.geovault.common.ui.theme.GeoVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GeoVaultSubViewScaffoldHostActiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subViewDismissesWhenRetainedHostBecomesInactive() {
        var hostIsActive by mutableStateOf(true)
        var dismissCount = 0

        composeRule.setContent {
            GeoVaultTheme {
                GeoVaultSubViewHostActiveProvider(isActive = hostIsActive) {
                    GeoVaultSubViewScaffold(
                        title = "Child",
                        onClose = {},
                        onLeaveComposition = { dismissCount += 1 },
                    ) {
                        Text("Body")
                    }
                }
            }
        }

        composeRule.runOnIdle {
            hostIsActive = false
        }

        composeRule.runOnIdle {
            assertEquals(1, dismissCount)
        }
    }

    @Test
    fun subViewCanOptOutOfHostInactiveDismissal() {
        var hostIsActive by mutableStateOf(true)
        var dismissCount = 0

        composeRule.setContent {
            GeoVaultTheme {
                GeoVaultSubViewHostActiveProvider(isActive = hostIsActive) {
                    GeoVaultSubViewScaffold(
                        title = "Child",
                        onClose = {},
                        onLeaveComposition = { dismissCount += 1 },
                        dismissOnHostInactive = false,
                    ) {
                        Text("Body")
                    }
                }
            }
        }

        composeRule.runOnIdle {
            hostIsActive = false
        }

        composeRule.runOnIdle {
            assertEquals(0, dismissCount)
        }
    }
}
