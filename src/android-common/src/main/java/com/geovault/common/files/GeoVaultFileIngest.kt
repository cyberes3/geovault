package com.geovault.common.files

import android.content.Context
import android.net.Uri
import java.io.File

data class GeoVaultFileIngestResult(
    val accepted: List<GeoVaultFileRef>,
    val rejectedFileNames: List<String>,
)

/**
 * One ingest path: URIs → metadata (including MIME) → catalog classify → optional stage.
 */
class GeoVaultFileIngest(
    context: Context,
    private val catalog: GeoVaultFileTypeCatalog,
    private val stageLongLivedGrants: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val metadata = GeoVaultOpenableUriMetadata(appContext.contentResolver)
    private val stager = GeoVaultIncomingFileStager(
        cacheDir = appContext.cacheDir,
        metadata = metadata,
    )

    fun ingest(
        uris: List<Uri>,
        source: GeoVaultFileRef.Source,
    ): GeoVaultFileIngestResult {
        if (uris.isEmpty()) {
            return GeoVaultFileIngestResult(accepted = emptyList(), rejectedFileNames = emptyList())
        }
        val classification = catalog.classify(
            uris = uris,
            displayNameOf = metadata::displayName,
            mimeTypeOf = metadata::mimeType,
        )
        val accepted = classification.supported.map { uri -> toRef(uri, source) }
        return GeoVaultFileIngestResult(
            accepted = accepted,
            rejectedFileNames = classification.rejectedFileNames,
        )
    }

    private fun toRef(uri: Uri, source: GeoVaultFileRef.Source): GeoVaultFileRef {
        val displayName = metadata.displayName(uri)
        val mime = metadata.mimeType(uri)
        val preferredName = catalog.preferredFileName(displayName, mime)
        val stagedUri = if (stageLongLivedGrants && source == GeoVaultFileRef.Source.Intent) {
            runCatching {
                val staged = stager.stage(appContext.contentResolver, uri, preferredName)
                Uri.fromFile(File(staged.path))
            }.getOrDefault(uri)
        } else {
            uri
        }
        val resolvedSource = if (stagedUri != uri) GeoVaultFileRef.Source.Staged else source
        return GeoVaultFileRef(
            uri = stagedUri,
            displayName = preferredName,
            mimeType = mime,
            extension = catalog.extensionFor(preferredName),
            sizeBytes = metadata.sizeBytes(uri),
            source = resolvedSource,
        )
    }
}
