package com.geovault.common.htmlrender.internal

import android.print.PrintAttributes
import com.geovault.common.htmlrender.MarginsMm
import com.geovault.common.htmlrender.PageSize
import com.geovault.common.htmlrender.PdfColorMode
import com.geovault.common.htmlrender.PdfSettings
import kotlin.math.roundToInt

internal object PrintAttributesFactory {

    fun fromPdfSettings(settings: PdfSettings): PrintAttributes {
        val margins = marginsToPrintMargins(settings.margins)
        val colorMode = when (settings.colorMode) {
            PdfColorMode.COLOR -> PrintAttributes.COLOR_MODE_COLOR
            PdfColorMode.MONOCHROME -> PrintAttributes.COLOR_MODE_MONOCHROME
        }
        return PrintAttributes.Builder()
            .setMediaSize(toMediaSize(settings.pageSize))
            .setResolution(
                PrintAttributes.Resolution(
                    "html-render",
                    "html-render",
                    settings.resolutionDpi,
                    settings.resolutionDpi,
                ),
            )
            .setMinMargins(margins)
            .setColorMode(colorMode)
            .build()
    }

    private fun toMediaSize(pageSize: PageSize): PrintAttributes.MediaSize =
        PrintAttributes.MediaSize(
            pageSize.id,
            pageSize.label,
            pageSize.widthMils,
            pageSize.heightMils,
        )

    private fun marginsToPrintMargins(m: MarginsMm): PrintAttributes.Margins {
        fun mmToMils(mm: Float): Int = (mm * 1000f / 25.4f).roundToInt().coerceAtLeast(0)
        return PrintAttributes.Margins(
            mmToMils(m.left),
            mmToMils(m.top),
            mmToMils(m.right),
            mmToMils(m.bottom),
        )
    }
}
