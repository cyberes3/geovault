package com.geovault.tracker.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.geovault.tracker.R

fun AlertDialog.applyDialogButtonColors(
    context: Context,
    destructiveAction: Boolean = false
) {
    val positiveColor = if (destructiveAction) {
        ContextCompat.getColor(context, R.color.error_red)
    } else {
        ContextCompat.getColor(context, com.geovault.common.R.color.gv_common_dialog_positive_button)
    }
    val negativeColor = ContextCompat.getColor(context, com.geovault.common.R.color.gv_common_dialog_negative_button)
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(positiveColor)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(negativeColor)
}
