package com.geovault.uploader.data

import android.content.Context
import com.geovault.common.settings.GeoVaultDocumentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class UploaderSettings(
    val suffixEnabled: Boolean = true
)

class UploaderPreferences private constructor(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = UploaderOptionsDocument.FILE_NAME,
        documentSerializer = UploaderOptionsDocument.serializer(),
        defaultValue = UploaderOptionsDocument(),
        currentVersion = UploaderOptionsDocument.SCHEMA_VERSION,
        legacyFileName = UploaderOptionsDocument.LEGACY_FILE_NAME,
        legacyMapper = UploaderOptionsDocument::fromLegacy,
    )
    private val _settings = MutableStateFlow(UploaderSettings())
    val settings: StateFlow<UploaderSettings> = _settings.asStateFlow()

    init {
        appScope.launch {
            store.data.collect { document ->
                _settings.value = UploaderSettings(suffixEnabled = document.addFilenameSuffix)
            }
        }
    }

    fun isSuffixEnabled(): Boolean = settings.value.suffixEnabled

    suspend fun setSuffixEnabled(enabled: Boolean) {
        store.update { current -> current.copy(addFilenameSuffix = enabled) }
    }

    fun clearAll() {
        runBlocking(Dispatchers.IO) {
            store.update { UploaderOptionsDocument() }
        }
    }

    fun preloadOnLaunch() {
        appScope.launch {
            store.get()
        }
    }

    companion object {
        @Volatile
        private var instance: UploaderPreferences? = null

        fun getInstance(context: Context): UploaderPreferences {
            return instance ?: synchronized(this) {
                instance ?: UploaderPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
