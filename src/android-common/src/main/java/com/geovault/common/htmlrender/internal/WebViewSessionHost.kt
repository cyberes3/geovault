package com.geovault.common.htmlrender.internal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import com.geovault.common.htmlrender.HtmlRendererConfig

/**
 * Owns a single [WebView] on the main thread. [prepare] must be called before use.
 */
internal class WebViewSessionHost(
    private val context: Context,
    private val config: HtmlRendererConfig,
) {
    private var webView: WebView? = null
    private var disposed = false

    fun requireWebView(): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewSessionHost requires main thread" }
        check(!disposed) { "HtmlRenderer is closed" }
        return webView ?: error("WebView not prepared")
    }

    fun isDisposed(): Boolean = disposed

    @SuppressLint("SetJavaScriptEnabled")
    fun prepare() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewSessionHost.prepare requires main thread" }
        if (disposed) return
        if (webView != null) return
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = config.enableJavaScript
            blockNetworkLoads = config.blockNetworkLoads
            domStorageEnabled = config.enableJavaScript
            loadWithOverviewMode = false
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            if (config.blockNetworkLoads) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
        webView = wv
    }

    fun resetAfterJob() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewSessionHost.resetAfterJob requires main thread" }
        val wv = webView ?: return
        wv.stopLoading()
        wv.loadUrl("about:blank")
        wv.clearHistory()
    }

    /** Safe when [prepare] has not run yet or after [destroy]. */
    fun stopLoadingIfPrepared() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        webView?.stopLoading()
    }

    fun destroy() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebViewSessionHost.destroy requires main thread" }
        disposed = true
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            removeAllViews()
            destroy()
        }
        webView = null
    }
}
