package com.geovault.common.htmlrender

/**
 * One render job. [html] is loaded with [mimeType]; relative URLs resolve against [baseUrl] when set
 * (recommended for assets). Use [LoadPolicy] to tune timeouts.
 *
 * **Threading**: Instances are immutable and safe to share; [HtmlRenderer.render] serializes execution.
 */
data class HtmlRenderRequest(
    val html: String,
    val baseUrl: String? = null,
    val mimeType: String = "text/html",
    val output: RenderOutput,
    val loadPolicy: LoadPolicy = LoadPolicy(),
) {
    init {
        require(mimeType.isNotBlank()) { "mimeType must not be blank" }
    }
}
