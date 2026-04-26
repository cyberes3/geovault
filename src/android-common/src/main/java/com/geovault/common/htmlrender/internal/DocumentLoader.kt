package com.geovault.common.htmlrender.internal

import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.geovault.common.htmlrender.HtmlRenderRequest
import com.geovault.common.htmlrender.HtmlRendererConfig
import com.geovault.common.htmlrender.RenderError
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object DocumentLoader {

    suspend fun load(
        webView: WebView,
        request: HtmlRenderRequest,
        config: HtmlRendererConfig,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "DocumentLoader.load requires main thread" }
        val timeoutMs = request.loadPolicy.loadTimeoutMs ?: config.defaultLoadTimeoutMs
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val once = OnceResume(cont)
                val handler = Handler(Looper.getMainLooper())
                var pollRunnable: Runnable? = null

                cont.invokeOnCancellation {
                    webView.stopLoading()
                    pollRunnable?.let(handler::removeCallbacks)
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            val msg = error.description?.toString() ?: "WebView load error"
                            once.fail(
                                RenderPipelineException(RenderError.WebViewError(message = msg)),
                            )
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!cont.isActive) return
                        if (request.loadPolicy.waitForWindowOnLoad) {
                            val poll = object : Runnable {
                                override fun run() {
                                    if (!cont.isActive) return
                                    view.evaluateJavascript(DOCUMENT_READY_SCRIPT) { json ->
                                        if (!cont.isActive) return@evaluateJavascript
                                        val ready = parseReady(json)
                                        if (ready) {
                                            once.succeed()
                                        } else {
                                            handler.postDelayed(this, 50)
                                        }
                                    }
                                }
                            }
                            pollRunnable = poll
                            handler.post(poll)
                        } else {
                            once.succeed()
                        }
                    }
                }

                val base = request.baseUrl?.takeIf { it.isNotBlank() } ?: "about:blank"
                webView.loadDataWithBaseURL(
                    base,
                    request.html,
                    request.mimeType,
                    Charsets.UTF_8.name(),
                    null,
                )
            }
        }
    }

    private const val DOCUMENT_READY_SCRIPT =
        "(function(){return document.readyState==='complete';})()"

    private fun parseReady(json: String?): Boolean {
        if (json == null) return false
        val t = json.trim()
        return t == "true" || t.equals("\"true\"", ignoreCase = false)
    }

    private class OnceResume(
        private val cont: CancellableContinuation<Unit>,
    ) {
        private val done = AtomicBoolean(false)

        fun succeed() {
            if (done.compareAndSet(false, true)) {
                cont.resume(Unit)
            }
        }

        fun fail(ex: Throwable) {
            if (done.compareAndSet(false, true)) {
                cont.resumeWithException(ex)
            }
        }
    }
}
