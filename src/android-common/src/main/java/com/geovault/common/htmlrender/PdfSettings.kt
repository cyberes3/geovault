package com.geovault.common.htmlrender

import android.print.PrintAttributes

/**
 * PDF-specific options. Uses the system WebView print pipeline (Chromium).
 *
 * Default [mediaSize] is US Letter so paginated HTML that declares `@page { size: Letter }` matches
 * the physical PDF page; mismatched sizes (e.g. A4 PDF + Letter CSS) often produce an extra
 * nearly-empty page where Chromium's print footer (page/total) appears in the margin.
 */
data class PdfSettings(
    val mediaSize: PrintAttributes.MediaSize = PrintAttributes.MediaSize.NA_LETTER,
    val resolutionDpi: Int = 600,
    val margins: MarginsMm = MarginsMm(),
    val colorMode: PdfColorMode = PdfColorMode.COLOR,
)
