package com.geovault.common.htmlrender

/**
 * Controls how long document loading may run. Null [loadTimeoutMs] falls back to
 * [HtmlRendererConfig.defaultLoadTimeoutMs].
 */
data class LoadPolicy(
    val waitForWindowOnLoad: Boolean = true,
    val loadTimeoutMs: Long? = null,
)
