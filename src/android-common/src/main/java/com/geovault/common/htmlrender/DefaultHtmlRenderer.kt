package com.geovault.common.htmlrender

import android.content.Context
import android.os.CancellationSignal
import android.os.Looper
import com.geovault.common.htmlrender.internal.DocumentLoader
import com.geovault.common.htmlrender.internal.PdfRenderSink
import com.geovault.common.htmlrender.internal.RenderPipelineException
import com.geovault.common.htmlrender.internal.RenderSink
import com.geovault.common.htmlrender.internal.RequestValidator
import com.geovault.common.htmlrender.internal.SessionStateMachine
import com.geovault.common.htmlrender.internal.WebViewSessionHost
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Default [HtmlRenderer] using a dedicated WebView session. See [HtmlRenderer] for threading and security notes.
 *
 * [close] may block the calling thread until the WebView is destroyed on the main looper.
 * It must not deadlock when invoked from the main thread (e.g. after [render] returns inside
 * [kotlin.use]); the main-thread path therefore runs [host.destroy] directly under the mutex
 * without nesting [runBlocking] on another dispatcher that would wait on Main.
 */
class DefaultHtmlRenderer(
    context: Context,
    private val config: HtmlRendererConfig = HtmlRendererConfig.DEFAULT,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : HtmlRenderer, AutoCloseable {

    private val appContext = context.applicationContext
    private val host = WebViewSessionHost(appContext, config)
    private val mutex = Mutex()
    private val stateMachine = SessionStateMachine()

    override suspend fun render(request: HtmlRenderRequest): HtmlRenderResult {
        RequestValidator.validate(request, config).exceptionOrNull()?.let { e ->
            val err = when (e) {
                is RenderPipelineException -> e.error
                else -> RenderError.InvalidRequest(message = e.message ?: "Invalid request", cause = e)
            }
            return HtmlRenderResult.Failure(err)
        }

        return mutex.withLock {
            withContext(mainDispatcher) {
                renderOnMain(request)
            }
        }
    }

    private suspend fun renderOnMain(request: HtmlRenderRequest): HtmlRenderResult {
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
            stateMachine.moveToLoading()
            DocumentLoader.load(webView, request, config)
            stateMachine.moveToReady()

            val sink: RenderSink = PdfRenderSink(config)
            stateMachine.moveToOutputting()
            val artifact = sink.emit(webView, request, cancellationSignal)
            HtmlRenderResult.Success(artifact)
        } catch (e: TimeoutCancellationException) {
            HtmlRenderResult.Failure(
                RenderError.Timeout(
                    phase = "load_or_output",
                    message = e.message ?: "Timed out",
                    cause = e,
                ),
            )
        } catch (e: RenderPipelineException) {
            HtmlRenderResult.Failure(e.error)
        } catch (e: Exception) {
            HtmlRenderResult.Failure(
                RenderError.IoFailure(message = e.message ?: "Unexpected error", cause = e),
            )
        } finally {
            stateMachine.resetToIdle()
            host.resetAfterJob()
        }
    }

    override fun close() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runBlocking {
                mutex.withLock {
                    host.destroy()
                }
            }
        } else {
            runBlocking {
                mutex.withLock {
                    withContext(mainDispatcher) {
                        host.destroy()
                    }
                }
            }
        }
    }
}
