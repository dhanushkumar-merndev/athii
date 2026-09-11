# Athii validation

Updated 2026-09-11. This document distinguishes executed checks from device-dependent acceptance work.

## Executed

- Final combined formatting, unit-test, lint, debug, and signed optimized release build completed successfully (Gradle: BUILD SUCCESSFUL).
- 34 JVM unit tests passed: time calculations/DST, reminder lifecycle, same-time identities, channel identities, draft parsing, AES-GCM tamper detection, and primary/retry/fallback behavior.
- Gemini `gemini-3.7-flash`, Groq `openai/gpt-oss-120b`, and Groq `openai/gpt-oss-20b` each returned HTTP 200 with valid response structure in live connectivity checks using owner-provided credentials. No secret or response body was logged. These are connection checks, not a complete live image/chat acceptance test.
- Realme RMX2161 / Android 12: development APK installed and launched successfully without uninstalling or clearing existing app data. Private key bootstrap completion marker observed. User's supplied screenshot also showed a successful chat response.
- Emulator API 35: targeted keyboard regression reproduced before fix, then passed after fix. Footer is absent while chat keyboard is visible; composer remains visible; footer returns after Back.
- Emulator API 35: two Add choices reproduced as separate rows before fix, then equal-width single-row test passed after fix.
- Screenshot inspection confirmed dark styling, white status-bar icons, and separate system-navigation safe space. Final screenshots use Athii branding.

## Final automated run

- JVM: **34 tests, zero failures**.
- Emulator regression runner: **OK (21 tests)**, including one expected skipped private-bootstrap case in the credential-free build; **20 tests executed successfully**.
- Private-bootstrap test rerun against the encrypted personal APK: **OK (1 test)**, proving automatic initialization, preservation of a replaced key, and no reimport after deletion.
- Targeted before/after regression tests reproduced and fixed the keyboard-footer issue, two-row Add choices, and RTL indicator position.
- Formatting check passes; lint reports **zero errors, 31 warnings** (documented categories below).
- Debug private APK installed and launched successfully on Realme RMX2161 / Android 12, preserving user data.
- Signed release installed fresh and cold-launched on API 35 emulator; Settings confirmed both API keys were initialized and masked. APK signature verified (v2); release is not debuggable. Plaintext credential scan of source and APK contents passed.
- Shareable signed release: `artifacts/Athii.apk` (about 3 MB); SHA-256 recorded in `artifacts/SHA256SUMS`.

Reports: `app/build/reports/instrumentation/results.txt`, `app/build/reports/tests/testDebugUnitTest/`, and `app/build/reports/lint-results-debug.html`.

## Remaining acceptance checks

- Live CameraX/gallery image extraction with real doctor/task photos through the complete app review-save flow. Structured draft parsing and review-before-save have automated coverage with fixtures.
- Real-device custom sound audible behavior under normal, silent, and DND modes; emulator tests verify duration validation, channels, no DND bypass, and permission denial.
- Real-device reboot/Doze/OEM battery restrictions and midnight delivery over an actual day. Simulated clock/Room tests cover reset, missed-date recovery, and edit preservation.
- Smoothness at 90/120 Hz and low-memory profiling on additional physical hardware. Compose pager and graphics-layer indicator avoid manual frame loops; no universal frame-rate claim is made.
- A signed personal release cannot replace a debug-signed install without uninstalling it. The phone keeps a data-preserving debug update; the independently signed release is for a fresh install on the friend's device.

## Intentional lint warnings

Dependency upgrade suggestions are retained for the pinned AGP/Kotlin/AndroidX toolchain. `UseKtx` suggestions prefer convenience extensions over equivalent platform calls; these do not indicate incorrect behavior. Lint errors must be zero. The remaining `ObsoleteSdkInt` suggestion is a redundant version guard; `UnusedResources` is the earlier legacy vector icon retained alongside the adaptive A icon. No blanket baseline suppresses correctness errors.

## Personal key distribution

The owner explicitly requested automatic setup with bundled credentials. Opt-in builds include an encrypted generated asset, then re-wrap keys with Android Keystore. Neither `.env` nor plaintext credentials are packaged. The APK has enough public information to decrypt the bootstrap, so this is obfuscation against casual extraction, not protection from determined reverse engineering. Private release uses R8 and disables debugging. Key deletion is respected without silently repopulating keys.

## Doctor filter and transition update

- Replaced separate attendance chips and scrollable filter rows with three equal-width dropdowns: attendance, department, working day.
- Narrow-layout UI test uses a 276 dp content width (320 dp screen minus horizontal padding) and 1.5× text scale; verifies equal aligned widths and all attendance selections, a long department name, and a working day.
- Long labels ellipsize in the row; dropdown options retain full labels and accessibility descriptions retain selected values.
- Add sheet action buttons no longer ripple or switch to disabled colors during dismissal. Duplicate actions remain guarded while the sheet hides.
- Forward navigation slides in from the right; Back slides the current screen out to the right. Bottom-sheet dismissal uses its native downward animation.
