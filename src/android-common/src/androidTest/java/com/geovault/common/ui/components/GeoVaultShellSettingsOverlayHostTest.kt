package com.geovault.common.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.geovault.common.ui.theme.GeoVaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GeoVaultShellSettingsOverlayHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overlayActiveLocalIsFalseDuringHiddenPrewarm() {
        var observedActive: Boolean? = null

        composeRule.setContent {
            GeoVaultTheme {
                GeoVaultShellSettingsOverlayHost(
                    visible = false,
                    onDismissRequest = {},
                    handleBack = false,
                    prewarmDelayMillis = 0L,
                ) {
                    observedActive = LocalGeoVaultShellSettingsOverlayActive.current
                    Text("Settings")
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(false, observedActive)
        }
    }

    @Test
    fun overlayActiveLocalIsTrueWhenVisible() {
        var observedActive: Boolean? = null

        composeRule.setContent {
            GeoVaultTheme {
                GeoVaultShellSettingsOverlayHost(
                    visible = true,
                    onDismissRequest = {},
                    handleBack = false,
                    prewarmDelayMillis = 0L,
                ) {
                    observedActive = LocalGeoVaultShellSettingsOverlayActive.current
                    Text("Settings")
                }
            }
        }

        composeRule.runOnIdle {
            assertEquals(true, observedActive)
        }
    }

    @Test
    fun settingsMenuActionIsSuppressedWhileSettingsOverlayIsActive() {
        var active by mutableStateOf(false)
        val menuDescription = "More options"

        composeRule.setContent {
            GeoVaultTheme {
                GeoVaultShellSettingsOverlayActiveProvider(active = active) {
                    Row {
                        GeoVaultTopBarSettingsMenuAction(
                            onOpenSettings = {},
                            visibility = GeoVaultTopBarMenuVisibility.Always,
                            iconContentDescription = menuDescription,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription(menuDescription).assertExists()
        composeRule.runOnIdle {
            active = true
        }
        composeRule.onNodeWithContentDescription(menuDescription).assertDoesNotExist()
    }
}
