package com.geovault.common.sort

import java.util.Locale

object GeoVaultFileListSort {
    fun <T> comparator(
        mode: GeoVaultFileListSortMode,
        nameSelector: (T) -> String,
        modifiedAtSelector: (T) -> Long,
    ): Comparator<T> = when (mode) {
        GeoVaultFileListSortMode.NAME_A_TO_Z -> naturalNameComparator(nameSelector)
        GeoVaultFileListSortMode.NAME_Z_TO_A -> naturalNameComparator(nameSelector).reversed()
        GeoVaultFileListSortMode.MODIFIED_NEWEST -> {
            val byModified = compareByDescending<T> { modifiedAtSelector(it) }
            byModified.then(naturalNameComparator(nameSelector))
        }
        GeoVaultFileListSortMode.MODIFIED_OLDEST -> {
            val byModified = compareBy<T> { modifiedAtSelector(it) }
            byModified.then(naturalNameComparator(nameSelector))
        }
    }

    private fun <T> naturalNameComparator(nameSelector: (T) -> String): Comparator<T> =
        NaturalSort.byName(Locale.getDefault(), nameSelector)
}
