package com.geovault.common.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpdateAvailableSnackbarDateTest {

    @Test
    fun `formats apk version label date as month-day-year`() {
        val formatted = UpdateAvailableSnackbarDate.format(
            versionLabel = "2026-04-26 0dea2b95c2",
            publishedAtIso = "2026-04-27T12:00:00Z",
            unknown = "Unknown",
        )
        assertEquals("04-26-2026", formatted)
    }

    @Test
    fun `zero-pads month and day`() {
        val formatted = UpdateAvailableSnackbarDate.format(
            versionLabel = "2026-01-02 abcdef1234",
            publishedAtIso = "",
            unknown = "Unknown",
        )
        assertEquals("01-02-2026", formatted)
    }

    @Test
    fun `falls back to published date when version label has no date`() {
        val iso = "2024-06-15T18:00:00Z"
        val expected = Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("MM-dd-yyyy"))
        val formatted = UpdateAvailableSnackbarDate.format(
            versionLabel = "v2",
            publishedAtIso = iso,
            unknown = "Unknown",
        )
        assertEquals(expected, formatted)
    }

    @Test
    fun `returns unknown when neither date is parseable`() {
        val formatted = UpdateAvailableSnackbarDate.format(
            versionLabel = "v2",
            publishedAtIso = "",
            unknown = "Unknown",
        )
        assertEquals("Unknown", formatted)
    }
}
