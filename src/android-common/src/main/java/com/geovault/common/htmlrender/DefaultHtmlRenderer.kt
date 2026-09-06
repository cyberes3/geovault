package com.geovault.common.htmlrender

import android.content.Context
import android.os.Looper
import com.geovault.common.htmlrender.internal.RenderPipelineException
import com.geovault.common.htmlrender.internal.RenderSession
import com.geovault.common.htmlrender.internal.RequestValidator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Default [HtmlRenderer] using a dedicated [RenderSession]. See [HtmlRenderer] for threading notes.
 *
 * [close] never uses [runBlocking] on the main thread. An in-flight [render] that holds the mutex
 * is cancelled; that render's `finally` destroys the session on main.
 */
internal class DefaultHtmlRenderer(
    context: Context,
    private val config: HtmlRendererConfig = HtmlRendererConfig.DEFAULT,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : HtmlRenderer {

    private val session = RenderSession(context.applicationContext, config)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var activeJob: Job? = null

    override suspend fun render(request: HtmlRenderRequest): HtmlRenderResult {
        RequestValidator.validate(request, config).exceptionOrNull()?.let { e ->
            val err = when (e) {
                is RenderPipelineException -> e.error
                else -> RenderError.InvalidRequest(message = e.message ?: "Invalid request", cause = e)
            }
            return HtmlRenderResult.Failure(err)
        }

        return mutex.withLock {
            if (closed.get() || session.isDisposed()) {
                return@withLock HtmlRenderResult.Failure(
                    RenderError.InvalidRequest(message = "HtmlRenderer is closed"),
                )
            }
            activeJob = coroutineContext[Job]
            try {
                withContext(mainDispatcher) {
                    session.render(request)
                }
            } finally {
                activeJob = null
                if (closed.get() && !session.isDisposed()) {
                    withContext(mainDispatcher) {
                        session.destroy()
                    }
                }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeJob?.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (mutex.tryLock()) {
                try {
                    session.destroy()
                } finally {
                    mutex.unlock()
                }
            }
            return
        }
        runBlocking {
            mutex.withLock {
                withContext(mainDispatcher) {
                    session.destroy()
                }
            }
        }
    }
}
