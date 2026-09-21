package com.roboyi.rtspdemo.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** RTSP URLs can contain camera passwords or tokens. Keep them out of plain preferences and backups. */
class SecureConnectionStore(context: Context, private val legacy: SharedPreferences) {
    private val encrypted = context.getSharedPreferences("connection_secrets", Context.MODE_PRIVATE)

    init {
        // Migrate each old value only after its encrypted replacement has reached disk.
        for (key in listOf(LAST_URL, HISTORY)) {
            if (legacy.contains(key)) {
                val value = legacy.getString(key, null)
                if (value != null) put(key, value)
                check(legacy.edit().remove(key).commit()) {
                    "Could not remove plaintext connection data"
                }
            }
        }
    }

    fun get(key: String): String? {
        val value = encrypted.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size > IV_LENGTH + TAG_LENGTH_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrElse {
            // A restored or invalidated keystore key cannot decrypt old URLs. Drop them safely.
            encrypted.edit().remove(key).apply()
            null
        }
    }

    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(encrypted.edit().putString(key, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "Could not save encrypted connection data"
        }
    }

    fun remove(key: String) {
        check(encrypted.edit().remove(key).commit()) { "Could not remove encrypted connection data" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    companion object {
        const val LAST_URL = "last_url"
        const val HISTORY = "history"
        private const val KEY_ALIAS = "roboyi_rtsp_connections_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH_BYTES = 16
    }
}
