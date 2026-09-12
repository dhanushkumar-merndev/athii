# Deletion verification

All manual actions using the shared deletion confirmation now require Android-owned biometric or device-credential authentication before executing: individual task/doctor deletion, clear completed tasks, clear chat history, remove an API key, and delete all app data.

The confirmation remains visible while verification is pending. A successful result approves only the exact action captured when Verify & delete was pressed, once. Cancellation, errors, unavailable authentication, navigation away and disposal invalidate the pending action; late callbacks cannot approve a different deletion. No verification result is persisted or reused.

AndroidX Biometric 1.1.0 uses `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` for compatibility with supported older Android versions. The main activity is now a FragmentActivity. No biometric templates, phone PINs or passwords are read or stored by Athii. See [Android's biometric prompt guidance](https://developer.android.com/identity/sign-in/biometric-auth).

Boundaries:

- A device without usable authentication cannot perform these manual deletions until a screen lock is configured.
- This is an in-app confirmation safeguard, not protection against Android Settings → Clear storage, uninstalling, or a rooted device.
- Existing scheduled completed-task cleanup is unchanged; this safeguard covers manual Delete/Clear confirmations, not background cleanup under a previously selected retention policy.

Verification: 94 JVM tests passed, including single-use approval, cancellation and stale-result tests. Two emulator UI tests passed with an injected authenticator and counter-only test actions (no real records deleted). They cover waiting for verification, failure, retry, cancellation and duplicate success callbacks. Physical fingerprint/PIN enrollment and success require the device owner's own verification and were not automated.
