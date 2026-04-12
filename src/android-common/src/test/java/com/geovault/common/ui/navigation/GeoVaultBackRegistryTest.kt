package com.geovault.common.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultBackRegistryTest {
    @Test
    fun dispatchesHighestPriorityNavigatorFirst() {
        val registry = GeoVaultBackRegistry()
        val callOrder = mutableListOf<String>()

        registry.register(
            navigator = FakeNavigator(
                canGoBack = true,
                onBack = {
                    callOrder.add("low")
                    true
                },
            ),
            priority = 1,
        )
        registry.register(
            navigator = FakeNavigator(
                canGoBack = true,
                onBack = {
                    callOrder.add("high")
                    true
                },
            ),
            priority = 10,
        )

        val consumed = registry.dispatchBack()

        assertTrue(consumed)
        assertEquals(listOf("high"), callOrder)
    }

    @Test
    fun dispatchesMostRecentNavigatorWhenPrioritiesAreEqual() {
        val registry = GeoVaultBackRegistry()
        val callOrder = mutableListOf<String>()

        registry.register(
            navigator = FakeNavigator(
                canGoBack = true,
                onBack = {
                    callOrder.add("first")
                    true
                },
            ),
            priority = 5,
        )
        registry.register(
            navigator = FakeNavigator(
                canGoBack = true,
                onBack = {
                    callOrder.add("second")
                    true
                },
            ),
            priority = 5,
        )

        val consumed = registry.dispatchBack()

        assertTrue(consumed)
        assertEquals(listOf("second"), callOrder)
    }

    @Test
    fun skipsNavigatorsThatCannotGoBack() {
        val registry = GeoVaultBackRegistry()
        var handled = false

        registry.register(
            navigator = FakeNavigator(
                canGoBack = false,
                onBack = { true },
            ),
            priority = 10,
        )
        registry.register(
            navigator = FakeNavigator(
                canGoBack = true,
                onBack = {
                    handled = true
                    true
                },
            ),
            priority = 1,
        )

        val consumed = registry.dispatchBack()

        assertTrue(consumed)
        assertTrue(handled)
    }

    @Test
    fun returnsFalseWhenNoNavigatorConsumesBack() {
        val registry = GeoVaultBackRegistry()
        registry.register(
            navigator = FakeNavigator(
                canGoBack = false,
                onBack = { false },
            ),
            priority = 1,
        )

        val consumed = registry.dispatchBack()

        assertFalse(consumed)
    }

    @Test
    fun unregisterUpdatesRegistrationState() {
        val registry = GeoVaultBackRegistry()
        val registration = registry.register(
            navigator = FakeNavigator(canGoBack = true, onBack = { true }),
            priority = 1,
        )

        assertTrue(registry.hasRegisteredNavigators())
        assertEquals(1, registry.registrationCountState.value)

        registration.unregister()

        assertFalse(registry.hasRegisteredNavigators())
        assertEquals(0, registry.registrationCountState.value)
    }

    private class FakeNavigator(
        private val canGoBack: Boolean,
        private val onBack: () -> Boolean,
    ) : GeoVaultBackNavigator {
        override fun canGoBack(): Boolean = canGoBack

        override fun goBack(): Boolean = onBack()
    }
}
