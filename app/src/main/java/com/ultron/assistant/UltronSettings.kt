package com.ultron.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object UltronSettings {
    private const val PREFS = "ultron_settings"
    private const val SECRET_PREFS = "ultron_secure_settings"
    private const val KEY_ALIAS = "ultron_api_secrets_v1"

    fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    fun get(context: Context, key: String): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
    }

    /**
     * API credentials are encrypted with an AES-GCM key held by Android Keystore.
     * Existing plaintext credentials are migrated the first time they are read.
     */
    @Synchronized
    fun saveSecret(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        context.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    @Synchronized
    fun getSecret(context: Context, key: String): String? {
        val securePrefs = context.getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
        val encoded = securePrefs.getString(key, null)
        if (!encoded.isNullOrBlank()) {
            return try {
                val combined = Base64.decode(encoded, Base64.NO_WRAP)
                if (combined.size <= 12) return null
                val iv = combined.copyOfRange(0, 12)
                val ciphertext = combined.copyOfRange(12, combined.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        // One-time migration from the earlier SharedPreferences storage.
        val legacy = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
        if (!legacy.isNullOrBlank()) {
            saveSecret(context, key, legacy)
            return legacy
        }
        return null
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun saveName(context: Context, name: String) {
        save(context, "assistant_name", name)
    }

    fun assistantName(context: Context): String {
        return get(context, "assistant_name") ?: "ULTRON"
    }
}
