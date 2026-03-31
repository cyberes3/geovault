package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UploaderSettings(
    val suffixEnabled: Boolean = true
)

class UploaderPreferences private constructor(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SETTINGS_SCHEMA_VERSION,
        registeredKeys = setOf(KEY_ADD_SUFFIX)
    )
    private val _settings = MutableStateFlow(UploaderSettings())
    val settings: StateFlow<UploaderSettings> = _settings.asStateFlow()

    init {
        appScope.launch {
            store.normalize()
        }
        appScope.launch {
            store.observe(KEY_ADD_SUFFIX).collect { enabled ->
                _settings.value = _settings.value.copy(suffixEnabled = enabled)
            }
        }
    }

    fun isSuffixEnabled(): Boolean = settings.value.suffixEnabled

    suspend fun setSuffixEnabled(enabled: Boolean) {
        store.put(KEY_ADD_SUFFIX, enabled)
    }

    fun clearAll() {
        store.clearBlocking()
    }

    companion object {
        private const val SETTINGS_SCHEMA_VERSION = 1
        private const val PREFS_NAME = "geovault_prefs"
        const val PREF_ADD_SUFFIX = "add_suffix"

        private val KEY_ADD_SUFFIX = PrefKey.BooleanKey(PREF_ADD_SUFFIX, true)

        @Volatile
        private var instance: UploaderPreferences? = null

        fun getInstance(context: Context): UploaderPreferences {
            return instance ?: synchronized(this) {
                instance ?: UploaderPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
