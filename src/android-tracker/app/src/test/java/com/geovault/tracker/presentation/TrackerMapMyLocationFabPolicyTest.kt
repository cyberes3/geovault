package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerMapMyLocationFabPolicyTest {

    private data class Case(
        val expectShow: Boolean,
        val mode: TrackerMapDisplayMode,
        val displayed: String,
        val selected: String,
        val label: String,
    )

    @Test
    fun shouldShowFab_table() {
        val cases = listOf(
            Case(
                expectShow = false,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "",
                selected = "A",
                label = "single blank displayed falls back to selected A",
            ),
            Case(
                expectShow = false,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "A",
                selected = "A",
                label = "single displayed equals selected",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "B",
                selected = "A",
                label = "single another tracker while A selected",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.ALL_QUEUE,
                displayed = "A",
                selected = "A",
                label = "all queue even when ids match",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                displayed = "A",
                selected = "A",
                label = "group placeholder",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "B",
                selected = "",
                label = "single other tracker no selected",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "",
                selected = "",
                label = "single both blank effective empty",
            ),
            Case(
                expectShow = false,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "  A  ",
                selected = "A",
                label = "trimming matches selected",
            ),
            Case(
                expectShow = true,
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                displayed = "  A  ",
                selected = "B",
                label = "trimming displayed differs from selected",
            ),
        )
        cases.forEach { c ->
            assertEquals(
                c.label,
                c.expectShow,
                TrackerMapMyLocationFabPolicy.shouldShowFab(
                    mode = c.mode,
                    displayedTrackerId = c.displayed,
                    selectedTrackerId = c.selected,
                ),
            )
        }
    }
}
