# Athii for Android

A private, native Kotlin/Jetpack Compose app for tasks, local reminders, a doctor directory, and an AI assistant. Android 8.0+ (API 26). Dark charcoal styling is the default, with light/system options, finger-following horizontal tab swipes, one sliding/squeezing footer indicator, directional editor transitions, and safe spacing for status bars, cutouts, navigation bars, and the keyboard.

## Build and run

Open this directory in Android Studio. Use **JDK 17 or 21** for Gradle, Android SDK 36, and Build Tools 36.0.0. The project pins Gradle 8.13 / AGP 8.11.1 and Kotlin 2.1.21. JDK 25 is not supported by this Gradle version; select JDK 21 under Settings → Build Tools → Gradle.

```bash
export JAVA_HOME=/path/to/jdk-21
./scripts/build.sh
```

Artifacts:

- Installable development APK: `app/build/outputs/apk/debug/app-debug.apk`
- Optimized signed personal release APK: `app/build/outputs/apk/release/app-release.apk` (when `.signing.properties` is configured)

The personal release uses a private keystore outside this project, configured by ignored `.signing.properties`. Keep that keystore and its password safe for future updates. Without a local signing configuration, release output is unsigned. Debug signing is only for device development; release and debug signatures are different. `local.properties`, `.env`, signing passwords, generated bootstrap assets, and keystores are excluded from source control.

Run device tests on a dedicated API 35+ emulator:

```bash
./scripts/test-emulator.sh emulator-5554
```

This script explicitly targets the emulator, never another connected device. Test results go to `app/build/reports/instrumentation/results.txt`. JVM results are under `app/build/reports/tests/testDebugUnitTest/`; lint results are under `app/build/reports/`.

## Features

- Tasks: manual creation, editing, completion, confirmed deletion, search, one required start date/time and an optional later same-day end time. New tasks notify at the start; end notifications include the task title. Previously saved reminder offsets remain intact until edited.
- Local reminders: AlarmManager, independent pending intents and notification tags, Mark Done/Snooze actions, exact-access explanation, notification permission recovery, boot/update/time-change restoration.
- Sounds: normal notification mode supports system/default, silent, or validated custom audio of at most 5 seconds. Optional Alarm mode rings inside Athii at the task start, with Stop and Snooze; it uses the device alarm sound/volume and a separate native Android channel. Athii never changes device volume or requests DND bypass. End alerts remain normal notifications.
- Doctors: all requested fields, manual and scanned creation, details, full editing with stable IDs, confirmed deletion, search, department/day/attendance filters, deliberate Present/Absent changes.
- Attendance: local midnight reset, transactional reset-date marker, startup/reboot/timezone recovery, foreground self-healing. Only attendance fields are reset; edited doctor details remain intact.
- Scan: CameraX and system Photo Picker, bounded decoding/EXIF correction/compression, preview, cancel/retry/manual fallback, multiple structured drafts. No extraction can save itself.
- Ask AI: Groq → Gemini fallback with full successful model labels, bounded retry/repair, allowlisted local read tools, and multiple reviewable task/doctor drafts. One “Review all” button opens an editable, scrollable batch; each item has its own Save action and saved state. Valid items survive invalid siblings or a later API outage. History persists locally; opening the app starts a fresh chat and the History icon switches conversations.
- Settings: Keystore-encrypted credentials, separate primary/fallback diagnostics, appearance, reminder settings, clear completed/chat/all-data with confirmation.

## AI setup and privacy

Manage credentials in **Settings → AI connections**:

- Groq image extraction: `qwen/qwen3.6-27b`, then Gemini `gemini-3.5-flash` and `gemini-3.5-flash-lite` as bounded fallbacks. Unavailable providers are skipped; incomplete token-limited responses are not shown as complete batches.
- Chat order: Groq `openai/gpt-oss-120b` → `openai/gpt-oss-20b` → `qwen/qwen3.6-27b` → Gemini `gemini-3.5-flash` → `gemini-3.5-flash-lite`. Each new question starts at the first configured, non-cooling-down model. Tool continuations stay on the successful model and advance if necessary. Authentication failure skips the affected provider, not the independently configured provider.

The usage icon immediately before Send opens all five models, measured input/output/total tokens, API request counts, and provider-reported remaining limits. Counters persist locally and include scans, summaries and diagnostics. Groq token headers describe its per-minute window; request headers describe its daily window. Colored bars and countdowns use those snapshots; elapsed windows are marked stale until another response arrives. Gemini remaining quota is marked unavailable unless reported, with a link to AI Studio and a midnight-Pacific daily-reset countdown. No quota polling consumes extra requests. These are free-tier-capable models, but billing and actual limits are controlled by the configured provider account.

After a reply, a short background request updates the conversation's rolling summary. The next message uses that memory plus the latest full exchange and any unsummarized recent turns. Summary failures leave recent context available; a new question cancels an in-flight summary. Summaries also consume tokens and may omit older detail. Full history remains local and unchanged. Draft tool schemas avoid duplicate single-item definitions, and smaller batches/output reservations reduce free-tier pressure.

Provider references: [Groq rate-limit headers](https://console.groq.com/docs/rate-limits), [Gemini limits and reset policy](https://ai.google.dev/gemini-api/docs/rate-limits), [Gemini chat compatibility](https://ai.google.dev/gemini-api/docs/openai), and [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing).

Existing keys are masked and never redisplayed. Credentials are AES-256-GCM encrypted using a non-exportable Android Keystore key. Normal builds do not package credentials. At the owner's explicit request, a personal build can initialize both keys automatically:

```bash
./scripts/build.sh :app:assembleRelease -PembedAiCredentials=true
```

This opt-in task reads `.env` locally (`GOOGLE_API`/`GEMINI_API_KEY` and `GROK_API`/`GROQ_API_KEY`), creates an AES-GCM encrypted asset bound to the signing certificate, and never generates plaintext keys into Kotlin, BuildConfig, or XML. First launch re-encrypts them using Android Keystore and records that setup was applied. Replacement keys remain intact; deleting credentials does not silently restore bundled keys. Clearing Android app storage or reinstalling resets the setup marker.

**APK encryption is obfuscation, not a guarantee of secrecy.** The certificate is public and the client contains the decryption logic, so a determined reverse engineer can recover bundled credentials. Share the personal APK only with its intended recipient. The signed, optimized release removes debugging support and applies R8 obfuscation. No backend is introduced.

There is no login, backend, remote database, sync, analytics, advertising, or telemetry. Android backup and device transfer are disabled. Network transport is limited to Google Gemini and Groq HTTPS endpoints, with redirects disabled and no request/body logging. Scanning sends the selected prepared image. Chat sends the current conversation's recent messages and review states, plus up to 20 matching records per search (10 upcoming tasks), with bounded tool rounds. Other conversations are excluded. Previous answers are context; fresh local queries determine current records. Chat history is stored in an atomic app-private file and can be cleared in Settings.

AI extraction and chat are fallible. Every extracted field is explicitly marked for review; unknown values stay blank. The AI tool layer exposes no save/delete/attendance operations. A task suggestion only opens an editable draft.

## Reminder and time semantics

Task due dates are stored as absolute epoch timestamps, together with the timezone at creation. Traveling changes their local display, not the actual instant of a scheduled appointment. Edit the task to move that instant. Nonexistent DST wall times are rejected; ambiguous fall-back times use the earlier offset. Snooze preserves the due timestamp.

Exact timing requires Android's Alarms & Reminders access. If denied, the app saves and schedules an inexact fallback, with an explanation in the editor and settings. Daily reset may likewise be delayed until Android dispatches the alarm; attendance is normalized before repository retrieval and every 30 seconds while foregrounded. The transactional Room maintenance marker prevents duplicate resets from undoing today's manual attendance.

Normal Android notification-channel semantics control sound, silent mode, DND, and user channel overrides. Force-stop prevents Android from delivering alarms until the app is reopened; this is an Android platform restriction. Boot recovery restores future scheduled alarms and preserves snoozed timestamps. Reminders that expired while the device was off are not replayed as new alerts.

Custom sounds are validated and copied into private storage, so they survive removal of the original document. Old copies are retained because Android may still reference an older channel; Delete all app data removes them. Prepared scan images are cache-only and old images are pruned during the next scan.

## Architecture

`core/` owns Room/DataStore, encrypted credentials, network transport, notification delivery, scheduling, and shared theme/components. Feature packages own repositories, feature-specific ViewModels, and Compose screens. Screens do not access DAOs or HTTP clients. Room schema versions are exported under `app/schemas/`; migrations preserve existing tasks and doctors without destructive fallback.

## Verification

See [docs/VALIDATION.md](docs/VALIDATION.md) for executed checks and remaining device-dependent checks. Dependency-upgrade lint suggestions are intentionally retained: the pinned compatible toolchain is reproducible. KTX convenience suggestions do not affect behavior. No lint errors are accepted.

Implementation references: [Android alarm scheduling](https://developer.android.com/develop/background-work/services/alarms/schedule), [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output), [Groq local tool calling](https://console.groq.com/docs/tool-use/local-tool-calling), and [AGP compatibility](https://developer.android.com/build/releases/agp-8-11-0-release-notes).
