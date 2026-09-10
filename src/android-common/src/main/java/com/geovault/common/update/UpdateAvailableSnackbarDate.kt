package com.geovault.common.update

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object UpdateAvailableSnackbarDate {
    private val monthDayYear: DateTimeFormatter = DateTimeFormatter.ofPattern("M-d-yyyy")

    fun format(versionLabel: String, publishedAtIso: String, unknown: String): String {
        parseVersionLabelDate(versionLabel)?.let { return it.format(monthDayYear) }
        parsePublishedAt(publishedAtIso)?.let { return it.format(monthDayYear) }
        return unknown
    }

    private fun parseVersionLabelDate(versionLabel: String): LocalDate? {
        val token = versionLabel.trim().substringBefore(' ')
        if (token.isBlank()) return null
        return try {
            LocalDate.parse(token, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parsePublishedAt(iso: String): LocalDate? {
        val trimmed = iso.trim()
        if (trimmed.isBlank()) return null
        return try {
            Instant.parse(trimmed).atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
