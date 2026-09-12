package com.runcode.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-bound secret vault.
 *
 * The AES-256 key lives in the Android Keystore, so it never leaves the device and cannot be
 * re-derived from anything shipped in the APK. Each value gets a fresh random IV, and a value
 * that cannot be encrypted is refused rather than written out in the clear.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secretKey: SecretKey? by lazy { loadOrCreateKey() }

    /** Returns true when the value was stored encrypted. Nothing is written otherwise. */
    fun setSecret(key: String, plainText: String): Boolean {
        val encoded = encrypt(plainText) ?: return false
        prefs.edit().putString(key, encoded).apply()
        return true
    }

    fun getSecret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return decrypt(stored)
    }

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getAllSecretKeys(): Set<String> {
        return prefs.all.keys
    }

    /**
     * Resolves strings like ${SEC_TELEGRAM_TOKEN} into the decrypted value if present in the
     * vault. The placeholder is returned unchanged when no such secret exists.
     */
    fun resolveValue(raw: String): String {
        if (raw.startsWith(PLACEHOLDER_PREFIX) && raw.endsWith("}")) {
            val key = raw.substring(PLACEHOLDER_PREFIX.length, raw.length - 1)
            return getSecret(key) ?: raw
        }
        return raw
    }

    private fun encrypt(plainText: String): String? {
        val key = secretKey ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(stored: String): String? {
        val key = secretKey ?: return null
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val payload = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadOrCreateKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
        } catch (_: Exception) {
            null
        }
    }

    private fun generateKey(): SecretKey? {
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PREFS_NAME = "runcode_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "runcode_vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = ":"
        const val PLACEHOLDER_PREFIX = "\${SEC_"
    }
}
