package com.geovault.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.geovault.common.ui.theme.GeoVaultTheme
import com.geovault.common.ui.theme.geoVaultDialogSurfaceColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private const val TapTargetTag = "tap-target"
private const val DisconnectDialogTitle = "Disconnect Account?"

class GeoVaultPrewarmedOverlayHostTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hiddenPrewarmedContentDoesNotHandleClicksOrOpenDialogs() {
        var backgroundClicks = 0

        composeRule.setContent {
            GeoVaultTheme {
            var showHiddenDialog by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .requiredSize(240.dp)
                    .testTag(TapTargetTag)
                    .clickable { backgroundClicks += 1 },
            ) {
                GeoVaultPrewarmedOverlayHost(
                    visible = false,
                    prewarmDelayMillis = 0L,
                ) {
                    Button(
                        onClick = { showHiddenDialog = true },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("Hidden Disconnect")
                    }
                    if (showHiddenDialog) {
                        DisconnectDialog()
                    }
                }
            }
            }
        }

        composeRule.onNodeWithTag(TapTargetTag).performTouchInput { click(center) }

        composeRule.runOnIdle {
            assertEquals(1, backgroundClicks)
        }
        composeRule.onNodeWithText(DisconnectDialogTitle).assertDoesNotExist()
    }

    @Test
    fun visiblePrewarmedContentHandlesClicks() {
        composeRule.setContent {
            GeoVaultTheme {
            var showVisibleDialog by remember { mutableStateOf(false) }

            Box(
                modifier = Modifier
                    .requiredSize(240.dp)
                    .testTag(TapTargetTag),
            ) {
                GeoVaultPrewarmedOverlayHost(
                    visible = true,
                    prewarmDelayMillis = 0L,
                ) {
                    Button(
                        onClick = { showVisibleDialog = true },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text("Visible Disconnect")
                    }
                    if (showVisibleDialog) {
                        DisconnectDialog()
                    }
                }
            }
            }
        }

        composeRule.onNodeWithTag(TapTargetTag).performTouchInput { click(center) }

        composeRule.onNodeWithText(DisconnectDialogTitle).assertIsDisplayed()
    }
}

@Composable
private fun DisconnectDialog() {
    AlertDialog(
        onDismissRequest = {},
        backgroundColor = geoVaultDialogSurfaceColor(),
        title = { Text(DisconnectDialogTitle) },
        confirmButton = {
            Button(onClick = {}) {
                Text("Disconnect")
            }
        },
    )
}
