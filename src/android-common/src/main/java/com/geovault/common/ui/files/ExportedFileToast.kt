package com.geovault.common.ui.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import java.io.File

/**
 * Shared success toast for SAF-backed exports (data files, generated KML, coordinate systems).
 *
 * Location resolution order:
 * 1. `file:` → filesystem path
 * 2. Real backing path via [android.content.ContentResolver.openFileDescriptor] and the
 *    `/proc/self/fd/<n>` symlink (works for many on-device `content://` saves)
 * 3. [DocumentsContract.getDocumentId], or the same id decoded from `…/document/<encoded>`
 *    when the provider omits a colon in the parsed id but the URI still carries `primary:…`
 * 4. The full destination [Uri] string (always defined; no placeholder copy)
 *
 * Resolved locations are shortened (internal `/storage/emulated/…`, SD volume roots,
 * `primary:` ids, etc.), then only the **parent folder** is shown after the export name
 * (no filename in the location half; the word “target” is not used). Folder labels end
 * with `/` (e.g. `Download/`).
 */
object ExportedFileToast {

    private val storageEmulatedUser = Regex("^/storage/emulated/\\d+/?")
    private val storageSelfPrimary = Regex("^/storage/self/primary/?")
    private val sdcardSymlink = Regex("^/sdcard/?")
    /** FAT volume id on removable SD (Android external storage UUID form). */
    private val storageFatVolume = Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/?")
    private val mntMediaRwFatVolume = Regex("^/mnt/media_rw/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/?")
    private val mntExpandMediaUser = Regex("^/mnt/expand/[0-9a-fA-F-]+/media/\\d+/?")
    private val oemExtSd = Regex("^/storage/extSdCard/?")
    private val oemSdcard1 = Regex("^/storage/sdcard1/?")
    private val fatVolumeId = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    fun show(
        context: Context,
        destinationUri: Uri,
        fallbackBaseName: String,
        extensionWithoutDot: String?,
    ) {
        val displayName = buildExportedDisplayName(
            context = context,
            destinationUri = destinationUri,
            fallbackBaseName = fallbackBaseName,
            extensionWithoutDot = extensionWithoutDot?.lowercase()?.removePrefix("."),
        )
        val resolvedLocation = buildExportTargetLabel(context, destinationUri)
        val parentFolder = parentFolderForToast(resolvedLocation, displayName)
        val message = if (parentFolder == "." || parentFolder.isEmpty()) {
            "Exported $displayName"
        } else {
            "Exported $displayName to $parentFolder"
        }
        Toast.makeText(
            context.applicationContext,
            message,
            Toast.LENGTH_LONG,
        ).show()
    }

    /**
     * Drops the leaf segment so the toast shows only the directory, with a trailing `/`.
     * When there is no `/`, treats a lone segment that matches [exportedFileName] as
     * “file at root” (`.`). `content://` fallbacks are left unchanged.
     */
    private fun parentFolderForToast(resolvedPathOrLabel: String, exportedFileName: String): String {
        if (resolvedPathOrLabel.startsWith("content://", ignoreCase = true)) {
            return resolvedPathOrLabel
        }
        val t = resolvedPathOrLabel.trimEnd('/')
        val lastSlash = t.lastIndexOf('/')
        if (lastSlash < 0) {
            val leaf = if (t.equals(exportedFileName, ignoreCase = true)) "." else t
            return if (leaf == ".") "." else folderWithTrailingSlash(leaf)
        }
        val parent = t.substring(0, lastSlash).trimEnd('/').ifEmpty { "." }
        return if (parent == ".") "." else folderWithTrailingSlash(parent)
    }

    private fun folderWithTrailingSlash(folderPath: String): String {
        val f = folderPath.trimEnd('/')
        if (f.isEmpty()) return "/"
        return "$f/"
    }

    private fun buildExportedDisplayName(
        context: Context,
        destinationUri: Uri,
        fallbackBaseName: String,
        extensionWithoutDot: String?,
    ): String {
        queryDisplayName(context, destinationUri)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val base = fallbackBaseName.trim()
        val ext = extensionWithoutDot?.trim()?.removePrefix(".")?.takeIf { it.isNotEmpty() }
            ?: return base

        if (base.endsWith(".$ext", ignoreCase = true)) return base
        val dot = base.lastIndexOf('.')
        if (dot in 1 until base.length - 1) return base
        return "$base.$ext"
    }

    private fun buildExportTargetLabel(context: Context, uri: Uri): String {
        if ("file".equals(uri.scheme, true)) {
            val p = uri.path?.takeIf { it.isNotEmpty() } ?: return uri.toString()
            return prettifyAbsolutePath(p)
        }

        filesystemPathViaFd(context, uri)?.let { return prettifyAbsolutePath(it) }

        firstHumanReadableDocumentId(uri)?.let { return prettifyDocumentId(it) }

        return uri.toString()
    }

    /**
     * Collapses redundant slashes, strips well-known Android storage roots, and prefixes
     * removable SD-relative paths with `SD card/` so they stay distinct from internal storage.
     */
    private fun prettifyAbsolutePath(path: String): String {
        var p = path.replace(Regex("/+"), "/")
        val original = p
        p = p.replaceFirst(mntMediaRwFatVolume, "SD card/")
        p = p.replaceFirst(mntExpandMediaUser, "")
        p = p.replaceFirst(storageEmulatedUser, "")
        p = p.replaceFirst(storageSelfPrimary, "")
        p = p.replaceFirst(sdcardSymlink, "")
        p = p.replaceFirst(oemExtSd, "SD card/")
        p = p.replaceFirst(oemSdcard1, "SD card/")
        p = p.replaceFirst(storageFatVolume, "SD card/")
        p = p.trimStart('/')
        return p.ifEmpty { original.trimStart('/') }
    }

    /** `primary:Download/a.kml` → `Download/a.kml`; `ABCD-EFGH:DCIM/x` → `SD card/DCIM/x`. */
    private fun prettifyDocumentId(documentId: String): String {
        val id = documentId.trim()
        if (id.startsWith("primary:", ignoreCase = true)) {
            return id.substring("primary:".length).trimStart('/')
        }
        val colon = id.indexOf(':')
        if (colon in 1 until id.length - 1) {
            val volume = id.substring(0, colon)
            val rest = id.substring(colon + 1).trimStart('/')
            if (fatVolumeId.matches(volume)) {
                return "SD card/$rest".trimEnd('/')
            }
        }
        return id
    }

    /**
     * When the backing node is a normal file, [File.getCanonicalFile] on `/proc/self/fd/N`
     * resolves to the real absolute path (unlike a bare provider row id in metadata).
     */
    private fun filesystemPathViaFd(context: Context, uri: Uri): String? =
        runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r", null)
                ?: context.contentResolver.openFileDescriptor(uri, "rw", null)
            pfd?.use {
                val path = File("/proc/self/fd/${it.fd}").canonicalFile.absolutePath
                when {
                    path.startsWith("/proc/") -> null
                    "(deleted)" in path -> null
                    else -> path
                }
            }
        }.getOrNull()

    /** Prefer the encoded `/document/…` tail — matches platform docs when [getDocumentId] is wrong. */
    private fun firstHumanReadableDocumentId(uri: Uri): String? {
        decodeDocumentIdFromUriPath(uri)?.let { if (isHumanReadableDocumentId(it)) return it }
        runCatching { DocumentsContract.getDocumentId(uri) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && isHumanReadableDocumentId(it) }
            ?.let { return it }
        return null
    }

    private fun decodeDocumentIdFromUriPath(uri: Uri): String? {
        val enc = uri.encodedPath ?: return null
        val marker = "/document/"
        val i = enc.indexOf(marker)
        if (i < 0) return null
        return Uri.decode(enc.substring(i + marker.length).trimStart('/')).takeIf { it.isNotEmpty() }
    }

    private fun isHumanReadableDocumentId(documentId: String): Boolean {
        if (documentId.all { it.isDigit() }) return false
        if (documentId.contains(':')) return true
        if (documentId.contains('/')) return true
        if (documentId.length >= 12) return true
        return false
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        GeoVaultOpenableUriMetadata(context.contentResolver).displayNameOrNull(uri)
}
