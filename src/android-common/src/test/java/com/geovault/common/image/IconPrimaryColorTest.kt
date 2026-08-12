package com.geovault.common.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPrimaryColorTest {

    @Test
    fun solidRed_isQuantizedDominantColor() {
        val pixels = IntArray(16) { argb(255, 255, 0, 0) }
        assertEquals("#f00000", IconPrimaryColor.fromArgbPixels(pixels))
    }

    @Test
    fun transparentWhiteAndBlack_areIgnored() {
        val pixels = intArrayOf(
            argb(0, 255, 0, 0),
            argb(127, 0, 255, 0),
            argb(255, 250, 250, 250),
            argb(255, 10, 10, 10),
            argb(255, 0, 0, 255),
            argb(255, 0, 0, 255),
        )
        assertEquals("#0000f0", IconPrimaryColor.fromArgbPixels(pixels))
    }

    @Test
    fun emptyOrFullyIgnored_returnsNull() {
        assertNull(IconPrimaryColor.fromArgbPixels(intArrayOf()))
        assertNull(
            IconPrimaryColor.fromArgbPixels(
                intArrayOf(
                    argb(0, 255, 0, 0),
                    argb(255, 255, 255, 255),
                    argb(255, 0, 0, 0),
                ),
            ),
        )
    }

    @Test
    fun lightGray_isDarkenedForMapVisibility() {
        val pixels = IntArray(8) { argb(255, 200, 200, 200) }
        assertEquals("#999999", IconPrimaryColor.fromArgbPixels(pixels))
    }

    @Test
    fun majorityBucket_winsOverMinority() {
        val pixels = IntArray(10) { index ->
            if (index < 7) argb(255, 0, 128, 0) else argb(255, 128, 0, 0)
        }
        assertEquals("#008000", IconPrimaryColor.fromArgbPixels(pixels))
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
