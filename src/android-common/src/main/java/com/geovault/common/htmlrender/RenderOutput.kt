package com.geovault.common.htmlrender

sealed class RenderOutput {
    data class Pdf(val settings: PdfSettings = PdfSettings()) : RenderOutput()
}
