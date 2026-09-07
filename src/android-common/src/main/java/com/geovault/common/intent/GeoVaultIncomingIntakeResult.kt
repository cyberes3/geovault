package com.geovault.common.intent

import com.geovault.common.files.GeoVaultFileRef

sealed interface GeoVaultIncomingIntakeResult {
    data object Ignored : GeoVaultIncomingIntakeResult

    data class Processed(
        val accepted: List<GeoVaultFileRef>,
        val rejectedFileNames: List<String>,
    ) : GeoVaultIncomingIntakeResult
}
