package com.geovault.common.net

/**
 * Canonical GeoVault API origin: scheme required, no trailing slash, no path.
 *
 * Stored form and path concatenation use [value]. Retrofit's `baseUrl` requires a trailing
 * slash — use [asRetrofitBase].
 */
@JvmInline
value class GeoVaultServerUrl private constructor(val value: String) {
    fun asRetrofitBase(): String = "$value/"

    fun resolve(path: String): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return value + normalizedPath
    }

    companion object {
        fun parse(raw: String): GeoVaultServerUrl? {
            val normalized = normalize(raw) ?: return null
            return GeoVaultServerUrl(normalized)
        }

        fun normalize(raw: String): String? {
            var normalized = raw.trim().trimStart('/').trimEnd('/')
            if (normalized.isEmpty()) return null
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                normalized = "https://$normalized"
            }
            return normalized
        }
    }
}
