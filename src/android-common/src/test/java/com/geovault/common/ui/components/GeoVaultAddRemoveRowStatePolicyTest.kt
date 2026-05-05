package com.geovault.common.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultAddRemoveRowStatePolicyTest {

    @Test
    fun pendingAdd_wins_over_everything_else() {
        assertEquals(
            GeoVaultAddRemoveRowActionState.ADDING,
            GeoVaultAddRemoveRowStatePolicy.resolve(
                GeoVaultAddRemoveRowFlags(
                    isPendingAdd = true,
                    isAdded = true,
                    isDisabled = true,
                ),
            ),
        )
    }

    @Test
    fun pendingRemove_wins_over_added_disabled_idle() {
        assertEquals(
            GeoVaultAddRemoveRowActionState.REMOVING,
            GeoVaultAddRemoveRowStatePolicy.resolve(
                GeoVaultAddRemoveRowFlags(
                    isPendingRemove = true,
                    isAdded = true,
                    isDisabled = true,
                ),
            ),
        )
    }

    @Test
    fun added_wins_over_disabled_and_idle() {
        assertEquals(
            GeoVaultAddRemoveRowActionState.ADDED_DELETE,
            GeoVaultAddRemoveRowStatePolicy.resolve(
                GeoVaultAddRemoveRowFlags(isAdded = true, isDisabled = true),
            ),
        )
    }

    @Test
    fun disabled_when_not_added() {
        assertEquals(
            GeoVaultAddRemoveRowActionState.DISABLED,
            GeoVaultAddRemoveRowStatePolicy.resolve(
                GeoVaultAddRemoveRowFlags(isDisabled = true),
            ),
        )
    }

    @Test
    fun idle_is_default() {
        assertEquals(
            GeoVaultAddRemoveRowActionState.IDLE,
            GeoVaultAddRemoveRowStatePolicy.resolve(GeoVaultAddRemoveRowFlags()),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun rejects_pendingAdd_and_pendingRemove_together() {
        GeoVaultAddRemoveRowFlags(isPendingAdd = true, isPendingRemove = true)
    }
}
