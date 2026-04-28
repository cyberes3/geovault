package com.geovault.common.maps.core

import com.geovault.common.GeovaultAuthManager
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.storage.Resource
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MapResourceUrlTransformTest {

    private val appContext get() = RuntimeEnvironment.getApplication()
    private val transform by lazy { MapResourceUrlTransform(appContext) }

    @Before
    fun resetServerUrl() {
        GeovaultAuthManager.setServerUrl(appContext, "")
    }

    @Test
    fun validHttpsUrl_passesThrough() {
        val url = "https://tile.openstreetmap.org/0/0/0.png"
        assertEquals(url, transform.onURL(Resource.TILE, url))
    }

    @Test
    fun emptyUrl_returnsRejectedSentinel() {
        val result = transform.onURL(Resource.GLYPHS, "")
        val parsed = result.toHttpUrl()
        assertTrue(MapResourceUrlTransform.isRejectedSentinel(parsed))
        assertTrue("sentinel path encodes the kind", result.endsWith("/glyphs"))
    }

    @Test
    fun whitespaceOnlyUrl_returnsRejectedSentinel() {
        val result = transform.onURL(Resource.SOURCE, "   ")
        assertTrue(MapResourceUrlTransform.isRejectedSentinel(result.toHttpUrl()))
    }

    @Test
    fun unparseableUrl_returnsRejectedSentinel() {
        val result = transform.onURL(Resource.SPRITE_JSON, "not a url at all")
        assertTrue(MapResourceUrlTransform.isRejectedSentinel(result.toHttpUrl()))
    }

    @Test
    fun serverRelativeUrl_isRewritten() {
        GeovaultAuthManager.setServerUrl(appContext, "https://geovault.example.com/")
        val rewritten = transform.onURL(Resource.SOURCE, "/api/tiles/style/streets")
        assertEquals("https://geovault.example.com/api/tiles/style/streets", rewritten)
    }

    @Test
    fun serverRelativeUrl_withoutServerUrl_returnsSentinel() {
        // No server URL configured -> rewrite leaves the leading slash, which fails HttpUrl.parse.
        val result = transform.onURL(Resource.STYLE, "/api/tiles/style/streets")
        assertTrue(MapResourceUrlTransform.isRejectedSentinel(result.toHttpUrl()))
    }

    @Test
    fun unknownKindAboveJavaIntDef_resolvesToImage() {
        // Native Resource::Kind has Image=7 but the Java IntDef stops at 6. Make sure
        // we don't blow up on it.
        val result = transform.onURL(7, "")
        assertTrue(result.endsWith("/image"))
    }

    @Test
    fun isRejectedSentinel_recognizesOnlySentinelHost() {
        assertTrue(MapResourceUrlTransform.isRejectedSentinel("https://${MapResourceUrlTransform.REJECTED_HOST}/x".toHttpUrl()))
        assertTrue(!MapResourceUrlTransform.isRejectedSentinel("https://maps.example.com/x".toHttpUrl()))
    }

    @Test
    fun kindName_coversAllResourceKinds() {
        assertEquals("unknown", MapResourceUrlTransform.kindName(Resource.UNKNOWN))
        assertEquals("style", MapResourceUrlTransform.kindName(Resource.STYLE))
        assertEquals("source", MapResourceUrlTransform.kindName(Resource.SOURCE))
        assertEquals("tile", MapResourceUrlTransform.kindName(Resource.TILE))
        assertEquals("glyphs", MapResourceUrlTransform.kindName(Resource.GLYPHS))
        assertEquals("spriteImage", MapResourceUrlTransform.kindName(Resource.SPRITE_IMAGE))
        assertEquals("spriteJson", MapResourceUrlTransform.kindName(Resource.SPRITE_JSON))
        assertEquals("image", MapResourceUrlTransform.kindName(7))
    }
}
