package com.geovault.common.htmlrender

/**
 * Controls how long loading and raster measurement may run. Null timeouts fall back to
 * [HtmlRendererConfig.defaultLoadTimeoutMs] and [HtmlRendererConfig.defaultMeasureTimeoutMs].
 */
data class LoadPolicy(
    val waitForWindowOnLoad: Boolean = true,
    val loadTimeoutMs: Long? = null,
    val measureTimeoutMs: Long? = null,
)
