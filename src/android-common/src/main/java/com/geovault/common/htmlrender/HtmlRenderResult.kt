package com.geovault.common.htmlrender

sealed class HtmlRenderResult {
    data class Success(val artifact: RenderArtifact) : HtmlRenderResult()

    data class Failure(val error: RenderError) : HtmlRenderResult()
}
