package com.geovault.common.ui.navigation

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf

class GeoVaultBackRegistry {
    private data class Entry(
        val id: Long,
        val priority: Int,
        val navigator: GeoVaultBackNavigator,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var nextId = 1L
    private val registrationCountStateValue = mutableIntStateOf(0)

    val registrationCountState: State<Int> = registrationCountStateValue

    fun hasRegisteredNavigators(): Boolean = registrationCountStateValue.intValue > 0

    fun register(
        navigator: GeoVaultBackNavigator,
        priority: Int = 0,
    ): GeoVaultBackRegistration {
        val entry = synchronized(lock) {
            Entry(
                id = nextId++,
                priority = priority,
                navigator = navigator,
            ).also { entries.add(it) }
        }
        registrationCountStateValue.intValue = synchronized(lock) { entries.size }
        return GeoVaultBackRegistration {
            unregister(entry.id)
        }
    }

    fun dispatchBack(): Boolean {
        val snapshot = synchronized(lock) {
            entries
                .sortedWith(
                    compareByDescending<Entry> { it.priority }
                        .thenByDescending { it.id }
                )
        }
        for (entry in snapshot) {
            if (!entry.navigator.canGoBack()) continue
            if (entry.navigator.goBack()) return true
        }
        return false
    }

    private fun unregister(id: Long) {
        synchronized(lock) {
            entries.removeAll { it.id == id }
        }
        registrationCountStateValue.intValue = synchronized(lock) { entries.size }
    }
}

fun interface GeoVaultBackRegistration {
    fun unregister()
}
