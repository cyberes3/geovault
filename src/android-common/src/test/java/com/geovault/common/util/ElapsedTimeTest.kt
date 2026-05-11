package com.geovault.common.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTimeTest {
    @Test
    fun `negative elapsed clamps to Now`() {
        assertEquals(ElapsedTime.Now, ElapsedTime.from(-5_000L))
    }

    @Test
    fun `zero elapsed is Now`() {
        assertEquals(ElapsedTime.Now, ElapsedTime.from(0L))
    }

    @Test
    fun `just under default now threshold is still Now`() {
        assertEquals(ElapsedTime.Now, ElapsedTime.from(9_999L))
    }

    @Test
    fun `at default now threshold becomes Seconds`() {
        assertEquals(ElapsedTime.Seconds(10), ElapsedTime.from(10_000L))
    }

    @Test
    fun `just under one minute is Seconds 59`() {
        assertEquals(ElapsedTime.Seconds(59), ElapsedTime.from(59_999L))
    }

    @Test
    fun `at one minute boundary is Minutes 1`() {
        assertEquals(ElapsedTime.Minutes(1), ElapsedTime.from(60_000L))
    }

    @Test
    fun `just under one hour is Minutes 59`() {
        assertEquals(ElapsedTime.Minutes(59), ElapsedTime.from(3_599_999L))
    }

    @Test
    fun `at one hour boundary is Hours 1`() {
        assertEquals(ElapsedTime.Hours(1), ElapsedTime.from(3_600_000L))
    }

    @Test
    fun `just under one day is Hours 23`() {
        assertEquals(ElapsedTime.Hours(23), ElapsedTime.from(86_399_999L))
    }

    @Test
    fun `at one day boundary is Days 1`() {
        assertEquals(ElapsedTime.Days(1), ElapsedTime.from(86_400_000L))
    }

    @Test
    fun `large multi-day elapsed produces correct Days value`() {
        assertEquals(ElapsedTime.Days(7), ElapsedTime.from(7L * 86_400_000L))
    }

    @Test
    fun `custom now threshold of zero classifies even tiny elapsed as Seconds`() {
        assertEquals(ElapsedTime.Seconds(0), ElapsedTime.from(500L, nowThresholdMs = 0L))
    }

    @Test
    fun `custom now threshold extends Now band`() {
        assertEquals(ElapsedTime.Now, ElapsedTime.from(29_999L, nowThresholdMs = 30_000L))
        assertEquals(ElapsedTime.Seconds(30), ElapsedTime.from(30_000L, nowThresholdMs = 30_000L))
    }
}
