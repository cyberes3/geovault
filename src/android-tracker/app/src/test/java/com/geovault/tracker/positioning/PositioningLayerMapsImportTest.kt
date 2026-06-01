package com.geovault.tracker.positioning

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PositioningLayerMapsImportTest {

    @Test
    fun positioningAndTrackingPackages_doNotImportCommonMaps() {
        val moduleRoot = File("src/main/java/com/geovault/tracker")
        val forbidden = Regex("""import\s+com\.geovault\.common\.maps\.""")
        val dirs = listOf(
            moduleRoot.resolve("positioning"),
            moduleRoot.resolve("tracking"),
        )
        val violations = mutableListOf<String>()
        for (dir in dirs) {
            dir.walkTopDown()
                .filter { it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (forbidden.containsMatchIn(line)) {
                            violations += "${file.relativeTo(moduleRoot)}:${index + 1}: $line"
                        }
                    }
                }
        }
        assertTrue(
            "Service-layer packages must not import common-maps:\n${violations.joinToString("\n")}",
            violations.isEmpty(),
        )
    }
}
