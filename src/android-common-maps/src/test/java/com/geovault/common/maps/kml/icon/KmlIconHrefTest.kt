package com.geovault.common.maps.kml.icon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmlIconHrefTest {

    @Test
    fun httpAndHttps_areRemote() {
        assertEquals(
            KmlIconHref.Remote("http://maps.google.com/mapfiles/kml/pushpin/red-pushpin.png"),
            KmlIconHref.parse("http://maps.google.com/mapfiles/kml/pushpin/red-pushpin.png"),
        )
        assertEquals(
            KmlIconHref.Remote("https://example.com/icon.png"),
            KmlIconHref.parse("  https://example.com/icon.png  "),
        )
    }

    @Test
    fun archiveRelative_includesGoogleEarthColonSlash() {
        assertEquals(KmlIconHref.ArchiveRelative("files/icon.png"), KmlIconHref.parse("files/icon.png"))
        assertEquals(KmlIconHref.ArchiveRelative("icon.png"), KmlIconHref.parse("icon.png"))
        assertEquals(KmlIconHref.ArchiveRelative(":/files/icon.png"), KmlIconHref.parse(":/files/icon.png"))
    }

    @Test
    fun blankAndNonHttpSchemes_areUnusable() {
        assertEquals(KmlIconHref.Unusable, KmlIconHref.parse(null))
        assertEquals(KmlIconHref.Unusable, KmlIconHref.parse("  "))
        assertEquals(KmlIconHref.Unusable, KmlIconHref.parse("file:///tmp/icon.png"))
        assertEquals(KmlIconHref.Unusable, KmlIconHref.parse("content://media/icon.png"))
    }
}
