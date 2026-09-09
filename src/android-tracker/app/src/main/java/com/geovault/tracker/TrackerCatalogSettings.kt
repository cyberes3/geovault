package com.geovault.tracker

data class TrackerCatalogSettings(
    val hidden: Boolean = false,
    val recentDataWindow: String? = null,
    val allowGroupReshare: Boolean? = null,
    val color: String? = null,
)
