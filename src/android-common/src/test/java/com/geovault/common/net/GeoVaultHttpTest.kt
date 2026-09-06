package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class GeoVaultHttpTest {
    interface DummyApi

    @Test
    fun cachedApiHolder_clearsAndReplaces() {
        val cache = GeoVaultHttp.CachedApiHolder<DummyApi>()
        val first = object : DummyApi {}
        cache.api = first
        cache.baseUrl = "https://a/"
        cache.clear()
        assertEquals(null, cache.api)
        assertEquals(null, cache.baseUrl)
        val second = object : DummyApi {}
        cache.api = second
        assertSame(second, cache.api)
        assertNotSame(first, cache.api)
    }

    @Test
    fun probeClient_isReusableWithoutBind() {
        val first = GeoVaultHttp.probeClient()
        val second = GeoVaultHttp.probeClient()
        assertSame(first, second)
    }
}
