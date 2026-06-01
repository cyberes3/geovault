package com.geovault.uploader.domain

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerSelectionRouterTest {

    private val router = PickerSelectionRouter { uri ->
        uri.lastPathSegment ?: "file"
    }

    @Test
    fun singleSupportedUri_returnsSupportedSelection() {
        val uri = Uri.parse("content://test/document/1.kml")
        val decision = router.decide(listOf(uri), applyExtensionFilter = true)
        assertTrue(decision is PickerRouteDecision.SupportedSelection)
        val selection = decision as PickerRouteDecision.SupportedSelection
        assertEquals(listOf(uri), selection.uris)
        assertTrue(selection.rejectedFileNames.isEmpty())
    }

    @Test
    fun allRejected_returnsRejectedOnly() {
        val uri = Uri.parse("content://test/document/1.txt")
        val decision = router.decide(listOf(uri), applyExtensionFilter = true)
        assertTrue(decision is PickerRouteDecision.RejectedOnly)
    }

    @Test
    fun mixedSelection_partitionsSupportedAndRejected() {
        val supported = Uri.parse("content://test/a.gpx")
        val rejected = Uri.parse("content://test/b.pdf")
        val decision = router.decide(listOf(supported, rejected), applyExtensionFilter = true)
        assertTrue(decision is PickerRouteDecision.SupportedSelection)
        val selection = decision as PickerRouteDecision.SupportedSelection
        assertEquals(listOf(supported), selection.uris)
        assertEquals(listOf("b.pdf"), selection.rejectedFileNames)
    }
}
