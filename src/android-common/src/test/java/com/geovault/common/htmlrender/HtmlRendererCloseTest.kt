package com.geovault.common.htmlrender

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HtmlRendererCloseTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun close_onMainLooper_doesNotDeadlock() {
        val renderer = HtmlRenderer.create(context)
        renderer.close()
        renderer.close()
    }

    @Test
    fun close_onTestDispatcher_doesNotDeadlock() = runTest {
        val renderer = HtmlRenderer.create(
            context,
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        renderer.close()
    }

    @Test
    fun close_fromBackgroundThread_doesNotDeadlock() {
        val renderer = HtmlRenderer.create(context)
        val done = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        Thread {
            try {
                renderer.close()
            } catch (t: Throwable) {
                synchronized(errors) { errors.add(t) }
            } finally {
                done.countDown()
            }
        }.start()

        pumpMainUntil(done, timeoutMs = 3_000L)
        assertTrue("close() deadlocked", done.await(1, TimeUnit.SECONDS))
        synchronized(errors) {
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun cancel_renderThenClose_doesNotDeadlock() {
        val renderer = HtmlRenderer.create(
            context,
            config = HtmlRendererConfig(
                defaultLoadTimeoutMs = 250L,
                defaultOutputTimeoutMs = 250L,
            ),
        )
        val renderDone = CountDownLatch(1)
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch {
            try {
                renderer.render(
                    HtmlRenderRequest(
                        html = "<html><body>hi</body></html>",
                        output = RenderOutput.Pdf(),
                        loadPolicy = LoadPolicy(loadTimeoutMs = 250L),
                    ),
                )
            } finally {
                renderDone.countDown()
            }
        }
        Thread.sleep(30)
        job.cancel()
        pumpMainUntil(renderDone, timeoutMs = 3_000L)
        assertTrue("cancel/render deadlocked", renderDone.await(1, TimeUnit.SECONDS))

        val closeDone = CountDownLatch(1)
        Thread {
            renderer.close()
            closeDone.countDown()
        }.start()
        pumpMainUntil(closeDone, timeoutMs = 3_000L)
        assertTrue("close after cancel deadlocked", closeDone.await(1, TimeUnit.SECONDS))
        scope.cancel()
    }

    private fun pumpMainUntil(done: CountDownLatch, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (done.count > 0 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
