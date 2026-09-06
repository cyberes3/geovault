package com.geovault.common.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class GeoVaultSecureString(val ciphertext: String) {
    fun decrypt(): String? = GeoVaultSecureCipher.decrypt(ciphertext)

    companion object {
        fun encrypt(plaintext: String): GeoVaultSecureString {
            return GeoVaultSecureString(GeoVaultSecureCipher.encrypt(plaintext))
        }

        fun fromPersisted(raw: String?): GeoVaultSecureString? {
            if (raw.isNullOrBlank()) return null
            return if (raw.startsWith(GeoVaultSecureCipher.ENVELOPE_PREFIX)) {
                GeoVaultSecureString(raw)
            } else {
                encrypt(raw)
            }
        }
    }
}

internal object GeoVaultSecureCipher {
    const val ENVELOPE_PREFIX = "v1:"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "geovault_auth_tokens_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    @Volatile
    private var useSoftwareKey = false

    private val softwareKey: SecretKey by lazy {
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String): String {
        return try {
            encryptWith(activeKey(), plaintext)
        } catch (error: Exception) {
            if (useSoftwareKey) throw error
            useSoftwareKey = true
            encryptWith(softwareKey, plaintext)
        }
    }

    fun decrypt(envelope: String): String? {
        if (!envelope.startsWith(ENVELOPE_PREFIX)) return null
        val primary = decryptWith(activeKey(), envelope)
        if (primary != null) return primary
        if (!useSoftwareKey) {
            return decryptWith(softwareKey, envelope)
        }
        return null
    }

    private fun activeKey(): SecretKey {
        if (useSoftwareKey) return softwareKey
        return try {
            getOrCreateAndroidKey()
        } catch (_: Exception) {
            useSoftwareKey = true
            softwareKey
        }
    }

    private fun encryptWith(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivEncoded = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataEncoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$ENVELOPE_PREFIX$ivEncoded:$dataEncoded"
    }

    private fun decryptWith(key: SecretKey, envelope: String): String? {
        val payload = envelope.removePrefix(ENVELOPE_PREFIX)
        val parts = payload.split(':')
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            if (iv.size != IV_SIZE_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateAndroidKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
