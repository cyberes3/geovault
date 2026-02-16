package com.geovault.places

import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.floor

/**
 * Port of the JS 'coordinate-parser' library.
 * Parses coordinate strings into Decimal Degrees (DD).
 */
object CoordinateParser {

    /**
     * Parses a coordinate string and returns a Pair of (Latitude, Longitude) in decimal degrees,
     * or null if parsing fails.
     */
    fun parse(input: String): Pair<Double, Double>? {
        try {
            // Pre-process: Replace commas between digits with dots (European decimal style)
            // e.g., "45,5" -> "45.5", but "45, 5" -> "45, 5" (separator)
            val preprocessed = input.replace(Regex("(\\d),(\\d)"), "$1.$2")

            val coordinates = Coordinates(preprocessed)
            return Pair(coordinates.latitude, coordinates.longitude)
        } catch (e: Exception) {
            // e.printStackTrace() // parsing failed
            return null
        }
    }

    /**
     * Checks if the input looks like coordinates (basic validation).
     * Used for fallback or UI hints.
     */
    fun looksLikeCoordinates(input: String): Boolean {
        return try {
            val validator = Validator()
            validator.validate(input)
            true
        } catch (e: Exception) {
            false
        }
    }

    private class Coordinates(val coordinateString: String) {
        var latitude: Double = 0.0
        var longitude: Double = 0.0
        private lateinit var latitudeNumbers: List<Double>
        private lateinit var longitudeNumbers: List<Double>

        init {
            validate()
            parse()
        }

        private fun validate() {
            Validator().validate(coordinateString)
        }

        private fun parse() {
            groupCoordinateNumbers()
            latitude = extractLatitude()
            longitude = extractLongitude()
        }

        private fun groupCoordinateNumbers() {
            val numbers = extractCoordinateNumbers(coordinateString)
            val countPerCoord = numbers.size / 2
            latitudeNumbers = numbers.subList(0, countPerCoord)
            longitudeNumbers = numbers.subList(numbers.size - countPerCoord, numbers.size)
        }

        private fun extractCoordinateNumbers(str: String): List<Double> {
            val pattern = Pattern.compile("-?\\d+(\\.\\d+)?")
            val matcher = pattern.matcher(str)
            val numbers = mutableListOf<Double>()
            while (matcher.find()) {
                numbers.add(matcher.group().toDouble())
            }
            return numbers
        }

        private fun extractLatitude(): Double {
            var lat = coordinateNumbersToDecimal(latitudeNumbers)
            if (latitudeIsNegative()) {
                lat *= -1
            }
            return lat
        }

        private fun extractLongitude(): Double {
            var lon = coordinateNumbersToDecimal(longitudeNumbers)
            if (longitudeIsNegative()) {
                lon *= -1
            }
            return lon
        }

        private fun coordinateNumbersToDecimal(numbers: List<Double>): Double {
            val coord = CoordinateNumber(numbers)
            coord.detectSpecialFormats()
            return coord.toDecimal()
        }

        private fun latitudeIsNegative(): Boolean {
            return coordinateString.contains(Regex("s", RegexOption.IGNORE_CASE))
        }

        private fun longitudeIsNegative(): Boolean {
            return coordinateString.contains(Regex("w", RegexOption.IGNORE_CASE))
        }
    }

    private class CoordinateNumber(rawNumbers: List<Double>) {
        var sign: Int = 1
        var degrees: Double = 0.0
        var minutes: Double = 0.0
        var seconds: Double = 0.0
        var milliseconds: Double = 0.0

        init {
            val normalized = normalizeCoordinateNumbers(rawNumbers)
            // In JS: sign = normalizedSignOf(coordinateNumbers[0])
            // But we actually took absolute values for d/m/s in JS.
            // Let's replicate JS logic closely.
            
            // Check sign of the first number *before* abs
            val firstNum = rawNumbers.firstOrNull() ?: 0.0
            sign = if (firstNum >= 0) 1 else -1

            // In JS: [degrees, minutes, seconds, milliseconds] = coordinateNumbers.map(Math.abs)
            // normalizedNumbers are already processed from constructor input, but let's just map raw to abs first
            // Wait, JS: coordinateNumbers = normalize(coordinateNumbers) -> which ensures floats
            // Then: sign = signOf(normalized[0])
            // Then: [d, m, s, ms] = normalized.map(Math.abs)
            
            if (normalized.isNotEmpty()) degrees = abs(normalized[0])
            if (normalized.size > 1) minutes = abs(normalized[1])
            if (normalized.size > 2) seconds = abs(normalized[2])
            if (normalized.size > 3) milliseconds = abs(normalized[3])
        }
        
        private fun normalizeCoordinateNumbers(numbers: List<Double>): List<Double> {
            // In JS this just ensures they are floats. We already have Doubles.
            // But we pad with 0s to length 4? 
            // JS: normalizedNumbers = [0, 0, 0, 0]; loop and fill.
            // So yes, effectively padding.
            val padded = MutableList(4) { 0.0 }
            for (i in numbers.indices) {
                if (i < 4) padded[i] = numbers[i]
            }
            return padded
        }

        fun detectSpecialFormats() {
            if (degreesCanBeSpecial()) {
                if (degreesCanBeMilliseconds()) {
                    degreesAsMilliseconds()
                } else if (degreesCanBeDegreesMinutesAndSeconds()) {
                    degreesAsDegreesMinutesAndSeconds()
                } else if (degreesCanBeDegreesAndMinutes()) {
                    degreesAsDegreesAndMinutes()
                }
            }
        }

        private fun degreesCanBeSpecial(): Boolean {
            return minutes == 0.0 && seconds == 0.0
        }

        // JS: degrees > 909090
        private fun degreesCanBeMilliseconds(): Boolean {
            return degrees > 909090
        }

        private fun degreesAsMilliseconds() {
            milliseconds = degrees
            degrees = 0.0
        }

        // JS: degrees > 9090
        private fun degreesCanBeDegreesMinutesAndSeconds(): Boolean {
            return degrees > 9090
        }

        private fun degreesAsDegreesMinutesAndSeconds() {
            // JS: newDegrees = Math.floor(degrees / 10000)
            val newDegrees = floor(degrees / 10000)
            // JS: minutes = Math.floor((degrees - newDegrees * 10000) / 100)
            minutes = floor((degrees - newDegrees * 10000) / 100)
            // JS: seconds = Math.floor(degrees - newDegrees * 10000 - minutes * 100)
            seconds = floor(degrees - newDegrees * 10000 - minutes * 100)
            degrees = newDegrees
        }

        // JS: degrees > 360
        private fun degreesCanBeDegreesAndMinutes(): Boolean {
            return degrees > 360
        }

        private fun degreesAsDegreesAndMinutes() {
            // JS: newDegrees = Math.floor(degrees / 100)
            val newDegrees = floor(degrees / 100)
            // JS: minutes = degrees - newDegrees * 100
            minutes = degrees - newDegrees * 100
            degrees = newDegrees
        }

        fun toDecimal(): Double {
            // JS: sign * (degrees + minutes / 60 + seconds / 3600 + milliseconds / 3600000)
            return sign * (degrees + minutes / 60.0 + seconds / 3600.0 + milliseconds / 3600000.0)
        }
    }

    private class Validator {
        fun validate(coordinates: String) {
            checkContainsNoLetters(coordinates)
            checkValidOrientation(coordinates)
            checkNumbers(coordinates)
        }

        private fun checkContainsNoLetters(coordinates: String) {
            // JS: /(?![neswd])[a-z]/i.test(coordinates)
            // Negative lookahead: matches any letter that is NOT n, e, s, w, d
            // Wait, 'd' is allowed in JS regex? "(?![neswd])[a-z]"
            // Yes, maybe for 'deg'?
            val pattern = Pattern.compile("(?![neswd])[a-z]", Pattern.CASE_INSENSITIVE)
            if (pattern.matcher(coordinates).find()) {
                throw IllegalArgumentException("Coordinate contains invalid alphanumeric characters.")
            }
        }

        private fun checkValidOrientation(coordinates: String) {
            // JS: /^[^nsew]*[ns]?[^nsew]*[ew]?[^nsew]*$/i
            val pattern = Pattern.compile("^[^nsew]*[ns]?[^nsew]*[ew]?[^nsew]*$", Pattern.CASE_INSENSITIVE)
            if (!pattern.matcher(coordinates).matches()) {
                throw IllegalArgumentException("Invalid cardinal direction.")
            }
        }

        private fun checkNumbers(coordinates: String) {
            val pattern = Pattern.compile("-?\\d+(\\.\\d+)?")
            val matcher = pattern.matcher(coordinates)
            var count = 0
            while (matcher.find()) {
                count++
            }
            
            if (count == 0) throw IllegalArgumentException("Could not find any coordinate number")
            if (count % 2 != 0) throw IllegalArgumentException("Uneven count of latitude/longitude numbers")
            if (count > 6) throw IllegalArgumentException("Too many coordinate numbers")
        }
    }
}
