package com.oki.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Optional personal-build bootstrap, explicitly requested by the owner. APK encryption deters
 * plaintext scanning; it is not protection against a determined reverse engineer. On first launch
 * credentials are re-encrypted using this device's non-exportable Keystore key.
 */
class EmbeddedCredentialBootstrap(
    private val context: Context,
    private val store: SecureCredentialStore,
) {
    suspend fun applyIfAvailable() =
        withContext(Dispatchers.IO) {
            // Deliberately retained when the user deletes credentials/all data, to respect that
            // deletion.
            val marker = File(context.noBackupFilesDir, "credential_bootstrap_applied")
            if (marker.exists()) return@withContext
            val envelope =
                try {
                    context.assets.open("athii-bootstrap.bin").use { it.readBytes() }
                } catch (_: java.io.FileNotFoundException) {
                    return@withContext
                }
            require(envelope.size >= 44) {
                "The private AI setup could not be read. Add keys in Settings."
            }
            @Suppress("DEPRECATION")
            val certificate =
                if (Build.VERSION.SDK_INT >= 28) {
                    context.packageManager
                        .getPackageInfo(
                            context.packageName,
                            PackageManager.GET_SIGNING_CERTIFICATES,
                        )
                        .signingInfo!!
                        .apkContentsSigners
                        .first()
                        .toByteArray()
                } else {
                    context.packageManager
                        .getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                        .signatures!!
                        .first()
                        .toByteArray()
                }
            val salt = envelope.copyOfRange(0, 16)
            val iv = envelope.copyOfRange(16, 28)
            val keyBytes =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        certificate +
                            salt +
                            "athii-bootstrap-v1:${context.packageName}".toByteArray()
                    )
            val cipher = CredentialCipher { SecretKeySpec(keyBytes, "AES") }
            val plaintext =
                try {
                    cipher.decrypt(iv, envelope.copyOfRange(28, envelope.size))
                } finally {
                    keyBytes.fill(0)
                }
            try {
                val credentials =
                    Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject
                for (provider in Provider.entries) {
                    val value = credentials[provider.name]?.jsonPrimitive?.contentOrNull
                    if (!value.isNullOrBlank() && !store.isConfigured(provider))
                        store.save(provider, value)
                }
                marker.writeText("1")
            } finally {
                plaintext.fill(0)
                envelope.fill(0)
            }
        }
}
