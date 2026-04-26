package com.geovault.common.htmlrender.internal

import android.os.CancellationSignal
import android.webkit.WebView
import com.geovault.common.htmlrender.HtmlRenderRequest
import com.geovault.common.htmlrender.RenderArtifact

internal fun interface RenderSink {
    suspend fun emit(
        webView: WebView,
        request: HtmlRenderRequest,
        cancellationSignal: CancellationSignal,
    ): RenderArtifact
}
