package com.geovault.common.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.ui.navigation.findComponentActivity

/**
 * Opens [oauthUrl] in an in-task Custom Tab when set, then invokes [onConsumed] so the URL
 * does not survive activity resume (avoids duplicate launches and resume flicker).
 */
@Composable
fun GeoVaultOAuthBrowserEffect(
    oauthUrl: String?,
    onConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findComponentActivity() }
    LaunchedEffect(oauthUrl, activity) {
        val url = oauthUrl ?: return@LaunchedEffect
        val host = activity ?: return@LaunchedEffect
        GeovaultAuthManager.launchOAuthInBrowser(host, url)
        onConsumed()
    }
}
