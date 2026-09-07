package com.geovault.common.intent

import android.content.Intent
import android.net.Uri
import com.geovault.common.files.GeoVaultFileIngest
import com.geovault.common.files.GeoVaultFileRef

/**
 * Classifies incoming VIEW/SEND payloads (or explicit picker URIs) through [GeoVaultFileIngest]
 * and consumes the intent so rotation does not re-ingest the same share.
 */
class GeoVaultIncomingFileIntake(
    private val fileIngest: GeoVaultFileIngest,
) {
    fun ingest(intent: Intent?): GeoVaultIncomingIntakeResult {
        if (!GeoVaultIncomingFileIntents.isIncomingFileAction(intent)) {
            return GeoVaultIncomingIntakeResult.Ignored
        }
        val uris = GeoVaultIncomingFileIntents.urisFrom(intent)
        GeoVaultIncomingFileIntents.consume(intent)
        return ingest(uris, GeoVaultFileRef.Source.Intent)
    }

    fun ingest(
        uris: List<Uri>,
        source: GeoVaultFileRef.Source,
    ): GeoVaultIncomingIntakeResult {
        val result = fileIngest.ingest(uris, source)
        return GeoVaultIncomingIntakeResult.Processed(
            accepted = result.accepted,
            rejectedFileNames = result.rejectedFileNames,
        )
    }
}
