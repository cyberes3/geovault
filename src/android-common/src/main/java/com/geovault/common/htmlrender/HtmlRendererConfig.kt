package com.geovault.common.htmlrender

/**
 * Global limits and WebView behavior. Tighten [blockNetworkLoads] for untrusted HTML to reduce
 * SSRF/exfiltration risk (remote images and XHR will not load).
 *
 * **Security**: Arbitrary HTML with scripting enabled can perform XSS-style actions inside the
 * WebView. Prefer app-generated templates with escaped data for untrusted input.
 */
data class HtmlRendererConfig(
    val maxHtmlBytes: Int = 5 * 1024 * 1024,
    val defaultLoadTimeoutMs: Long = 30_000L,
    val defaultOutputTimeoutMs: Long = 60_000L,
    val enableJavaScript: Boolean = true,
    val blockNetworkLoads: Boolean = false,
) {
    init {
        require(maxHtmlBytes > 0)
        require(defaultLoadTimeoutMs > 0)
        require(defaultOutputTimeoutMs > 0)
    }

    companion object {
        val DEFAULT = HtmlRendererConfig()
    }
}
