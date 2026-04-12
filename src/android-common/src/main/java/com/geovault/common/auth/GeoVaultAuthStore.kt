package com.geovault.common.auth

import android.content.Context
import android.util.Log
import com.geovault.common.SecureValueCipher
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey

class GeoVaultAuthStore private constructor(context: Context) {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS
    )

    fun preloadAll() {
        store.preloadAllDataBlocking()
    }

    // ── Server URL (plain, not encrypted) ──────────────────────────────

    fun getServerUrl(): String = store.getBlocking(KEY_SERVER_URL)

    fun setServerUrl(url: String) {
        store.putBlocking(KEY_SERVER_URL, url)
    }

    suspend fun getServerUrlAsync(): String = store.get(KEY_SERVER_URL)

    suspend fun setServerUrlAsync(url: String) {
        store.put(KEY_SERVER_URL, url)
    }

    // ── Access token (encrypted, with expiration check) ────────────────

    fun getAccessToken(): String? {
        val expiresAt = store.getBlocking(KEY_EXPIRES_AT)
        if (expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - TOKEN_BUFFER_SECONDS) {
            return null
        }
        return getSecureValue(KEY_ACCESS_TOKEN)
    }

    fun getRawAccessToken(): String? = getSecureValue(KEY_ACCESS_TOKEN)

    // ── Refresh token (encrypted) ──────────────────────────────────────

    fun getRefreshToken(): String? = getSecureValue(KEY_REFRESH_TOKEN)

    // ── Save / clear tokens ────────────────────────────────────────────

    fun saveTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        store.putBatchBlocking(buildMap {
            put(KEY_ACCESS_TOKEN, SecureValueCipher.encrypt(accessToken))
            put(KEY_REFRESH_TOKEN, encryptOrNull(refreshToken))
            put(KEY_EXPIRES_AT, System.currentTimeMillis() / 1000 + expiresInSeconds)
        })
    }

    fun clearTokens() {
        store.putBatchBlocking(mapOf(
            KEY_ACCESS_TOKEN to null,
            KEY_REFRESH_TOKEN to null,
            KEY_EXPIRES_AT to null,
            KEY_USER_EMAIL to null
        ))
    }

    fun clearAuthData() {
        store.putBatchBlocking(mapOf(
            KEY_ACCESS_TOKEN to null,
            KEY_REFRESH_TOKEN to null,
            KEY_EXPIRES_AT to null,
            KEY_PKCE_VERIFIER to null,
            KEY_PKCE_STATE to null,
            KEY_USER_EMAIL to null
        ))
    }

    // ── PKCE state (encrypted) ─────────────────────────────────────────

    fun savePkceState(verifier: String, state: String) {
        val encVerifier = SecureValueCipher.encrypt(verifier)
        val encState = SecureValueCipher.encrypt(state)
        Log.d(TAG, "savePkceState: state=$state encrypted verifier=${encVerifier.length} chars, state=${encState.length} chars")
        store.putBatchBlocking(mapOf(
            KEY_PKCE_VERIFIER to encVerifier,
            KEY_PKCE_STATE to encState
        ))
        Log.i(TAG, "savePkceState: written to store")
    }

    fun getAndClearPkceState(): Pair<String, String>? {
        val rawVerifier = store.getBlocking(KEY_PKCE_VERIFIER)
        val rawState = store.getBlocking(KEY_PKCE_STATE)
        Log.d(TAG, "getAndClearPkceState: raw verifier=${if (rawVerifier.isBlank()) "BLANK" else "${rawVerifier.length} chars"}" +
            " raw state=${if (rawState.isBlank()) "BLANK" else "${rawState.length} chars"}")

        val verifier = getSecureValue(KEY_PKCE_VERIFIER)
        val state = getSecureValue(KEY_PKCE_STATE)
        Log.d(TAG, "getAndClearPkceState: decrypted verifier=${if (verifier == null) "NULL" else "present"}" +
            " state=${state ?: "NULL"}")

        if (verifier == null || state == null) {
            Log.w(TAG, "getAndClearPkceState: returning null — verifier=${verifier != null} state=${state != null}")
            return null
        }
        store.putBatchBlocking(mapOf(
            KEY_PKCE_VERIFIER to null,
            KEY_PKCE_STATE to null
        ))
        Log.i(TAG, "getAndClearPkceState: cleared stored PKCE, returning state=$state")
        return verifier to state
    }

    // ── Cached user email (encrypted) ──────────────────────────────────

    fun getCachedUserEmail(): String? = getSecureValue(KEY_USER_EMAIL)

    fun setCachedUserEmail(email: String?) {
        store.putBatchBlocking(mapOf(KEY_USER_EMAIL to encryptOrNull(email)))
    }

    // ── Debug ──────────────────────────────────────────────────────────

    fun getExpiresAt(): Long = store.getBlocking(KEY_EXPIRES_AT)

    // ── Encryption helpers ─────────────────────────────────────────────

    private fun getSecureValue(key: PrefKey.StringKey): String? {
        val encrypted = store.getBlocking(key)
        if (encrypted.isBlank()) return null
        val decrypted = SecureValueCipher.decrypt(encrypted)
        if (decrypted == null) {
            Log.w(TAG, "secure_decrypt_failed key=${key.name}")
            store.removeBlocking(key)
        }
        return decrypted?.takeIf { it.isNotBlank() }
    }

    private fun encryptOrNull(value: String?): String? {
        return if (value.isNullOrBlank()) null else SecureValueCipher.encrypt(value)
    }

    companion object {
        private const val TAG = "GeoVaultAuthStore"
        private const val PREFS_NAME = "geovault_auth"
        private const val SCHEMA_VERSION = 1
        private const val TOKEN_BUFFER_SECONDS = 60L

        private val KEY_SERVER_URL = PrefKey.StringKey("server_url")
        private val KEY_ACCESS_TOKEN = PrefKey.StringKey("access_token")
        private val KEY_REFRESH_TOKEN = PrefKey.StringKey("refresh_token")
        private val KEY_EXPIRES_AT = PrefKey.LongKey("expires_at")
        private val KEY_PKCE_VERIFIER = PrefKey.StringKey("pkce_code_verifier")
        private val KEY_PKCE_STATE = PrefKey.StringKey("pkce_state")
        private val KEY_USER_EMAIL = PrefKey.StringKey("cached_user_email")

        private val ALL_KEYS: Set<PrefKey<*>> = setOf(
            KEY_SERVER_URL,
            KEY_ACCESS_TOKEN,
            KEY_REFRESH_TOKEN,
            KEY_EXPIRES_AT,
            KEY_PKCE_VERIFIER,
            KEY_PKCE_STATE,
            KEY_USER_EMAIL
        )

        @Volatile
        private var instance: GeoVaultAuthStore? = null

        fun getInstance(context: Context): GeoVaultAuthStore {
            return instance ?: synchronized(this) {
                instance ?: GeoVaultAuthStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
