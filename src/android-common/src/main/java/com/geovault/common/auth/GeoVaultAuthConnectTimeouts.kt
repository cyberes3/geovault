package com.geovault.common.auth

import java.util.concurrent.TimeUnit

object GeoVaultAuthConnectTimeouts {
    const val SERVER_URL_RESOLVE_TIMEOUT_SECONDS = 5L

    val serverUrlResolveTimeoutMs: Long =
        TimeUnit.SECONDS.toMillis(SERVER_URL_RESOLVE_TIMEOUT_SECONDS)
}
