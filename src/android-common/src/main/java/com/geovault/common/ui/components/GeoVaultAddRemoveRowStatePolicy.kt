package com.geovault.common.ui.components

/**
 * Input flags for [GeoVaultAddRemoveRowStatePolicy.resolve]. Call sites compose domain state
 * (pending mutations, membership, eligibility) into these booleans; the policy maps them to a
 * single [GeoVaultAddRemoveRowActionState] for [GeoVaultAddRemoveRowCard].
 *
 * Precedence when multiple flags are true is defined solely by [GeoVaultAddRemoveRowStatePolicy.resolve]
 * (pending add beats pending remove beats added beats disabled beats idle).
 */
data class GeoVaultAddRemoveRowFlags(
    val isPendingAdd: Boolean = false,
    val isPendingRemove: Boolean = false,
    val isAdded: Boolean = false,
    val isDisabled: Boolean = false,
) {
    init {
        check(
            !(isPendingAdd && isPendingRemove),
        ) {
            "GeoVaultAddRemoveRowFlags: isPendingAdd and isPendingRemove cannot both be true"
        }
    }
}

/**
 * Maps add/remove row semantics to [GeoVaultAddRemoveRowActionState] so list screens stay consistent.
 */
object GeoVaultAddRemoveRowStatePolicy {
    /**
     * Order: **ADDING** → **REMOVING** → **ADDED_DELETE** → **DISABLED** → **IDLE**.
     */
    fun resolve(flags: GeoVaultAddRemoveRowFlags): GeoVaultAddRemoveRowActionState {
        if (flags.isPendingAdd) return GeoVaultAddRemoveRowActionState.ADDING
        if (flags.isPendingRemove) return GeoVaultAddRemoveRowActionState.REMOVING
        if (flags.isAdded) return GeoVaultAddRemoveRowActionState.ADDED_DELETE
        if (flags.isDisabled) return GeoVaultAddRemoveRowActionState.DISABLED
        return GeoVaultAddRemoveRowActionState.IDLE
    }
}
