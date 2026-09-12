package com.oki.core.security

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

internal interface DeletionAuthenticator {
    fun authenticate(title: String, result: (Boolean, String?) -> Unit)

    fun cancel()
}

// Test seam only; production always uses the Android-owned biometric/credential prompt.
internal val LocalDeletionAuthenticator = staticCompositionLocalOf<DeletionAuthenticator?> { null }

@Composable
internal fun rememberDeletionAuthenticator(): DeletionAuthenticator {
    val context = LocalContext.current
    val injected = LocalDeletionAuthenticator.current
    return remember(context, injected) { injected ?: AndroidDeletionAuthenticator(context) }
}

private fun Context.activity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
        else -> null
    }

private class AndroidDeletionAuthenticator(private val context: Context) : DeletionAuthenticator {
    private var prompt: BiometricPrompt? = null

    override fun authenticate(title: String, result: (Boolean, String?) -> Unit) {
        val activity = context.activity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            result(false, "Cannot verify right now. Reopen this screen and try again.")
            return
        }
        // WEAK | DEVICE_CREDENTIAL is supported on older Android versions as well;
        // STRONG | DEVICE_CREDENTIAL is unsupported on API 29 and below.
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        val capability = BiometricManager.from(context).canAuthenticate(authenticators)
        if (capability != BiometricManager.BIOMETRIC_SUCCESS) {
            result(
                false,
                when (capability) {
                    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                        "Set up a phone PIN, password or fingerprint in Android Settings first. Nothing was deleted."
                    else ->
                        "Phone verification is unavailable. Nothing was deleted. Try again later."
                },
            )
            return
        }
        try {
            prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(context),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            authentication: BiometricPrompt.AuthenticationResult
                        ) {
                            result(true, null)
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            result(false, "$message Nothing was deleted.")
                        }
                        // Failed biometric matches remain inside the system prompt for retry/PIN
                        // fallback.
                    },
                )
            prompt!!.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify deletion")
                    .setSubtitle(title)
                    .setAllowedAuthenticators(authenticators)
                    .setConfirmationRequired(true)
                    .build()
            )
        } catch (_: Exception) {
            result(false, "Could not start phone verification. Nothing was deleted.")
        }
    }

    override fun cancel() {
        prompt?.cancelAuthentication()
        prompt = null
    }
}
