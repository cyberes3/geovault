package com.geovault.common.net

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ListEnvelopeTest {
    @Test
    fun parse_readsItemsAndPageFields() {
        val envelope = ListEnvelope.parse(
            """{"items":[{"id":"a"}],"page":2,"page_size":10,"total_items":21,"total_pages":3}"""
        )
        requireNotNull(envelope)
        assertEquals(1, envelope.items.size)
        assertEquals("a", envelope.items[0].optString("id"))
        assertEquals(2, envelope.page)
        assertEquals(10, envelope.pageSize)
        assertEquals(21, envelope.totalItems)
        assertEquals(3, envelope.totalPages)
    }

    @Test
    fun parse_rejectsBareArray() {
        assertNull(ListEnvelope.parse("""[{"id":"a"}]"""))
        assertNull(ListEnvelope.parse("""{"shares":[]}"""))
    }

    @Test
    fun gson_readsSnakeCasePageFields() {
        val parsed: ListEnvelope<String> = Gson().fromJson(
            """{"items":["a"],"page":2,"page_size":10,"total_items":21,"total_pages":3}""",
            object : TypeToken<ListEnvelope<String>>() {}.type,
        )
        assertEquals(listOf("a"), parsed.items)
        assertEquals(2, parsed.page)
        assertEquals(10, parsed.pageSize)
        assertEquals(21, parsed.totalItems)
        assertEquals(3, parsed.totalPages)
    }
}
