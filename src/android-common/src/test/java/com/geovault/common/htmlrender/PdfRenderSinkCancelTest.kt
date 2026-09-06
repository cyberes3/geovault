package com.geovault.common.htmlrender

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.geovault.common.htmlrender.internal.PdfRenderSink
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfRenderSinkCancelTest {

    @Test
    fun cancelledLayout_invokesOnLayoutCancelled() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val webView = WebView(context)
        val adapter = LayoutCancellingAdapter()
        val sink = PdfRenderSink(
            config = HtmlRendererConfig(defaultOutputTimeoutMs = 5_000L),
            createAdapter = { adapter },
        )
        val request = HtmlRenderRequest(
            html = "<html></html>",
            output = RenderOutput.Pdf(),
        )
        val result = runCatching {
            sink.emit(webView, request, CancellationSignal())
        }

        assertTrue(adapter.onLayoutCancelledInvoked.get())
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    private class LayoutCancellingAdapter : PrintDocumentAdapter() {
        val onLayoutCancelledInvoked = AtomicBoolean(false)

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            onLayoutCancelledInvoked.set(true)
            callback.onLayoutCancelled()
        }

        override fun onWrite(
            pages: Array<PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback,
        ) {
            error("onWrite should not run after layout cancel")
        }
    }
}
