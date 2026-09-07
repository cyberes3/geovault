package com.geovault.uploader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenamePolicyTest {
    @Test
    fun `withOptionalSuffix appends suffix before extension`() {
        val value = FilenamePolicy.withOptionalSuffix("import.kml", addSuffix = true)
        assertEquals("import_android_upload.kml", value)
    }

    @Test
    fun `withOptionalSuffix keeps name when disabled`() {
        val value = FilenamePolicy.withOptionalSuffix("import.kml", addSuffix = false)
        assertEquals("import.kml", value)
    }
}
