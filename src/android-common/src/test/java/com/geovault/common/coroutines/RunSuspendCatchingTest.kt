package com.geovault.common.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSuspendCatchingTest {

    @Test
    fun returnsFailureForOrdinaryExceptions() = runBlocking {
        val result = runSuspendCatching<Int> { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun rethrowsCancellation() = runBlocking {
        var thrown = false
        try {
            runSuspendCatching<Unit> { throw CancellationException("cancelled") }
        } catch (e: CancellationException) {
            thrown = true
            assertEquals("cancelled", e.message)
        }
        assertTrue(thrown)
    }
}
