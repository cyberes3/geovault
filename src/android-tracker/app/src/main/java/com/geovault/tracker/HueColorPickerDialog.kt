package com.geovault.tracker

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.geovault.common.R as CommonR

/** Default tracker color, matching `gv_common_color_blue_400`. */
const val DEFAULT_TRACKER_COLOR_HEX: String = "#6C93DE"

/** Fallback tracker color hex used across tracker UI and map rendering. */
fun defaultTrackerColorHex(context: Context): String =
    colorIntToHex(ContextCompat.getColor(context, CommonR.color.gv_common_color_blue_400))

/** Static fallback for contexts where Android resources are unavailable (e.g. JVM tests). */
fun defaultTrackerColorHex(): String = DEFAULT_TRACKER_COLOR_HEX

/**
 * Parses a hex color string (with or without #) to Android color int.
 * Returns fallback tracker color from resources when [hex] is null or invalid.
 */
fun parseHexToColorInt(hex: String?, context: Context): Int {
    val normalized = hex?.trim()?.let { if (it.startsWith("#")) it else "#$it" }?.takeIf { it.isNotEmpty() }
    if (normalized == null) return Color.parseColor(defaultTrackerColorHex(context))
    return try {
        val parsedHex = if (
            normalized.length == 9 &&
            normalized.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        ) {
            val rrggbb = normalized.substring(1, 7)
            val aa = normalized.substring(7, 9)
            "#$aa$rrggbb"
        } else {
            normalized
        }
        Color.parseColor(parsedHex)
    } catch (_: Exception) {
        Color.parseColor(defaultTrackerColorHex(context))
    }
}

fun colorIntToHex(color: Int): String {
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    return String.format("#%02X%02X%02X", r, g, b)
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * AppCompat [AlertDialog] (including QuadFlask's builder) requires an AppCompat-themed context.
 * [MainActivity] uses a platform Material theme, so dialogs must use a wrapped context.
 */
private fun appCompatDialogContext(context: Context): Context {
    val base = context.findActivity() ?: context
    return ContextThemeWrapper(base, AppCompatR.style.Theme_AppCompat_DayNight_Dialog_Alert)
}

/**
 * Shows the QuadFlask hue-based color picker dialog for tracker color selection.
 * On confirm, invokes [onColorPicked] with a six-digit `#RRGGBB` string.
 */
fun showHueColorPickerDialog(
    context: Context,
    initialHex: String?,
    onColorPicked: (String) -> Unit,
) {
    val initialColor = parseHexToColorInt(initialHex, context)
    val dialogContext = appCompatDialogContext(context)
    val dialog: AlertDialog = ColorPickerDialogBuilder
        .with(dialogContext)
        .setTitle(context.getString(R.string.trackers_choose_color))
        .initialColor(initialColor)
        .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
        .density(12)
        .setPositiveButton(context.getString(android.R.string.ok)) { _, selectedColor, _ ->
            onColorPicked(colorIntToHex(selectedColor))
        }
        .setNegativeButton(context.getString(android.R.string.cancel), null)
        .build()
    val isDarkTheme =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    if (isDarkTheme) {
        dialog.setOnShowListener {
            val buttonColor = ContextCompat.getColor(context, CommonR.color.gv_common_text_primary)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }
    }
    dialog.show()
}
