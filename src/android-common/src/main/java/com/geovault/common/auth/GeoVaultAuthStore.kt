package com.geovault.common.auth

import android.content.Context
import android.util.Log
import com.geovault.common.settings.AuthSettingsDocument
import com.geovault.common.settings.GeoVaultDocumentStore
import com.geovault.common.settings.GeoVaultSecureString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class GeoVaultAuthStore private constructor(context: Context) {
    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = AuthSettingsDocument.FILE_NAME,
        documentSerializer = AuthSettingsDocument.serializer(),
        defaultValue = AuthSettingsDocument(),
        currentVersion = AuthSettingsDocument.SCHEMA_VERSION,
        legacyMapper = AuthSettingsDocument::fromLegacy,
    )
    private val lock = Any()

    @Volatile
    private var cached = AuthSettingsDocument()

    @Volatile
    private var hydrated = false

    fun preloadAll() {
        ensureHydrated()
    }

    fun getServerUrl(): String {
        ensureHydrated()
        return cached.serverUrl
    }

    fun setServerUrl(url: String) {
        updateCacheAndPersist { current -> current.copy(serverUrl = url) }
    }

    fun getAccessToken(): String? {
        ensureHydrated()
        val expiresAt = cached.expiresAt
        if (expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - TOKEN_BUFFER_SECONDS) {
            return null
        }
        return decrypted(cached.accessToken) { current -> current.copy(accessToken = null) }
    }

    fun getRawAccessToken(): String? {
        ensureHydrated()
        return decrypted(cached.accessToken) { current -> current.copy(accessToken = null) }
    }

    fun getRefreshToken(): String? {
        ensureHydrated()
        return decrypted(cached.refreshToken) { current -> current.copy(refreshToken = null) }
    }

    fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        updateCacheAndPersist { current ->
            current.copy(
                accessToken = GeoVaultSecureString.encrypt(accessToken),
                refreshToken = encryptOrNull(refreshToken),
                expiresAt = System.currentTimeMillis() / 1000 + expiresInSeconds,
            )
        }
    }

    fun clearTokens() {
        updateCacheAndPersist { current ->
            current.copy(
                accessToken = null,
                refreshToken = null,
                expiresAt = 0L,
                cachedUserEmail = null,
            )
        }
    }

    fun savePkceState(verifier: String, state: String) {
        val encVerifier = GeoVaultSecureString.encrypt(verifier)
        val encState = GeoVaultSecureString.encrypt(state)
        Log.d(TAG, "savePkceState: state=$state encrypted verifier=${encVerifier.ciphertext.length} chars, state=${encState.ciphertext.length} chars")
        updateCacheAndPersist { current ->
            current.copy(
                pkceVerifier = encVerifier,
                pkceState = encState,
                lastConsumedPkceState = null,
                lastConsumedPkceAt = 0L,
            )
        }
        Log.i(TAG, "savePkceState: written to store")
    }

    fun getAndClearPkceState(): Pair<String, String>? {
        synchronized(lock) {
            ensureHydratedLocked()
            val rawVerifier = cached.pkceVerifier?.ciphertext.orEmpty()
            val rawState = cached.pkceState?.ciphertext.orEmpty()
            Log.d(
                TAG,
                "getAndClearPkceState: raw verifier=${if (rawVerifier.isBlank()) "BLANK" else "${rawVerifier.length} chars"}" +
                    " raw state=${if (rawState.isBlank()) "BLANK" else "${rawState.length} chars"}"
            )

            val verifier = cached.pkceVerifier?.decrypt()
            val state = cached.pkceState?.decrypt()
            Log.d(
                TAG,
                "getAndClearPkceState: decrypted verifier=${if (verifier == null) "NULL" else "present"}" +
                    " state=${state ?: "NULL"}"
            )

            if (verifier.isNullOrBlank() || state.isNullOrBlank()) {
                Log.w(TAG, "getAndClearPkceState: returning null — verifier=${verifier != null} state=${state != null}")
                return null
            }
            persistLocked { current ->
                current.copy(
                    pkceVerifier = null,
                    pkceState = null,
                    lastConsumedPkceState = GeoVaultSecureString.encrypt(state),
                    lastConsumedPkceAt = System.currentTimeMillis(),
                )
            }
            Log.i(TAG, "getAndClearPkceState: cleared stored PKCE, returning state=$state")
            return verifier to state
        }
    }

    fun wasRecentlyConsumedPkceState(state: String): Boolean {
        ensureHydrated()
        val consumedState = cached.lastConsumedPkceState?.decrypt()
        val consumedAt = cached.lastConsumedPkceAt
        if (state.isBlank() || consumedState != state || consumedAt <= 0L) return false
        return System.currentTimeMillis() - consumedAt <= RECENT_PKCE_STATE_WINDOW_MS
    }

    fun getCachedUserEmail(): String? {
        ensureHydrated()
        return decrypted(cached.cachedUserEmail) { current -> current.copy(cachedUserEmail = null) }
    }

    fun setCachedUserEmail(email: String?) {
        updateCacheAndPersist { current -> current.copy(cachedUserEmail = encryptOrNull(email)) }
    }

    fun getExpiresAt(): Long {
        ensureHydrated()
        return cached.expiresAt
    }

    private fun decrypted(
        value: GeoVaultSecureString?,
        clear: (AuthSettingsDocument) -> AuthSettingsDocument,
    ): String? {
        if (value == null) return null
        val decrypted = value.decrypt()
        if (decrypted == null) {
            Log.w(TAG, "secure_decrypt_failed")
            updateCacheAndPersist(clear)
            return null
        }
        return decrypted.takeIf { it.isNotBlank() }
    }

    private fun encryptOrNull(value: String?): GeoVaultSecureString? {
        return if (value.isNullOrBlank()) null else GeoVaultSecureString.encrypt(value)
    }

    private fun ensureHydrated() {
        if (hydrated) return
        synchronized(lock) {
            ensureHydratedLocked()
        }
    }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        cached = runBlocking(Dispatchers.IO) { store.get() }
        hydrated = true
    }

    private fun updateCacheAndPersist(transform: (AuthSettingsDocument) -> AuthSettingsDocument) {
        synchronized(lock) {
            ensureHydratedLocked()
            persistLocked(transform)
        }
    }

    private fun persistLocked(transform: (AuthSettingsDocument) -> AuthSettingsDocument) {
        val next = transform(cached)
        cached = next
        runBlocking(Dispatchers.IO) {
            store.update { next }
        }
    }

    companion object {
        private const val TAG = "GeoVaultAuthStore"
        private const val TOKEN_BUFFER_SECONDS = 60L
        private const val RECENT_PKCE_STATE_WINDOW_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: GeoVaultAuthStore? = null

        fun getInstance(context: Context): GeoVaultAuthStore {
            return instance ?: synchronized(this) {
                instance ?: GeoVaultAuthStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
