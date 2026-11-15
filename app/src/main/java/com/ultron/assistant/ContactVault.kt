package com.ultron.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a private encrypted snapshot of the device address book.
 * The encryption key is generated in Android Keystore and never stored in the APK/repository.
 * Contact data is read only from Android's local Contacts Provider.
 */
object ContactVault {
    private const val KEY_ALIAS = "ultron_contacts_aes_v1"
    private const val FILE_NAME = "contacts.vault"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    data class Entry(val name: String, val number: String)

    @Synchronized
    fun load(context: Context): List<Entry> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return readEncryptedCache(context)
        }

        val entries = mutableListOf<Entry>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty().trim() else ""
                    val number = if (numberCol >= 0) cursor.getString(numberCol).orEmpty().trim() else ""
                    if (name.isNotBlank() && number.isNotBlank()) entries.add(Entry(name, number))
                }
            }
            writeEncryptedCache(context, entries)
            return entries
        } catch (_: SecurityException) {
            return readEncryptedCache(context)
        } catch (_: Exception) {
            return readEncryptedCache(context)
        }
    }

    private fun readEncryptedCache(context: Context): List<Entry> {
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return emptyList()
            val bytes = file.readBytes()
            if (bytes.size <= 12) return emptyList()
            val iv = bytes.copyOfRange(0, 12)
            val encrypted = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            val json = JSONArray(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.optJSONObject(i) ?: continue
                    val name = item.optString("n").trim()
                    val number = item.optString("p").trim()
                    if (name.isNotBlank() && number.isNotBlank()) add(Entry(name, number))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeEncryptedCache(context: Context, entries: List<Entry>) {
        try {
            val json = JSONArray()
            entries.forEach { entry ->
                json.put(JSONObject().put("n", entry.name).put("p", entry.number))
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            val output = cipher.iv + encrypted
            File(context.filesDir, FILE_NAME).writeBytes(output)
        } catch (_: Exception) {
            // Live Contacts Provider matching remains available if encrypted caching fails.
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
