package com.geovault.places

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Run on JVM to verify CoordinateParser with the example formats.
 * Run: ./gradlew :app:testDebugUnitTest --tests "com.geovault.places.CoordinateParserTest"
 */
class CoordinateParserTest {

    private val delta = 0.001

    private val expectationResult = Pair(40.4183318, -74.6411133)
    private val expectationFormats = listOf(
        "40.4183318, -74.6411133",
        "40.4183318° N 74.6411133° W",
        "40° 25´ 5.994\" N 74° 38´ 28.008\" W",
        "40° 25.0999’ , -74° 38.4668’",
        "N40°25’5.994, W74°38’28.008\"",
        "40°25’5.994\"N, 74°38’28.008\"W",
        "40 25 5.994, -74 38 28.008",
        "40.4183318 -74.6411133",
        "40.4183318°,-74.6411133°",
        "40-25.0999N 74-38.4668W",
        "145505994.48, -268708007.88",
        "40.4183318N74.6411133W",
        "4025.0999N7438.4668W",
        "40°25’5.994\"N, 74°38’28.008\"W",
        "402505.994N743828.008W",
        "N 40 25.0999    W 74 38.4668",
        "40:25:6N,74:38:28W",
        "40:25:5.994N 74:38:28.008W",
        "40°25’6\"N 74°38’28\"W",
        "40°25’6\" -74°38’28\"",
        "40d 25’ 6\" N 74d 38’ 28\" W",
        "40.4183318N 74.6411133W",
        "40° 25.0999, -74° 38.4668"
    )

    private val reversedExpectationResult = Pair(-40.4183318, 74.6411133)
    private val reversedExpectationFormats = listOf(
        "-40.4183318, 74.6411133",
        "40.4183318° S 74.6411133° E",
        "40° 25´ 5.994\" S 74° 38´ 28.008\" E",
        "-40° 25.0999’ , 74° 38.4668’",
        "S40°25’5.994, E74°38’28.008\"",
        "40°25’5.994\"S, 74°38’28.008\"E",
        "-40 25 5.994, 74 38 28.008",
        "-40.4183318 74.6411133",
        "-40.4183318°,74.6411133°",
        "40-25.0999S 74-38.4668E",
        "-145505994.48, 268708007.88",
        "40.4183318S74.6411133E",
        "4025.0999S7438.4668E",
        "40°25’5.994\"S, 74°38’28.008\"E",
        "402505.994S743828.008E",
        "S 40 25.0999    E 74 38.4668",
        "40:25:6S,74:38:28E",
        "40:25:5.994S 74:38:28.008E",
        "40°25’6\"S 74°38’28\"E",
        "-40°25’6\" 74°38’28\"",
        "40d 25’ 6\" S 74d 38’ 28\" E",
        "40.4183318S 74.6411133E",
        "40.4183318S 74.6411133",
        "-40° 25.0999, 74° 38.4668"
    )

    private val invalidFormats = listOf(
        "blablabla",
        "5 Fantasy street 12",
        "-40.1X, 74",
        "-40.1 X, 74",
        "-40.1, 74X",
        "-40.1, 74 X",
        "1 2 3 4 5 6 7 8",
        "1 2 3 4 5 6 7",
        "1 2 3 4 5",
        "1 2 3 ",
        "1",
        "40.1° SS 60.1° EE",
        "40.1° E 60.1° S",
        "40.1° W 60.1° N",
        "40.1° W 60.1° W",
        "40.1° N 60.1° N",
        "-40.4183318, 12.345, 74.6411133"
    )

    // Original examples from the user's test file, kept for good measure
    private val originalExamples = listOf(
        "45° 46' 52\" N 108° 30' 14\" W",
        "39°06'50.1\"N 104°52'30.6\"W",
        "45° 46.8666' N 108° 30.2333' W",
        "45.7811111° N 108.5038888° W",
        "45.7811111°N108.5038888°W",
        "-45.78° S 108.5° E",
        "45°46,8666'   N   108°30,2333'   W",
        "45°46,8666' N   108°30,2333' W",
        "39.898864,-105.347922",
        "39.898864, -105.347922",
        "39 53.932,-105 20.875",
        "39 53.932, -105 20.875",
        "39 53 55.91,-105 20 52.52",
        "39 53 55.91, -105 20 52.52"
    )

    @Test
    fun testExpectationFormats() {
        expectationFormats.forEach { input ->
            val result = CoordinateParser.parse(input)
            assertNotNull("Failed to parse: $input", result)
            result?.let {
                assertEquals("Lat mismatch for $input", expectationResult.first, it.first, delta)
                assertEquals("Lon mismatch for $input", expectationResult.second, it.second, delta)
            }
        }
    }

    @Test
    fun testReversedExpectationFormats() {
        reversedExpectationFormats.forEach { input ->
            val result = CoordinateParser.parse(input)
            assertNotNull("Failed to parse: $input", result)
            result?.let {
                assertEquals("Lat mismatch for $input", reversedExpectationResult.first, it.first, delta)
                assertEquals("Lon mismatch for $input", reversedExpectationResult.second, it.second, delta)
            }
        }
    }

    @Test
    fun testInvalidFormats() {
        invalidFormats.forEach { input ->
            try {
                // The JS library throws or validates returns false. 
                // Our parse helper returns null if it fails check valid orientation or other checks.
                val result = CoordinateParser.parse(input)
                // If it parses, we might be surprised, but checking invalidity usually means it throws or returns null
                // The JS library throws error in Validator.
                // Our Kotlin implementation catches exceptions in 'parse' and returns null.
                assertNull("Should fail for: $input", result)
            } catch (e: Exception) {
                // Expected if we didn't catch it inside parse, but parse swallows exceptions
            }
        }
    }

    @Test
    fun testOriginalExamples() {
        originalExamples.forEachIndexed { i, input ->
            val result = CoordinateParser.parse(input)
            assertNotNull("Failed to parse original example ${i + 1}: $input", result)
            // Just verifying it parses to something non-null, as we don't have exact expected values in the original file
        }
    }
    
    @Test
    fun testSamples() {
        // "55° 22' 33.6\" N, 12° 1' 55.2\" E": [(55 + 22 / 60 + 33.6 / 3600), (12 + 1 / 60  + 55.2 / 3600)]
        val input = "55° 22' 33.6\" N, 12° 1' 55.2\" E"
        val expectedLat = 55 + 22 / 60.0 + 33.6 / 3600.0
        val expectedLon = 12 + 1 / 60.0 + 55.2 / 3600.0
        
        val result = CoordinateParser.parse(input)
        assertNotNull(result)
        assertEquals(expectedLat, result!!.first, delta)
        assertEquals(expectedLon, result.second, delta)
    }
}
