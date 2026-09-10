package com.geovault.common.files

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Single-slot disk store for an in-flight SAF Save.
 *
 * Meta present means the stage completed. [consume] is the only read after a destination
 * URI returns; a new instance on the same [slotDir] must see the same slot (process death).
 */
class GeoVaultSafPendingExportStore(
    private val slotDir: File,
    private val json: Json = JSON,
) {
    private val payloadFile = File(slotDir, PAYLOAD_NAME)
    private val metaFile = File(slotDir, META_NAME)

    fun stage(request: GeoVaultSafExportRequest, writeFailedMessage: String) {
        slotDir.mkdirs()
        clear()
        val meta = GeoVaultStagedSafExportMeta(
            suggestedFileName = request.suggestedFileName,
            fallbackBaseName = request.fallbackBaseName,
            extensionWithoutDot = request.extensionWithoutDot,
            mimeType = request.mimeType,
            writeFailedMessage = writeFailedMessage,
        )
        val payloadTmp = File(slotDir, "$PAYLOAD_NAME.tmp")
        val metaTmp = File(slotDir, "$META_NAME.tmp")
        payloadTmp.writeBytes(request.bytes)
        metaTmp.writeText(json.encodeToString(GeoVaultStagedSafExportMeta.serializer(), meta))
        replaceFile(payloadTmp, payloadFile)
        replaceFile(metaTmp, metaFile)
    }

    fun consume(): GeoVaultStagedSafExport? {
        if (!metaFile.isFile || !payloadFile.isFile) {
            clear()
            return null
        }
        val meta = runCatching {
            json.decodeFromString(GeoVaultStagedSafExportMeta.serializer(), metaFile.readText())
        }.getOrNull()
        if (meta == null) {
            clear()
            return null
        }
        return GeoVaultStagedSafExport(payloadFile, meta)
    }

    fun clear() {
        payloadFile.delete()
        metaFile.delete()
        File(slotDir, "$PAYLOAD_NAME.tmp").delete()
        File(slotDir, "$META_NAME.tmp").delete()
    }

    private fun replaceFile(from: File, to: File) {
        if (from.renameTo(to)) return
        to.delete()
        from.copyTo(to, overwrite = true)
        from.delete()
    }

    companion object {
        const val DIR_NAME: String = "saf-pending"
        const val PAYLOAD_NAME: String = "payload.bin"
        const val META_NAME: String = "meta.json"

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun inCache(cacheDir: File): GeoVaultSafPendingExportStore =
            GeoVaultSafPendingExportStore(File(cacheDir, DIR_NAME))
    }
}
