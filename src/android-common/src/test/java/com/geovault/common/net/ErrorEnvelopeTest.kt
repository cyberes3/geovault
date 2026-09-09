package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ErrorEnvelopeTest {
    @Test
    fun parse_readsErrorCodeAndDetails() {
        val envelope = ErrorEnvelope.parse("""{"error":"Nope","code":400,"details":{"fields":{"name":"required"}}}""")
        requireNotNull(envelope)
        assertEquals("Nope", envelope.error)
        assertEquals(400, envelope.code)
        assertEquals("required", envelope.details?.optJSONObject("fields")?.optString("name"))
    }

    @Test
    fun parse_rejectsLegacyMessageField() {
        assertNull(ErrorEnvelope.parse("""{"message":"old path","code":400}"""))
        assertNull(ErrorEnvelope.parse("""{"error":"missing code"}"""))
    }
}
