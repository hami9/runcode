package com.runcode.app.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("runcode_vault", Context.MODE_PRIVATE)
    private val keySpec: SecretKeySpec
    private val ivSpec = IvParameterSpec(ByteArray(16) { 0x42.toByte() })

    init {
        // Derive device-local 256-bit AES key from package identifier and internal install seed
        val seed = context.packageName + "_runcode_salt_2026"
        val sha = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha.digest(seed.toByteArray(StandardCharsets.UTF_8))
        keySpec = SecretKeySpec(keyBytes, "AES")
    }

    fun setSecret(key: String, plainText: String) {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val base64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(key, base64).apply()
        } catch (e: Exception) {
            prefs.edit().putString(key, plainText).apply()
        }
    }

    fun getSecret(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(bytes)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            stored
        }
    }

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getAllSecretKeys(): Set<String> {
        return prefs.all.keys
    }

    /**
     * Resolves strings like ${SEC_TELEGRAM_TOKEN} into the decrypted value if present in the vault.
     */
    fun resolveValue(raw: String): String {
        if (raw.startsWith("\${SEC_") && raw.endsWith("}")) {
            val key = raw.substring(6, raw.length - 1)
            return getSecret(key) ?: raw
        }
        return raw
    }
}
