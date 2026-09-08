package com.geovault.places.domain

import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.sampleContent
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolutionPolicyTest {
    private val policy = ConflictResolutionPolicy()

    @Test
    fun emptyStringDescriptionMatchesNull() {
        assertFalse(policy.hasServerChanged(sampleContent(description = ""), sampleContent(description = "")))
        assertFalse(policy.hasServerChanged(sampleContent(address = ""), sampleContent(address = null)))
    }

    @Test
    fun nameChangeIsConflict() {
        assertTrue(policy.hasServerChanged(sampleContent(name = "A"), sampleContent(name = "B")))
    }

    @Test
    fun addressChangeIsConflict() {
        assertTrue(
            policy.hasServerChanged(
                sampleContent(address = "One"),
                sampleContent(address = "Two"),
            ),
        )
    }

    @Test
    fun buildConflictedCopyUsesUniqueSuffixNotStackedLabel() {
        val local = samplePlace(
            key = PlaceKey.local("abcdef-rest"),
            serverId = null,
            name = "Camp",
            pending = PendingChange.Create,
        )

        val copy = policy.buildConflictedCopy(local)

        assertEquals("Camp (conflict abcdef)", copy.content.name)
        assertNull(copy.serverId)
        assertEquals(PendingChange.Create, copy.pending)
        assertTrue(copy.key.isLocal)
        assertTrue(copy.key != local.key)
    }
}
