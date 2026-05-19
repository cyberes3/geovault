package com.geovault.common.logging

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultAppVersionLogTest {

    @Test
    fun parseBuildDateFromVersionName_extractsDatePrefix() {
        assertEquals(
            "2026-05-19",
            GeoVaultAppVersionLog.parseBuildDateFromVersionName("2026-05-19-abc1234567"),
        )
    }

    @Test
    fun parseBuildDateFromVersionName_unknownWhenNoDatePrefix() {
        assertEquals("unknown", GeoVaultAppVersionLog.parseBuildDateFromVersionName("1.0.0"))
    }

    @Test
    fun parseBuildDateFromVersionName_unknownWhenEmpty() {
        assertEquals("unknown", GeoVaultAppVersionLog.parseBuildDateFromVersionName(""))
    }
}
