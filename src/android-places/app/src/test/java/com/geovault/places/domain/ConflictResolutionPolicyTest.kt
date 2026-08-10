package com.geovault.places.domain

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.Properties
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolutionPolicyTest {
    private val policy = ConflictResolutionPolicy()

    @Test
    fun emptyStringDescriptionMatchesNull() {
        val original = place(description = "")
        val server = place(description = null)
        assertFalse(policy.hasServerChanged(original, server))
    }

    @Test
    fun point2dIgnoresAltitudeDifference() {
        val original = Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(name = "A"),
        )
        val server = Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0, 100.0)),
            properties = Properties(name = "A"),
        )
        assertFalse(policy.hasServerChanged(original, server))
    }

    @Test
    fun nameChangeIsConflict() {
        val original = place(name = "A")
        val server = place(name = "B")
        assertTrue(policy.hasServerChanged(original, server))
    }

    private fun place(
        name: String = "Place",
        description: String? = null,
    ): Feature {
        return Feature(
            geometry = Geometry(coordinates = listOf(2.0, 1.0)),
            properties = Properties(name = name, description = description),
        )
    }
}
