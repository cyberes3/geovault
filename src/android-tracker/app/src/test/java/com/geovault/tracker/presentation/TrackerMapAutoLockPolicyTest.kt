package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerMapAutoLockPolicyTest {

    @Test
    fun recordingStart_singleSession_withDisplayed_returnsSelectionLock() {
        val r = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = " t1 ",
            selectedTrackerId = "sel",
        )
        assertEquals(
            TrackerMapAutoLockOnRecordingResult.SelectionLock("t1"),
            r
        )
    }

    @Test
    fun recordingStart_singleSession_blankDisplayed_fallsBackToSelected() {
        val r = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "  ",
            selectedTrackerId = "abc",
        )
        assertEquals(
            TrackerMapAutoLockOnRecordingResult.SelectionLock("abc"),
            r
        )
    }

    @Test
    fun recordingStart_singleSession_bothBlank_returnsNone() {
        val r = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "",
            selectedTrackerId = "   ",
        )
        assertEquals(TrackerMapAutoLockOnRecordingResult.None, r)
    }

    @Test
    fun recordingStart_allQueue_returnsLiveActiveFit() {
        val r = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            displayedTrackerId = "",
            selectedTrackerId = "",
        )
        assertEquals(TrackerMapAutoLockOnRecordingResult.LiveActiveFit, r)
    }

    @Test
    fun recordingStart_group_returnsLiveActiveFit() {
        val r = TrackerMapAutoLockPolicy.resolveAutoLockOnRecordingStart(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            displayedTrackerId = "g1",
            selectedTrackerId = "g2",
        )
        assertEquals(TrackerMapAutoLockOnRecordingResult.LiveActiveFit, r)
    }

    @Test
    fun singleStream_emptyToOne_matchingDisplayed_returnsId() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            previousTargets = emptySet(),
            nextTargets = setOf("remote-1"),
            displayedTrackerId = "remote-1",
        )
        assertEquals("remote-1", id)
    }

    @Test
    fun singleStream_multiToOne_matchingDisplayed_returnsId() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            previousTargets = setOf("a", "b"),
            nextTargets = setOf("only"),
            displayedTrackerId = "only",
        )
        assertEquals("only", id)
    }

    @Test
    fun singleStream_unchangedSingle_returnsNull() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            previousTargets = setOf("x"),
            nextTargets = setOf("x"),
            displayedTrackerId = "x",
        )
        assertNull(id)
    }

    @Test
    fun singleStream_displayedMismatch_returnsNull() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            previousTargets = emptySet(),
            nextTargets = setOf("a"),
            displayedTrackerId = "b",
        )
        assertNull(id)
    }

    @Test
    fun singleStream_wrongMode_returnsNull() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            previousTargets = emptySet(),
            nextTargets = setOf("a"),
            displayedTrackerId = "a",
        )
        assertNull(id)
    }

    @Test
    fun singleStream_twoTargets_returnsNull() {
        val id = TrackerMapAutoLockPolicy.resolveAutoSelectionLockForSingleStream(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            previousTargets = emptySet(),
            nextTargets = setOf("a", "b"),
            displayedTrackerId = "a",
        )
        assertNull(id)
    }
}
