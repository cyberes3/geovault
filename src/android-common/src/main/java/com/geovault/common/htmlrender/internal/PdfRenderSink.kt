package com.geovault.common.htmlrender.internal

import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.OpenLayoutResultCallback
import android.print.OpenWriteResultCallback
import android.print.PageRange
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.TypedValue
import android.view.View
import android.webkit.WebView
import com.geovault.common.htmlrender.HtmlRenderRequest
import com.geovault.common.htmlrender.HtmlRendererConfig
import com.geovault.common.htmlrender.RenderArtifact
import com.geovault.common.htmlrender.RenderError
import com.geovault.common.htmlrender.RenderOutput
import java.io.File
import java.util.UUID
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PdfRenderSink(
    private val config: HtmlRendererConfig,
    private val createAdapter: (WebView) -> PrintDocumentAdapter = { webView ->
        webView.createPrintDocumentAdapter("export")
    },
) : RenderSink {

    override suspend fun emit(
        webView: WebView,
        request: HtmlRenderRequest,
        cancellationSignal: CancellationSignal,
    ): RenderArtifact {
        check(Looper.myLooper() == Looper.getMainLooper()) { "PdfRenderSink.emit requires main thread" }
        val pdf = request.output as RenderOutput.Pdf
        val attrs = PrintAttributesFactory.fromPdfSettings(pdf.settings)
        val timeoutMs = config.defaultOutputTimeoutMs

        // Off-screen WebViews often have no real width until laid out; Chromium's print layout can
        // then wrap <pre> as if the viewport were ~device-width and ruin column alignment.
        measureWebViewForPrint(webView)

        val bytes = withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val temp = File.createTempFile("html-render-${UUID.randomUUID()}", ".pdf", webView.context.cacheDir)
                var pfd: ParcelFileDescriptor? = null
                val printCancel = CancellationSignal()
                cancellationSignal.setOnCancelListener { printCancel.cancel() }

                cont.invokeOnCancellation {
                    printCancel.cancel()
                    runCatching { pfd?.close() }
                    temp.delete()
                }

                try {
                    pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE)
                } catch (e: Exception) {
                    temp.delete()
                    cont.resumeWithException(
                        RenderPipelineException(
                            RenderError.IoFailure("Could not create PDF temp file", e),
                        ),
                    )
                    return@suspendCancellableCoroutine
                }

                // OpenLayoutResultCallback / OpenWriteResultCallback are the documented
                // platform bridge to package-private PrintDocumentAdapter callbacks.
                val adapter = createAdapter(webView)
                val pfdNonNull = pfd!!
                adapter.onStart()
                adapter.onLayout(
                    null,
                    attrs,
                    printCancel,
                    object : OpenLayoutResultCallback() {
                        override fun onLayoutFinished(info: PrintDocumentInfo?, changed: Boolean) {
                            if (!cont.isActive) {
                                runCatching { adapter.onFinish() }
                                return
                            }
                            adapter.onWrite(
                                arrayOf(PageRange.ALL_PAGES),
                                pfdNonNull,
                                printCancel,
                                object : OpenWriteResultCallback() {
                                    override fun onWriteFinished(pages: Array<PageRange>) {
                                        runCatching { pfdNonNull.close() }
                                        pfd = null
                                        if (!cont.isActive) {
                                            temp.delete()
                                            runCatching { adapter.onFinish() }
                                            return
                                        }
                                        try {
                                            val data = temp.readBytes()
                                            temp.delete()
                                            adapter.onFinish()
                                            cont.resume(data)
                                        } catch (e: Exception) {
                                            temp.delete()
                                            runCatching { adapter.onFinish() }
                                            cont.resumeWithException(
                                                RenderPipelineException(
                                                    RenderError.IoFailure("Failed to read PDF bytes", e),
                                                ),
                                            )
                                        }
                                    }

                                    override fun onWriteFailed(error: CharSequence?) {
                                        runCatching { pfdNonNull.close() }
                                        pfd = null
                                        temp.delete()
                                        runCatching { adapter.onFinish() }
                                        cont.resumeWithException(
                                            RenderPipelineException(
                                                RenderError.IoFailure(error?.toString() ?: "PDF write failed"),
                                            ),
                                        )
                                    }

                                    override fun onWriteCancelled() {
                                        runCatching { pfdNonNull.close() }
                                        pfd = null
                                        temp.delete()
                                        runCatching { adapter.onFinish() }
                                        cont.cancel()
                                    }
                                },
                            )
                        }

                        override fun onLayoutFailed(error: CharSequence?) {
                            runCatching { pfdNonNull.close() }
                            pfd = null
                            temp.delete()
                            runCatching { adapter.onFinish() }
                            cont.resumeWithException(
                                RenderPipelineException(
                                    RenderError.IoFailure(error?.toString() ?: "PDF layout failed"),
                                ),
                            )
                        }

                        override fun onLayoutCancelled() {
                            runCatching { pfdNonNull.close() }
                            pfd = null
                            temp.delete()
                            runCatching { adapter.onFinish() }
                            cont.cancel()
                        }
                    },
                    null,
                )
            }
        }

        return RenderArtifact.Pdf(bytes)
    }

    /**
     * Gives the document a stable printable width (Letter body minus typical margins) so
     * monospace preformatted text paginates consistently with our `@page` CSS.
     */
    private fun measureWebViewForPrint(webView: WebView) {
        val dm = webView.resources.displayMetrics
        val layoutWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_IN,
            7.5f,
            dm,
        ).toInt().coerceIn(600, 4096)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(layoutWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = webView.measuredHeight.coerceAtLeast(1)
        webView.layout(0, 0, layoutWidthPx, h)
    }
}
