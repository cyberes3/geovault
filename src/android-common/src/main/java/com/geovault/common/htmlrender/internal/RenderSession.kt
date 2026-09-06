package com.geovault.common.htmlrender.internal

import android.content.Context
import android.os.CancellationSignal
import com.geovault.common.htmlrender.HtmlRenderRequest
import com.geovault.common.htmlrender.HtmlRenderResult
import com.geovault.common.htmlrender.HtmlRendererConfig
import com.geovault.common.htmlrender.RenderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext

/**
 * One WebView render pipeline: load HTML, emit PDF, then reset the host.
 */
internal class RenderSession(
    context: Context,
    private val config: HtmlRendererConfig,
) {
    private val host = WebViewSessionHost(context, config)

    fun isDisposed(): Boolean = host.isDisposed()

    suspend fun render(request: HtmlRenderRequest): HtmlRenderResult {
        if (host.isDisposed()) {
            return HtmlRenderResult.Failure(
                RenderError.InvalidRequest(message = "HtmlRenderer is closed"),
            )
        }

        val cancellationSignal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                cancellationSignal.cancel()
                host.stopLoadingIfPrepared()
            }
        }

        return try {
            host.prepare()
            val webView = host.requireWebView()
            DocumentLoader.load(webView, request, config)
            val artifact = PdfRenderSink(config).emit(webView, request, cancellationSignal)
            HtmlRenderResult.Success(artifact)
        } catch (e: TimeoutCancellationException) {
            HtmlRenderResult.Failure(
                RenderError.Timeout(
                    phase = "load_or_output",
                    message = e.message ?: "Timed out",
                    cause = e,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RenderPipelineException) {
            HtmlRenderResult.Failure(e.error)
        } catch (e: Exception) {
            HtmlRenderResult.Failure(
                RenderError.IoFailure(message = e.message ?: "Unexpected error", cause = e),
            )
        } finally {
            if (!host.isDisposed()) {
                host.resetAfterJob()
            }
        }
    }

    fun destroy() {
        host.destroy()
    }
}
