package com.geovault.common.htmlrender.internal

import com.geovault.common.htmlrender.HtmlRenderRequest
import com.geovault.common.htmlrender.HtmlRendererConfig
import com.geovault.common.htmlrender.RenderError
import java.net.MalformedURLException
import java.net.URL

/**
 * Generic invariants only:
 *
 *  - HTML byte size is within [HtmlRendererConfig.maxHtmlBytes].
 *  - When provided, [HtmlRenderRequest.baseUrl] parses as a [URL].
 *  - [HtmlRenderRequest.mimeType] is non-blank (also enforced by the data class `init`).
 *
 * App-specific layout contracts (page size, margins, CSS rules) belong in the caller's HTML
 * template, not here.
 */
internal object RequestValidator {

    fun validate(request: HtmlRenderRequest, config: HtmlRendererConfig): Result<Unit> {
        val bytes = request.html.toByteArray(Charsets.UTF_8)
        if (bytes.size > config.maxHtmlBytes) {
            return Result.failure(
                RenderPipelineException(
                    RenderError.InvalidRequest(
                        message = "HTML exceeds maxHtmlBytes (${bytes.size} > ${config.maxHtmlBytes})",
                    ),
                ),
            )
        }

        val base = request.baseUrl
        if (base != null && base.isNotBlank()) {
            try {
                URL(base)
            } catch (_: MalformedURLException) {
                return Result.failure(
                    RenderPipelineException(
                        RenderError.InvalidRequest(message = "baseUrl is not a valid URL: $base"),
                    ),
                )
            }
        }

        if (request.mimeType.isBlank()) {
            return Result.failure(
                RenderPipelineException(
                    RenderError.InvalidRequest(message = "mimeType must not be blank"),
                ),
            )
        }

        return Result.success(Unit)
    }
}
