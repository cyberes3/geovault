package com.geovault.common.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIdTest {
    @Test
    fun parse_acceptsUuid4() {
        val id = ShareId.parse("550E8400-E29B-41D4-A716-446655440000")
        assertEquals("550e8400-e29b-41d4-a716-446655440000", id?.value)
    }

    @Test
    fun parse_rejectsNonUuid4() {
        assertNull(ShareId.parse("550e8400-e29b-11d4-a716-446655440000"))
        assertNull(ShareId.parse("550e8400-e29b-41d4-1716-446655440000"))
        assertNull(ShareId.parse("not-a-uuid"))
        assertNull(ShareId.parse(null))
        assertNull(ShareId.parse("  "))
    }
}
