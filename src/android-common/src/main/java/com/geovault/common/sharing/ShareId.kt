package com.geovault.common.sharing

/**
 * UUID4 token used as the global share-link identity.
 */
data class ShareId(val value: String) {
    init {
        require(isShareId(value)) { "Share id must be a UUID4" }
    }

    override fun toString(): String = value

    companion object {
        private val SHARE_ID_PATTERN =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        fun parse(value: String?): ShareId? {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (!SHARE_ID_PATTERN.matches(normalized)) {
                return null
            }
            return ShareId(normalized)
        }

        fun isShareId(value: String?): Boolean = parse(value) != null
    }
}
