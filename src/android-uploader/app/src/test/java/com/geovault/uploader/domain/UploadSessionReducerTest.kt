package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.net.GeoVaultApiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class UploadSessionReducerTest {

    @Test
    fun itemsAppended_addsAndDedupsByUri() {
        val first = item("a", "alpha.kml", "content://geovault.test/a")
        val duplicate = item("a2", "alpha-copy.kml", "content://geovault.test/a")
        val second = item("b", "beta.kml", "content://geovault.test/b")
        val session = UploadSession()
            .reduce(UploadEvent.ItemsAppended(listOf(first)))
            .reduce(UploadEvent.ItemsAppended(listOf(duplicate, second)))
        assertEquals(listOf(id("a"), id("b")), session.order)
        assertEquals("alpha.kml", session.items.getValue(id("a")).displayName)
    }

    @Test
    fun renameAndRemove_onlyQueuedOrFailedWhileIdle() {
        val queued = item("q", "queued.kml")
        val failed = item("f", "failed.kml", state = UploadItemState.Failed(failure()))
        val succeeded = item("s", "done.kml", state = UploadItemState.Succeeded)
        val idle = UploadSession().reduce(UploadEvent.ItemsAppended(listOf(queued, failed, succeeded)))

        val renamed = idle.reduce(UploadEvent.ItemRenamed(id("q"), "renamed.kml"))
        assertEquals("renamed.kml", renamed.items.getValue(id("q")).displayName)
        assertEquals(idle, idle.reduce(UploadEvent.ItemRenamed(id("s"), "nope.kml")))
        assertEquals(idle, idle.reduce(UploadEvent.ItemRenamed(id("missing"), "x.kml")))

        val removedFailed = renamed.reduce(UploadEvent.ItemRemoved(id("f")))
        assertEquals(listOf(id("q"), id("s")), removedFailed.order)
        assertEquals(removedFailed, removedFailed.reduce(UploadEvent.ItemRemoved(id("s"))))
    }

    @Test
    fun running_ignoresRenameRemoveAndIdleOnlyEvents() {
        val running = startedSession()
        assertEquals(running, running.reduce(UploadEvent.ItemRenamed(id("a"), "nope.kml")))
        assertEquals(running, running.reduce(UploadEvent.ItemRemoved(id("a"))))
        assertEquals(running, running.reduce(UploadEvent.BatchRequested))
        assertEquals(running, running.reduce(UploadEvent.ItemSucceeded(id("missing"))))
    }

    @Test
    fun idle_ignoresInFlightEvents() {
        val idle = UploadSession().reduce(UploadEvent.ItemsAppended(listOf(item("a", "a.kml"))))
        assertEquals(idle, idle.reduce(UploadEvent.ItemStarted(id("a"))))
        assertEquals(idle, idle.reduce(UploadEvent.ItemSucceeded(id("a"))))
        assertEquals(idle, idle.reduce(UploadEvent.ItemFailed(id("a"), failure())))
    }

    @Test
    fun batchRequested_emptyWorkStaysIdle() {
        val succeeded = item("s", "done.kml", state = UploadItemState.Succeeded)
        val session = UploadSession()
            .reduce(UploadEvent.ItemsAppended(listOf(succeeded)))
            .reduce(UploadEvent.BatchRequested)
        assertEquals(UploadSessionPhase.Idle, session.phase)
        assertTrue(session.workItems().isEmpty())
        assertEquals("No valid files to upload", session.statusMessage)
    }

    @Test
    fun workItems_areQueuedPlusFailedOnly() {
        val session = UploadSession().reduce(
            UploadEvent.ItemsAppended(
                listOf(
                    item("q", "q.kml"),
                    item("f", "f.kml", state = UploadItemState.Failed(failure())),
                    item("s", "s.kml", state = UploadItemState.Succeeded),
                    item("u", "u.kml", state = UploadItemState.Uploading),
                ),
            ),
        )
        assertEquals(listOf(id("q"), id("f")), session.workItems())
    }

    @Test
    fun success_neverReentersWorkSet() {
        val finished = runBatch(
            listOf(item("a", "a.kml"), item("b", "b.kml")),
            results = listOf(success, success),
        )
        assertTrue(finished.phase is UploadSessionPhase.Finished)
        assertTrue(finished.workItems().isEmpty())
        val retried = finished.reduce(UploadEvent.BatchRequested)
        assertEquals(UploadSessionPhase.Idle, retried.phase)
        assertEquals(UploadItemState.Succeeded, retried.items.getValue(id("a")).state)
        assertEquals(UploadItemState.Succeeded, retried.items.getValue(id("b")).state)
    }

    @Test
    fun failedRetry_uploadsFailedOnly() {
        val finished = runBatch(
            listOf(item("a", "a.kml"), item("b", "b.kml")),
            results = listOf(success, fail),
        )
        assertEquals(listOf(id("b")), finished.workItems())
        val retry = finished.reduce(UploadEvent.BatchRequested)
        val running = retry.phase as UploadSessionPhase.Running
        assertEquals(1, running.workTotal)
        val afterRetry = retry
            .reduce(UploadEvent.ItemStarted(id("b")))
            .reduce(UploadEvent.ItemSucceeded(id("b")))
        val done = afterRetry.phase as UploadSessionPhase.Finished
        assertEquals(2, done.succeeded)
        assertEquals(0, done.failed)
        assertTrue(afterRetry.workItems().isEmpty())
    }

    @Test
    fun cancelThenRetry_returnsToIdleAndRequeuesInFlight() {
        val running = startedSession()
        val cancelled = running.reduce(UploadEvent.CancelRequested)
        assertEquals(UploadSessionPhase.Idle, cancelled.phase)
        assertEquals(UploadItemState.Queued, cancelled.items.getValue(id("a")).state)
        assertEquals(listOf(id("a"), id("b")), cancelled.workItems())
        val restarted = cancelled.reduce(UploadEvent.BatchRequested)
        assertTrue(restarted.phase is UploadSessionPhase.Running)
        assertEquals(2, (restarted.phase as UploadSessionPhase.Running).workTotal)
    }

    @Test
    fun inFlightCancelled_requeuesPreferredItem() {
        val running = startedSession()
        val cancelled = running.reduce(UploadEvent.InFlightCancelled(id("a")))
        assertEquals(UploadSessionPhase.Idle, cancelled.phase)
        assertEquals(UploadItemState.Queued, cancelled.items.getValue(id("a")).state)
    }

    @Test
    fun appendDuringRunning_keepsPhase() {
        val running = startedSession()
        val appended = running.reduce(UploadEvent.ItemsAppended(listOf(item("c", "c.kml"))))
        assertTrue(appended.phase is UploadSessionPhase.Running)
        assertEquals(listOf(id("a"), id("b"), id("c")), appended.order)
    }

    private val success = true
    private val fail = false

    private fun runBatch(items: List<UploadItem>, results: List<Boolean>): UploadSession {
        var session = UploadSession().reduce(UploadEvent.ItemsAppended(items)).reduce(UploadEvent.BatchRequested)
        items.zip(results).forEach { (item, ok) ->
            session = session.reduce(UploadEvent.ItemStarted(item.id))
            session = if (ok) {
                session.reduce(UploadEvent.ItemSucceeded(item.id))
            } else {
                session.reduce(UploadEvent.ItemFailed(item.id, failure()))
            }
        }
        return session
    }

    private fun startedSession(): UploadSession {
        return UploadSession()
            .reduce(UploadEvent.ItemsAppended(listOf(item("a", "a.kml"), item("b", "b.kml"))))
            .reduce(UploadEvent.BatchRequested)
            .reduce(UploadEvent.ItemStarted(id("a")))
    }

    private fun item(
        key: String,
        name: String,
        uri: String = "content://geovault.test/$key",
        state: UploadItemState = UploadItemState.Queued,
    ): UploadItem {
        return UploadItem(
            id = id(key),
            file = GeoVaultFileRef(
                uri = Uri.parse(uri),
                displayName = name,
                mimeType = "application/vnd.google-earth.kml+xml",
                extension = "kml",
                sizeBytes = 12L,
                source = GeoVaultFileRef.Source.Picker,
            ),
            displayName = name,
            sizeBytes = 12L,
            modifiedAtMs = null,
            state = state,
        )
    }

    private fun id(value: String) = UploadItemId(value)

    private fun failure() = GeoVaultApiFailure(httpCode = 500, serverMessage = "boom", operation = "importUpload")
}
