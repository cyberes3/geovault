package com.geovault.tracker

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.appcompat.R as AppCompatR
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import com.flask.colorpicker.ColorPickerView
import com.flask.colorpicker.builder.ColorPickerDialogBuilder
import com.geovault.common.ui.theme.GeoVaultColorHex
import com.geovault.common.ui.theme.GeoVaultColorTokens

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
    val initialColor = GeoVaultColorHex.parseColorInt(initialHex, GeoVaultColorTokens.Blue400.toArgb())
    val dialogContext = appCompatDialogContext(context)
    val dialog: AlertDialog = ColorPickerDialogBuilder
        .with(dialogContext)
        .setTitle(context.getString(R.string.trackers_choose_color))
        .initialColor(initialColor)
        .wheelType(ColorPickerView.WHEEL_TYPE.FLOWER)
        .density(12)
        .setPositiveButton(context.getString(android.R.string.ok)) { _, selectedColor, _ ->
            onColorPicked(GeoVaultColorHex.formatRgb(selectedColor))
        }
        .setNegativeButton(context.getString(android.R.string.cancel), null)
        .build()
    val isDarkTheme =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    if (isDarkTheme) {
        dialog.setOnShowListener {
            val buttonColor = GeoVaultColorTokens.Dark.TextPrimary.toArgb()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(buttonColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(buttonColor)
        }
    }
    dialog.show()
}
