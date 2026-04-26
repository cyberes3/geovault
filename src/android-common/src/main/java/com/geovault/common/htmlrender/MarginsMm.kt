package com.geovault.common.htmlrender

/**
 * Print margins in millimeters (applied to PDF output via [android.print.PrintAttributes]).
 */
data class MarginsMm(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)
