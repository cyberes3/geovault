package com.geovault.common.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Locks `res/values/colors.xml` and `res/values-night/colors.xml` to the Kotlin
 * [GeoVaultColorTokens] palette. XML is a deliberate **subset** of Kotlin: every name
 * shipped in XML must resolve to a Kotlin token whose hex form matches exactly. Adding a
 * new XML name without updating the contract below — or changing a hex value in either
 * surface without updating the other — fails CI.
 *
 * The test parses XML directly with `DocumentBuilderFactory` so it runs as a plain JVM
 * unit test (no Robolectric, no Android resource harness).
 */
class GeoVaultColorTokensXmlSyncTest {

    /**
     * Mapping from each `gv_common_*` name we ship in `values/colors.xml` to the Kotlin
     * token whose hex projection must match. The map is the canonical contract: forgetting
     * to wire a new XML name in here trips ["every light XML name maps to the matching
     * Kotlin token"][everyLightXmlNameMaps].
     */
    private val lightContract: Map<String, () -> String> = mapOf(
        "gv_common_main_blue" to { GeoVaultColorTokens.Hex.MainBlue },
        "gv_common_main_yellow" to { GeoVaultColorTokens.Hex.MainYellow },
        "gv_common_surface" to { GeoVaultColorTokens.Hex.Surface },
        "gv_common_border_light" to { GeoVaultColorTokens.Hex.BorderLight },
        "gv_common_text_primary" to { GeoVaultColorTokens.Hex.TextPrimary },
        "gv_common_text_secondary" to { GeoVaultColorTokens.Hex.TextSecondary },
        "gv_common_map_heading_north" to { GeoVaultColorTokens.Hex.MapHeadingNorth },
    )

    /**
     * Mapping for `values-night/colors.xml`. Only names that need a day/night flip live
     * here — brand colors (`main_blue`, `main_yellow`) intentionally stay branded across
     * themes and are absent from this map.
     */
    private val nightContract: Map<String, () -> String> = mapOf(
        "gv_common_surface" to { GeoVaultColorTokens.Dark.Surface.toHexRgb() },
        "gv_common_border_light" to { GeoVaultColorTokens.Dark.BorderLight.toHexRgb() },
        "gv_common_text_primary" to { GeoVaultColorTokens.Dark.TextPrimary.toHexRgb() },
        "gv_common_text_secondary" to { GeoVaultColorTokens.Dark.TextSecondary.toHexRgb() },
    )

    @Test
    fun everyLightXmlNameMaps() {
        val xml = parseColorsXml("src/main/res/values/colors.xml")
        for ((name, value) in xml) {
            val expected = lightContract[name]
                ?: fail(
                    "XML color '$name' has no Kotlin mapping in the contract. " +
                        "Either wire it into GeoVaultColorTokens (and the lightContract map) " +
                        "or delete it from values/colors.xml.",
                )
            @Suppress("UNCHECKED_CAST")
            val resolved = (expected as () -> String).invoke()
            assertEquals(
                "drift on $name: XML=$value Kotlin=$resolved",
                resolved.uppercase(),
                value.uppercase(),
            )
        }
    }

    @Test
    fun everyNightXmlNameMaps() {
        val xml = parseColorsXml("src/main/res/values-night/colors.xml")
        for ((name, value) in xml) {
            val expected = nightContract[name]
                ?: fail(
                    "values-night color '$name' has no Kotlin Dark mapping. " +
                        "Either wire it into GeoVaultColorTokens.Dark (and the nightContract map) " +
                        "or delete it from values-night/colors.xml.",
                )
            @Suppress("UNCHECKED_CAST")
            val resolved = (expected as () -> String).invoke()
            assertEquals(
                "dark drift on $name: XML=$value Kotlin=$resolved",
                resolved.uppercase(),
                value.uppercase(),
            )
        }
    }

    @Test
    fun xmlSurfaceIsSmall() {
        val xml = parseColorsXml("src/main/res/values/colors.xml")
        // Hard cap to catch accidental re-introduction of the old 50+ name palette.
        assertTrue(
            "values/colors.xml has grown past its minimal surface (${xml.size} names): ${xml.keys}",
            xml.size <= 10,
        )
    }

    private fun parseColorsXml(relativePath: String): Map<String, String> {
        val file = File(relativePath)
        require(file.exists()) { "Expected XML at $relativePath (cwd=${File("").absolutePath})" }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("color")
        val out = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            val value = node.textContent.trim()
            out[name] = normalize(value)
        }
        return out
    }

    /**
     * Normalize XML hex literals to the same `#RRGGBB` / `#AARRGGBB` form that
     * [Color.toHexRgb] / [Color.toHexArgb] emit, so the assertion can compare strings
     * verbatim. Three-digit shorthand (`#FFF`) and eight-digit alpha-prefixed
     * (`#AARRGGBB`) forms are accepted; eight-digit values whose alpha is `FF` collapse
     * to six digits (matching the [Color.toHexRgb] projection).
     */
    private fun normalize(raw: String): String {
        val cleaned = raw.removePrefix("#").uppercase()
        return when (cleaned.length) {
            3 -> "#" + cleaned.map { "$it$it" }.joinToString("")
            6 -> "#$cleaned"
            8 -> if (cleaned.startsWith("FF")) "#" + cleaned.substring(2) else "#$cleaned"
            else -> "#$cleaned"
        }
    }
}
