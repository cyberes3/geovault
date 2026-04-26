package com.geovault.common.htmlrender

/**
 * Successful render output. PDF bytes are held in memory; very large documents may be costly.
 */
sealed class RenderArtifact {
    data class Pdf(val bytes: ByteArray) : RenderArtifact() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Pdf) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
