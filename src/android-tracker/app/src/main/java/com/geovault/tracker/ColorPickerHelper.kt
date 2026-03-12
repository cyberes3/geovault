package com.geovault.tracker

import android.content.res.Configuration
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder

/** Default tracker color (blue-400 from frontend scale). Use this constant for all default color fallbacks. */
internal const val DEFAULT_TRACKER_COLOR_HEX = "#6C93DE"

/**
 * Parses a hex color string (with or without #) to Android color int.
 * Returns default blue if invalid.
 */
fun parseHexToColor(hex: String?): Int {
    val normalized = hex?.trim()?.let { if (it.startsWith("#")) it else "#$it" } ?: return Color.parseColor(DEFAULT_TRACKER_COLOR_HEX)
    return try {
        Color.parseColor(normalized)
    } catch (_: Exception) {
        Color.parseColor(DEFAULT_TRACKER_COLOR_HEX)
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
    val initialColor = parseHexToColor(initialHex)
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
    val color = parseHexToColor(hex)
    val density = view.context.resources.displayMetrics.density
    val sizePx = (40 * density).toInt()
    val shape = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(sizePx, sizePx)
    }
    view.background = shape
}
