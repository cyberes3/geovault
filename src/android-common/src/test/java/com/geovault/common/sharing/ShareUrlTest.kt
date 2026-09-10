package com.geovault.common.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareUrlTest {
    @Test
    fun builders_emitSocialAndSpaPaths() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals("/share/track/$id/", ShareUrl.trackSocial(id))
        assertEquals("/#/extensions/live-track/share?id=$id", ShareUrl.trackSpa(id))
        assertEquals("/share/map/$id/", ShareUrl.mapSocial(id))
        assertEquals("/#/mapshare?id=$id", ShareUrl.mapSpa(id))
    }

    @Test
    fun parse_readsSocialPathnames() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(id, ShareUrl.parseTrackSocialPath("/share/track/$id/"))
        assertEquals(id, ShareUrl.parseMapSocialPath("/share/map/$id"))
        assertNull(ShareUrl.parseTrackSocialPath("/share/track/not-a-uuid/"))
    }

    @Test
    fun absolute_joinsOriginOrPassesThroughHttp() {
        assertEquals(
            "https://example.test/share/track/x/",
            ShareUrl.absolute("https://example.test/", "/share/track/x/"),
        )
        assertEquals(
            "https://other.test/a",
            ShareUrl.absolute("https://example.test", "https://other.test/a"),
        )
        assertEquals(
            "https://example.test/#/extensions/live-track/share?id=x",
            ShareUrl.absolute("https://example.test", "/#/extensions/live-track/share?id=x"),
        )
    }
}
