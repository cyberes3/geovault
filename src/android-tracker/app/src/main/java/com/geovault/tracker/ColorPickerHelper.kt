package com.geovault.tracker

import android.content.res.Configuration
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder

/** Fallback tracker color hex from [R.color.default_tracker_color] (gv_common_blue_400). */
fun defaultTrackerColorHex(context: Context): String =
    colorToHex(ContextCompat.getColor(context, R.color.default_tracker_color))

/**
 * Parses a hex color string (with or without #) to Android color int.
 * Returns fallback tracker color from resources when [hex] is null or invalid.
 */
fun parseHexToColor(hex: String?, context: Context): Int {
    val normalized = hex?.trim()?.let { if (it.startsWith("#")) it else "#$it" }?.takeIf { it.isNotEmpty() }
    if (normalized == null) return Color.parseColor(defaultTrackerColorHex(context))
    return try {
        // Android parses 8-digit hex as #AARRGGBB, but some tracker colors can arrive as
        // #RRGGBBAA. Convert that form so icon tint matches map line color rendering.
        val parsedHex = if (normalized.length == 9 && normalized.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
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

/**
 * Converts Android color int to 6-digit hex string (no alpha).
 */
fun colorToHex(color: Int): String {
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    return String.format("#%02X%02X%02X", r, g, b)
}

/**
 * Shows the QuadFlask hue-based color picker dialog. On confirm, updates [colorEdit] and [onColorPicked].
 * [initialHex] is the current color to show (e.g. from colorEdit text or tracker).
 */
fun showHueColorPickerDialog(
    context: Context,
    initialHex: String?,
    colorEdit: EditText,
    onColorPicked: ((String) -> Unit)? = null
) {
    val initialColor = parseHexToColor(initialHex, context)
    val dialog = ColorPickerDialogBuilder
        .with(context)
        .setTitle(context.getString(R.string.choose_color))
        .initialColor(initialColor)
        .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
        .density(12)
        .setPositiveButton(context.getString(android.R.string.ok)) { _, selectedColor, _ ->
            val hex = colorToHex(selectedColor)
            colorEdit.setText(hex)
            onColorPicked?.invoke(hex)
        }
        .setNegativeButton(context.getString(android.R.string.cancel), null)
        .build()
    val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    if (isDarkTheme) {
        dialog.setOnShowListener {
            val buttonColor = context.getColor(R.color.text_primary)
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }
    }
    dialog.show()
}

/**
 * Updates the color preview [View]'s background to [hex]. Use with the 40dp circular preview in edit/new tracker forms.
 */
fun updateColorPreview(view: View, hex: String?) {
    val color = parseHexToColor(hex, view.context)
    val density = view.context.resources.displayMetrics.density
    val sizePx = (40 * density).toInt()
    val shape = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(sizePx, sizePx)
    }
    view.background = shape
}
