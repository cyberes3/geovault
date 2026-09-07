package com.geovault.common.ui.time

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GeoVaultDateTimeFormat {
    private val dateTime = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
    }
    private val dateTimeSeconds = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.getDefault())
    }
    private val date = ThreadLocal.withInitial {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    }
    private val time = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    fun formatLocalDateTime(epochMillis: Long): String = dateTime.get().format(Date(epochMillis))

    fun formatLocalDateTimeWithSeconds(epochMillis: Long): String =
        dateTimeSeconds.get().format(Date(epochMillis))

    fun formatLocalDate(epochMillis: Long): String = date.get().format(Date(epochMillis))

    fun formatLocalTime(epochMillis: Long): String = time.get().format(Date(epochMillis))
}
