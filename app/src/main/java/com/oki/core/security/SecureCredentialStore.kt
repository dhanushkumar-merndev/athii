package com.oki.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.credentialStore by preferencesDataStore("encrypted_credentials")

enum class Provider {
    GEMINI,
    GROQ,
}

class CredentialCipher(private val key: () -> SecretKey) {
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}

class SecureCredentialStore(context: Context) {
    private val store = context.credentialStore
    private val cipher = CredentialCipher(::key)

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("oki_api_aes_v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "oki_api_aes_v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    suspend fun save(provider: Provider, value: String) =
        withContext(Dispatchers.IO) {
            require(value.isNotBlank()) { "Enter an API key." }
            val bytes = value.trim().toByteArray()
            val (iv, encrypted) =
                try {
                    cipher.encrypt(bytes)
                } finally {
                    bytes.fill(0)
                }
            store.edit {
                it[stringPreferencesKey("${provider.name}_iv")] =
                    Base64.encodeToString(iv, Base64.NO_WRAP)
                it[stringPreferencesKey("${provider.name}_cipher")] =
                    Base64.encodeToString(encrypted, Base64.NO_WRAP)
            }
        }

    suspend fun isConfigured(provider: Provider): Boolean =
        store.data.first()[stringPreferencesKey("${provider.name}_cipher")] != null

    // Only transport clients use this; display APIs expose a Boolean, never a saved key.
    internal suspend fun readForRequest(provider: Provider): String =
        withContext(Dispatchers.IO) {
            val data = store.data.first()
            val iv =
                data[stringPreferencesKey("${provider.name}_iv")]
                    ?: error(
                        "${provider.name.lowercase().replaceFirstChar { it.uppercase() }} key missing. Add it in Settings."
                    )
            val encrypted =
                data[stringPreferencesKey("${provider.name}_cipher")]
                    ?: error("API key missing. Add it in Settings.")
            try {
                val bytes =
                    cipher.decrypt(
                        Base64.decode(iv, Base64.NO_WRAP),
                        Base64.decode(encrypted, Base64.NO_WRAP),
                    )
                try {
                    bytes.toString(Charsets.UTF_8)
                } finally {
                    bytes.fill(0)
                }
            } catch (_: Exception) {
                error("The saved credential cannot be unlocked. Replace it in Settings.")
            }
        }

    suspend fun delete(provider: Provider) {
        store.edit {
            it.remove(stringPreferencesKey("${provider.name}_iv"))
            it.remove(stringPreferencesKey("${provider.name}_cipher"))
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    override fun toString() = "SecureCredentialStore(redacted)"
}
