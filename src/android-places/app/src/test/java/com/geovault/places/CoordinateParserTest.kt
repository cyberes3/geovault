package com.geovault.places

import org.junit.Test

/**
 * Run on JVM to verify CoordinateParser with the example formats.
 * Run: ./gradlew :app:testDebugUnitTest --tests "com.geovault.places.CoordinateParserTest"
 */
class CoordinateParserTest {

    private val examples = listOf(
        // Full DMS
        "45° 46' 52\" N 108° 30' 14\" W",
        "39°06'50.1\"N 104°52'30.6\"W",
        // DDM (decimal minutes)
        "45° 46.8666' N 108° 30.2333' W",
        // Decimal degrees with direction
        "45.7811111° N 108.5038888° W",
        "45.7811111°N108.5038888°W",
        "-45.78° S 108.5° E",
        // European decimal comma (direction-style)
        "45°46,8666'   N   108°30,2333'   W",
        "45°46,8666' N   108°30,2333' W",
        // Comma-separated decimal
        "39.898864,-105.347922",
        "39.898864, -105.347922",
        "39 53.932,-105 20.875",
        "39 53.932, -105 20.875",
        "39 53 55.91,-105 20 52.52",
        "39 53 55.91, -105 20 52.52",
    )

    @Test
    fun parseExampleFormats() {
        examples.forEachIndexed { i, input ->
            println("--- Example ${i + 1}: ${input.take(40)}...")
            val result = CoordinateParser.parse(input)
            if (result != null) {
                println("  OK: lat=${result.first}, lon=${result.second}")
            } else {
                println("  FAIL: parse() returned null")
                val looksLike = CoordinateParser.looksLikeCoordinates(input)
                println("  looksLikeCoordinates=$looksLike")
            }
        }
    }
}
