package com.geovault.common.htmlrender

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Renders HTML to PDF using the system WebView (Chromium).
 *
 * **Threading**: [render] is suspend-safe but **not concurrent**: only one render may run at a time
 * per instance; additional calls wait on an internal mutex.
 *
 * **Context**: Prefer an [android.app.Activity] context when constructing via [create] so
 * WebView matches the app theme. [android.content.Context.getApplicationContext] works for
 * headless use but may differ in font/theming.
 *
 * **Memory**: PDF output is read fully into a [ByteArray] from a temp file.
 *
 * **Security**: See [HtmlRendererConfig] for network blocking and scripting. Do not pass unsanitized
 * third-party HTML without understanding WebView isolation and `file://` exposure.
 */
interface HtmlRenderer : AutoCloseable {

    /**
     * Runs the full pipeline on the main thread (via the renderer's main dispatcher) except where
     * the implementation explicitly offloads work.
     */
    suspend fun render(request: HtmlRenderRequest): HtmlRenderResult

    companion object {
        /**
         * @param context UI or application context (activity recommended).
         * @param config global limits and WebView flags.
         * @param mainDispatcher dispatcher bound to the main looper; inject a test double when unit testing.
         */
        fun create(
            context: Context,
            config: HtmlRendererConfig = HtmlRendererConfig.DEFAULT,
            mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        ): HtmlRenderer = DefaultHtmlRenderer(context, config, mainDispatcher)
    }
}
