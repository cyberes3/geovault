package com.geovault.common.htmlrender

/**
 * Printable page dimensions in thousandths of an inch (mils).
 *
 * Mapped to [android.print.PrintAttributes.MediaSize] only inside the print bridge.
 */
data class PageSize(
    val id: String,
    val label: String,
    val widthMils: Int,
    val heightMils: Int,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(widthMils > 0) { "widthMils must be positive" }
        require(heightMils > 0) { "heightMils must be positive" }
    }

    companion object {
        val LETTER = PageSize("NA_LETTER", "Letter", 8500, 11000)
        val A4 = PageSize("ISO_A4", "ISO A4", 8270, 11690)
    }
}
