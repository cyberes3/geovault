package com.geovault.common.auth

/**
 * Contract for auth connect button state ([isConnecting] / "Connecting...") across GeoVault apps.
 *
 * - Set [isConnecting] true in [AuthConnectCoordinator.launch] `onConnecting`.
 * - On [CommonInitialAuthController.OAuthPreparationResult.Ready]: set `oauthUrl` only; **keep**
 *   [isConnecting] true so the button does not flash back to "Connect Account" before or during
 *   the browser step.
 * - On [GeoVaultOAuthBrowserEffect]'s `onConsumed`: clear `oauthUrl` only (browser was handed off).
 * - On [CommonInitialAuthController.OAuthPreparationResult.InvalidServerUrl] /
 *   [CommonInitialAuthController.OAuthPreparationResult.UnreachableServer]: clear [isConnecting].
 * - On host `onResume` while still signed out: clear [isConnecting] (user returned from browser).
 */
object AuthConnectUiLifecycle
