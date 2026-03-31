package com.geovault.common.ui.theme

import androidx.annotation.ColorInt
import androidx.compose.ui.graphics.Color

object GeoVaultColorTokens {
    @ColorInt const val PRIMARY_BLUE_INT: Int = 0xFF163D8A.toInt()
    @ColorInt const val PRIMARY_BLUE_DARK_INT: Int = 0xFF063288.toInt()
    @ColorInt const val SURFACE_INT: Int = 0xFFFFFFFF.toInt()
    @ColorInt const val LIST_BACKGROUND_INT: Int = 0xFFF3F6FA.toInt()
    @ColorInt const val TEXT_PRIMARY_INT: Int = 0xFF061D4A.toInt()
    @ColorInt const val TEXT_SECONDARY_INT: Int = 0xFF4B5563.toInt()
    @ColorInt const val BORDER_LIGHT_INT: Int = 0xFFC4D2ED.toInt()
    @ColorInt const val GRAY_200_INT: Int = 0xFFE5E7EB.toInt()
    @ColorInt const val GRAY_300_INT: Int = 0xFFD1D5DB.toInt()
    @ColorInt const val GRAY_400_INT: Int = 0xFF9CA3AF.toInt()
    @ColorInt const val TOGGLE_TITLE_INT: Int = 0xFF000000.toInt()
    @ColorInt const val TOGGLE_HELP_TEXT_INT: Int = 0xFF808080.toInt()
    @ColorInt const val TOGGLE_UNCHECKED_THUMB_INT: Int = GRAY_200_INT
    @ColorInt const val TOGGLE_UNCHECKED_TRACK_INT: Int = GRAY_400_INT
    @ColorInt const val SUCCESS_INT: Int = 0xFF5B8A3C.toInt()
    @ColorInt const val ERROR_INT: Int = 0xFFFF3E41.toInt()

    @ColorInt const val DARK_SURFACE_INT: Int = 0xFF111827.toInt()
    @ColorInt const val DARK_BACKGROUND_INT: Int = 0xFF051638.toInt()
    @ColorInt const val DARK_ON_SURFACE_INT: Int = 0xFFE5E7EB.toInt()
    @ColorInt const val DARK_ON_BACKGROUND_INT: Int = 0xFFE5E7EB.toInt()
    @ColorInt const val DARK_BORDER_LIGHT_INT: Int = 0xFF404040.toInt()
    @ColorInt const val DARK_TOGGLE_TITLE_INT: Int = 0xFFE5E7EB.toInt()
    @ColorInt const val DARK_TOGGLE_HELP_TEXT_INT: Int = 0xFFA0A0A0.toInt()
    @ColorInt const val DARK_TOGGLE_UNCHECKED_THUMB_INT: Int = 0xFFBFC8D6.toInt()
    @ColorInt const val DARK_TOGGLE_UNCHECKED_TRACK_INT: Int = 0xFF626D7C.toInt()
    @ColorInt const val DARK_ERROR_INT: Int = 0xFFF28B8D.toInt()

    @ColorInt const val SNACKBAR_BACKGROUND_INT: Int = 0xFF323232.toInt()
    @ColorInt const val SNACKBAR_MESSAGE_INT: Int = 0xFFFFFFFF.toInt()
    @ColorInt const val PURPLE_500_INT: Int = 0xFFCB48B7.toInt()

    /** Frontend `--main-yellow` / `--color-yellow-500` (#F4AC45). */
    @ColorInt const val MAIN_YELLOW_INT: Int = 0xFFF4AC45.toInt()
    @ColorInt const val YELLOW_500_INT: Int = MAIN_YELLOW_INT
    @ColorInt const val SNACKBAR_ACTION_INT: Int = MAIN_YELLOW_INT

    val PrimaryBlue: Color = Color(PRIMARY_BLUE_INT)
    val PrimaryBlueDark: Color = Color(PRIMARY_BLUE_DARK_INT)
    val Surface: Color = Color(SURFACE_INT)
    val ListBackground: Color = Color(LIST_BACKGROUND_INT)
    val TextPrimary: Color = Color(TEXT_PRIMARY_INT)
    val TextSecondary: Color = Color(TEXT_SECONDARY_INT)
    val BorderLight: Color = Color(BORDER_LIGHT_INT)
    val Gray200: Color = Color(GRAY_200_INT)
    val Gray300: Color = Color(GRAY_300_INT)
    val Gray400: Color = Color(GRAY_400_INT)
    val ToggleTitle: Color = Color(TOGGLE_TITLE_INT)
    val ToggleHelpText: Color = Color(TOGGLE_HELP_TEXT_INT)
    val ToggleUncheckedThumb: Color = Color(TOGGLE_UNCHECKED_THUMB_INT)
    val ToggleUncheckedTrack: Color = Color(TOGGLE_UNCHECKED_TRACK_INT)
    val Success: Color = Color(SUCCESS_INT)
    val Error: Color = Color(ERROR_INT)

    val DarkSurface: Color = Color(DARK_SURFACE_INT)
    val DarkBackground: Color = Color(DARK_BACKGROUND_INT)
    val DarkOnSurface: Color = Color(DARK_ON_SURFACE_INT)
    val DarkOnBackground: Color = Color(DARK_ON_BACKGROUND_INT)
    val DarkBorderLight: Color = Color(DARK_BORDER_LIGHT_INT)
    val DarkToggleTitle: Color = Color(DARK_TOGGLE_TITLE_INT)
    val DarkToggleHelpText: Color = Color(DARK_TOGGLE_HELP_TEXT_INT)
    val DarkToggleUncheckedThumb: Color = Color(DARK_TOGGLE_UNCHECKED_THUMB_INT)
    val DarkToggleUncheckedTrack: Color = Color(DARK_TOGGLE_UNCHECKED_TRACK_INT)
    val DarkError: Color = Color(DARK_ERROR_INT)

    val SnackbarBackground: Color = Color(SNACKBAR_BACKGROUND_INT)
    val MainYellow: Color = Color(MAIN_YELLOW_INT)
    val Yellow500: Color = Color(YELLOW_500_INT)
    val SnackbarAction: Color = Yellow500
    val SnackbarMessage: Color = Color(SNACKBAR_MESSAGE_INT)
    val Purple500: Color = Color(PURPLE_500_INT)
}
