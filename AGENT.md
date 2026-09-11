# AGENT.md — Local Android Tasks + Doctor Assistant

## 0. Mission

Build a **single-user Android application** in **Kotlin** for personal use. The app must be fast, polished, offline-first, and store all user data locally on the Android device. There is **no account system, no remote database, no Firebase database, no Supabase, no backend server, and no sync service**.

The application has two primary feature domains:

1. **Tasks & Reminders**
   - Create, review, edit, complete, reschedule, and delete tasks.
   - Schedule local Android notifications for task reminders.
   - Support a configurable default reminder offset, defaulting to 5 minutes before the task time.
   - Allow optional custom notification sound up to 5 seconds.
   - Respect Android silent mode and Do Not Disturb; never force audio through silent/DND.
   - Support image capture/upload so AI can extract task details into a draft that the user reviews before saving.

2. **Doctor Directory & AI Assistant**
   - Store doctor information locally.
   - Add doctors manually or scan an image/card/board and let AI extract a draft.
   - Let the user review/edit before saving.
   - Show all doctors in a fast searchable list.
   - Each doctor has a daily attendance state: **Present** or **Absent**.
   - Default state is **Present**.
   - User can manually switch any doctor between Present and Absent.
   - At **12:00 AM local device time every day**, reset every doctor to **Present** automatically.
   - AI chat can answer questions using the locally stored tasks and doctor data.

The app must feel like a native premium Android utility: simple, smooth, animated, quick to open, and easy to use with one hand.

---

## 1. Non-Negotiable Product Rules

### 1.1 Local-first

All persistent application data stays on the device:

- tasks
- reminder settings
- doctors
- doctor attendance state
- app preferences
- optional local chat history
- selected custom reminder sound reference
- encrypted API credentials

Network access is allowed **only** for AI API calls to Google Gemini and Groq.

Do not add:

- login
- registration
- user accounts
- remote database
- cloud synchronization
- analytics SDKs
- ads
- telemetry
- Firebase Cloud Messaging
- push server
- background tracking

### 1.2 “Push notification” behavior in this app

The user calls them push notifications, but because this app has no server/backend, implement them as **local scheduled Android notifications**.

They must work without the app being open.

Task reminders must not depend on an internet connection.

### 1.3 Review-before-save is mandatory

AI must never directly write extracted image data into persistent task/doctor storage.

Every AI extraction follows:

```text
Image capture/gallery
    -> AI extraction
    -> Draft object
    -> Review/Edit screen
    -> User taps Save
    -> Persist locally
```

The same Review/Edit screen should also be used for manual creation where practical, so there is one consistent save path.

### 1.4 Never invent missing medical/doctor information

If AI cannot confidently read a doctor field, return it as null/empty and visually flag it for review.

Never infer qualifications, timings, room numbers, phone numbers, working days, hospital names, or departments from weak evidence.

---

## 2. Technology Stack

Use a modern native Android stack.

### Required

- Kotlin
- Jetpack Compose
- Material 3
- Coroutines + Flow
- Navigation Compose
- ViewModel
- Repository pattern
- Room for **local-only on-device structured storage**
- DataStore for app preferences
- Android Keystore for protecting API credentials
- AlarmManager for exact user reminder alarms
- BroadcastReceiver for alarm delivery/reboot recovery
- CameraX for in-app camera capture
- Android system Photo Picker for gallery selection
- OkHttp or Retrofit for network transport
- Kotlin serialization or Moshi for JSON

Room is allowed because it is an **on-device local database only**. There must be no remote DB.

### Optional UI libraries

Keep dependencies minimal.

Allowed when useful:

- Lottie Compose for small success/scanning animations
- Coil for local/image preview loading

Do not use a heavy cross-platform framework.

Do not use Flutter, React Native, Ionic, or a WebView-based UI.

---

## 3. Architecture

Use a clean feature-oriented package structure similar to:

```text
app/
  core/
    ai/
    notifications/
    security/
    storage/
    ui/
    util/
  feature/
    tasks/
      data/
      domain/
      ui/
    doctors/
      data/
      domain/
      ui/
    assistant/
      data/
      domain/
      ui/
    settings/
      ui/
```

Prefer small focused classes instead of giant managers.

Suggested boundaries:

- `TaskRepository`
- `DoctorRepository`
- `SettingsRepository`
- `ReminderScheduler`
- `NotificationPublisher`
- `DailyDoctorResetScheduler`
- `GeminiVisionClient`
- `GroqChatClient`
- `AiRouter`
- `SecureCredentialStore`
- `LocalAssistantToolExecutor`

Do not let Compose screens call Room DAOs or HTTP clients directly.

---

## 4. Main Navigation

Use a bottom navigation bar with three primary destinations:

1. **Tasks**
2. **Doctors**
3. **Ask AI**

Settings opens from a top-right settings icon.

Use a prominent but tasteful add action where appropriate.

Suggested conceptual layout:

```text
Tasks        Doctors        Ask AI
  ●              ●              ●

             + Add
```

Do not overcrowd the UI.

---

## 5. Visual Design & Motion

### 5.1 Style

Create a clean contemporary Material 3 interface with:

- excellent spacing
- large readable typography
- clear hierarchy
- rounded but not excessively bubbly components
- edge-to-edge layouts
- dark mode and light mode support
- accessible contrast
- one-handed usability

Avoid visual clutter.

### 5.2 Motion

Animations must feel premium but lightweight.

Use Compose-native animation APIs first:

- `AnimatedContent`
- `AnimatedVisibility`
- `animate*AsState`
- spring animations
- shared transitions only where they genuinely improve navigation
- `graphicsLayer` for performant visual transforms

Use Lottie only for a few small moments such as:

- image scanning
- successful save
- empty assistant processing state if desired

Do not animate every element.

Respect system “Remove animations” / reduced-motion accessibility behavior where possible.

### 5.3 Performance

Target smooth behavior on modern Android devices at 60/90/120 Hz.

Requirements:

- avoid unnecessary recomposition
- use stable keys in lazy lists
- do not perform disk/network work on the main thread
- use immutable UI state
- paginate only if actually needed; local doctor/task collections are expected to be small
- use `derivedStateOf` where useful
- avoid giant bitmap decoding
- resize/compress camera images before AI upload

---

# PART A — TASKS & REMINDERS

## 6. Task Data Model

Create a local task entity containing at least:

```text
id: Long / UUID

title: String
notes: String?

dueDateTime: Instant or robust local date-time representation

timeZoneIdAtCreation: String

reminderEnabled: Boolean
reminderOffsetMinutes: Int
scheduledReminderAt: timestamp?

isCompleted: Boolean
completedAt: timestamp?

source: MANUAL | IMAGE_SCAN

createdAt
updatedAt
```

Handle timezone changes correctly.

Do not store only a display string such as `"5:30 PM"`.

---

## 7. Create Task Flow

When the user taps Add Task, offer:

- **Create manually**
- **Scan image**

### Manual

User can enter:

- task title
- notes (optional)
- date
- time
- reminder enabled
- reminder offset

Then show/reuse the Task Review screen.

### Image scan

Allow:

- CameraX capture
- Android Photo Picker

After image selection:

1. show preview
2. call Gemini
3. parse schema-constrained response
4. create an in-memory Task Draft
5. open Task Review
6. user edits if needed
7. user taps Save

Never schedule a reminder until Save succeeds.

---

## 8. Task Review Screen

Display editable fields:

- Title
- Notes
- Date
- Time
- Reminder toggle
- Reminder offset

Show clear validation.

Examples:

- Empty title -> block Save
- Invalid date/time -> block Save
- AI uncertainty -> show a subtle “Please review” indication

Primary action:

**Save Task**

Secondary action:

**Cancel**

---

## 9. Reminder Offset

Global setting:

```text
Default reminder: 5 minutes before
```

User can change it to common presets and custom value.

Suggested presets:

- At time
- 5 min before
- 10 min before
- 15 min before
- 30 min before
- 1 hour before
- Custom

Each task may override the global default.

Example:

```text
Task time: 5:30 PM
Reminder offset: 5 minutes
Notification time: 5:25 PM
```

If the resulting reminder time is already in the past, explain it clearly and let the user choose:

- notify now
- choose another offset
- save without reminder

Do not silently schedule an invalid past alarm.

---

## 10. Android Reminder Scheduling

Use `AlarmManager` for user-requested reminder timing.

For reminders requiring exact timing, use an exact alarm implementation such as `setExactAndAllowWhileIdle()` where permitted.

For Android versions requiring exact-alarm special access:

- declare the appropriate manifest permission
- check whether exact alarms can be scheduled
- show a friendly explanation before opening Android’s Alarms & Reminders access screen
- handle denial gracefully

Never crash if permission is unavailable.

### Alarm identity

Every task reminder must have its own stable unique alarm identity and unique notification identity.

Editing a task:

```text
cancel old alarm
update local task
schedule new alarm
```

Deleting a task:

```text
cancel alarm
remove notification if active
remove local task
```

Completing a task should cancel any future reminder unless product behavior explicitly indicates otherwise.

---

## 11. Same-Time Notifications

This is required.

If two or more tasks have reminders at the same exact time, show **all of them**.

Example:

```text
5:25 PM
- Call hospital
- Send report
```

Do not reuse the same notification ID.

Each task gets its own notification ID.

Android may visually group notifications, but no task notification may overwrite another.

Optional:

Use one notification group for task reminders while preserving individual child notifications.

---

## 12. Notification Content

A task reminder should contain:

- task title
- due time
- optional short notes preview

Suggested actions:

- Mark Done
- Snooze
- Open

If Snooze is implemented, keep it simple:

- 5 min
- 10 min
- 30 min

Snooze must create a new one-time local alarm without changing the original task due time.

---

## 13. Silent Mode / DND Behavior

Critical rule:

**Never bypass Android silent mode or Do Not Disturb.**

Do not request DND policy access merely to force sounds.

Do not use MediaPlayer/ExoPlayer as a hack to force reminder audio.

Notification sound must be delivered using normal Android notification-channel semantics so the OS remains in control.

If the phone is silent or DND suppresses notification sound, the reminder should still appear visually but must not force audio.

---

## 14. Notification Sound Settings

Settings options:

```text
Reminder sound
- System default
- Custom sound
- Silent
```

Default:

**System default**

### Custom sound

Allow the user to choose a local audio file.

Requirements:

- validate that it is an audio file
- inspect duration
- maximum accepted duration: 5 seconds
- show error if longer than 5 seconds
- persist the content URI permission when supported
- retain selected sound after app restart/device reboot
- offer preview
- offer Remove/Reset

### Android notification-channel constraint

On Android 8+, notification sound is associated with a notification channel and channel behavior cannot be arbitrarily mutated after creation.

Implement channel versioning.

For example:

```text
reminders_default_v1
reminders_custom_<hash_or_version>
reminders_silent_v1
```

When the user changes sound configuration:

- create/reuse the correct channel
- use that channel for future notifications
- do not attempt unreliable per-notification sound mutation
- optionally clean obsolete app-created channels when safe and not user-customized

Use notification audio attributes appropriate for notifications.

Never enable DND bypass.

---

## 15. Reboot and Time Changes

Android alarms do not survive every reboot scenario automatically.

Implement receivers/logic to restore scheduled reminders after:

- device boot
- app update if necessary
- timezone change
- manual device time change when relevant
- exact-alarm permission regained

On restore:

1. query incomplete tasks with future reminders
2. recompute reminder timestamps safely
3. reschedule

Do not create duplicate alarms.

---

# PART B — DOCTORS

## 16. Doctor Data Model

Store at least:

```text
id: Long / UUID

doctorName: String
qualification: String?
department: String?
roomOrOpdNumber: String?
availableFrom: LocalTime?
availableUntil: LocalTime?
workingDays: Set<DayOfWeek>
hospitalOrClinic: String?
phone: String?
notes: String?

attendanceStatus: PRESENT | ABSENT
attendanceChangedAt: timestamp?

source: MANUAL | IMAGE_SCAN

createdAt
updatedAt
```

Exact user-requested doctor fields:

- Doctor name
- Qualification
- Department
- Room / OPD number
- Available from
- Available until
- Working days
- Hospital / clinic
- Optional phone
- Notes

`doctorName` is required.

Other fields may be blank if unknown.

---

## 17. Add Doctor Flow

When user taps Add Doctor:

- Add manually
- Scan image

### Manual

Open Doctor Review/Edit with blank fields.

### Scan image

Supported sources:

- Camera
- Gallery Photo Picker

Examples of useful images:

- doctor visiting card
- hospital board
- OPD timing board
- printed schedule
- screenshot

Flow:

```text
Image
 -> Gemini extraction
 -> Doctor Draft
 -> Review/Edit
 -> Save locally
```

Never auto-save.

---

## 18. Doctor Review Screen

Editable fields:

- Doctor name
- Qualification
- Department
- Room / OPD number
- Available from
- Available until
- Working days
- Hospital / clinic
- Phone
- Notes

Attendance defaults to:

**Present**

AI-extracted uncertain/empty fields should be clearly reviewable.

Save only when the user presses Save Doctor.

---

## 19. Doctor List / See All Doctors

A dedicated Doctors screen must show all saved doctors.

Provide:

- Search
- Present/Absent status
- Department filter
- Working-day filter
- Present-only / Absent-only filter
- clear empty state

Each card/list row should show concise information such as:

```text
Dr. Example Name               PRESENT
Dermatology
Room 204 · 5:00 PM–8:00 PM
Mon, Tue, Thu, Sat
```

Tapping a doctor opens Doctor Details.

Use green/red only as supplemental status cues; also render explicit text/icons so color is not the only status signal.

---

## 20. Doctor Attendance

Every doctor has exactly two daily attendance states:

- **Present**
- **Absent**

### Default

When a doctor is created:

```text
attendanceStatus = PRESENT
```

### Manual toggle

From doctor list and doctor detail, user can mark:

```text
Present <-> Absent
```

Persist the current state immediately locally.

Use a quick but deliberate interaction that avoids accidental changes.

### Daily reset

At **12:00 AM local device time every day**, automatically set **every saved doctor to PRESENT**.

Required behavior:

```text
11:59 PM
Dr A = Absent
Dr B = Present

12:00 AM local time
Dr A = Present
Dr B = Present
```

This reset is local-only.

Do not delete doctor records.

Do not change doctor timings or any other doctor data.

### Reset scheduler

Implement `DailyDoctorResetScheduler`.

Schedule the next local midnight and, when triggered:

1. update all doctors to `PRESENT`
2. clear/update attendance timestamps as appropriate
3. schedule the following local midnight

Also make the logic self-healing:

- on app start, check the last successful reset date
- if today’s reset was missed because the device was off, immediately normalize attendance to PRESENT once
- after reboot, schedule the next midnight reset
- after timezone/time changes, recalculate next local midnight

Persist something like:

```text
lastDoctorAttendanceResetLocalDate
```

This prevents missed or duplicate reset logic.

The reset does not need to show a notification.

---

## 21. Doctor Details

Show:

- name
- Present/Absent status
- qualification
- department
- hospital/clinic
- room/OPD
- availability time
- working days
- phone
- notes

Actions:

- Edit
- Mark Present/Absent
- Delete

If phone exists, optionally provide a Call action that launches the system dialer only after explicit user tap.

Do not place calls automatically.

### 21.1 Doctor Editing

Every saved doctor record must be fully editable at any time. The Edit action must open the same Doctor Review/Edit form used during creation, prefilled with the current saved values.

The user must be able to modify:

- Doctor name
- Qualification
- Department
- Room / OPD number
- Available from
- Available until
- Working days
- Hospital / clinic
- Optional phone
- Notes
- Present / Absent status

Editing must preserve the doctor's stable local ID. On Save, update only that doctor record and refresh the list/detail UI immediately. On Cancel/Back without saving, preserve the previous stored values.

The automatic midnight reset affects only `attendanceStatus`; it must never overwrite edited doctor details.

### 21.2 Doctor Deletion

Every saved doctor record must be deletable from Doctor Details and may also expose a contextual delete action from the doctor list.

Before deleting, show a confirmation dialog such as:

```text
Delete doctor?
This will permanently remove Dr. <Name> from this device.

Cancel    Delete
```

Rules:

- Cancel leaves the doctor unchanged.
- Confirm permanently removes only that doctor from local storage.
- Deletion must immediately disappear from all doctor lists/search/filter results.
- Any cached assistant context must not keep presenting a deleted doctor as current local data.
- Midnight attendance reset must safely ignore deleted records.
- Deleting one doctor must never alter any other doctor.
- Optional reliable Undo snackbar is allowed, but only if deletion semantics remain correct.

---

# PART C — AI IMAGE EXTRACTION

## 22. Gemini Model

Use:

```text
gemini-3.7-flash
```

Purpose:

- task image understanding/extraction
- doctor card/board/schedule image extraction

Gemini is not the general chat engine in this app unless fallback behavior is intentionally added later.

### Required extraction behavior

Use structured output / schema-constrained JSON.

Do not ask Gemini for prose and then regex parse it.

---

## 23. Task Extraction Schema

Gemini should return a structure conceptually equivalent to:

```json
{
  "title": "string or null",
  "notes": "string or null",
  "date": "YYYY-MM-DD or null",
  "time": "HH:mm or null",
  "reminderOffsetMinutes": 5,
  "confidence": {
    "title": 0.0,
    "date": 0.0,
    "time": 0.0
  }
}
```

Rules:

- Do not fabricate missing dates/times.
- If an image says “tomorrow” and the extraction request includes the current local date/time/timezone, resolve it carefully, but show it for user review.
- If multiple tasks are detected, support returning multiple drafts if implementation remains clean; otherwise explicitly ask user which detected item to create.
- The final persisted record always comes from the user-approved Review screen.

---

## 24. Doctor Extraction Schema

Gemini should return conceptually:

```json
{
  "doctorName": null,
  "qualification": null,
  "department": null,
  "roomOrOpdNumber": null,
  "availableFrom": null,
  "availableUntil": null,
  "workingDays": [],
  "hospitalOrClinic": null,
  "phone": null,
  "notes": null,
  "confidence": {
    "doctorName": 0.0,
    "qualification": 0.0,
    "department": 0.0,
    "roomOrOpdNumber": 0.0,
    "timing": 0.0,
    "workingDays": 0.0,
    "hospitalOrClinic": 0.0,
    "phone": 0.0
  }
}
```

Normalize days to enum-like values:

```text
MONDAY
TUESDAY
WEDNESDAY
THURSDAY
FRIDAY
SATURDAY
SUNDAY
```

Never assume a doctor works every day unless the image explicitly indicates it.

---

## 25. Image Preparation

Before uploading an image to Gemini:

- correct orientation using EXIF
- resize to a sensible maximum dimension
- compress enough to reduce latency/data use without making text unreadable
- never store a full-resolution duplicate unnecessarily
- strip irrelevant metadata when practical

Show upload/processing state.

Allow Cancel.

On AI failure, keep the image preview and offer:

- Retry
- Enter manually

---

# PART D — AI ASSISTANT

## 26. Groq Models

Primary text model:

```text
openai/gpt-oss-120b
```

Fallback text model:

```text
openai/gpt-oss-20b
```

Use Groq for natural-language questions about locally stored task and doctor information.

---

## 27. AI Assistant Principle

Do not upload the entire local database for every question.

The assistant must use a controlled local retrieval/tool layer.

Suggested local tools:

```text
searchDoctors(query, department, day, attendance)
getDoctorById(id)
getDoctorsAvailableOn(day, time?)
getPresentDoctors()
getAbsentDoctors()

searchTasks(query, from, to, completed?)
getUpcomingTasks(limit)
getTasksOn(date)
getTaskById(id)
```

The LLM decides which local tool/query it needs.

Android executes the query locally.

Only the minimum matching records are included in the model context.

---

## 28. Example Assistant Questions

The chatbot should handle queries like:

```text
Which dermatology doctors are present today?

Who is available Tuesday evening?

What room is Dr. Rao in?

Show absent doctors.

What tasks do I have tomorrow?

Do I have anything at 5:30 PM?

When is my next reminder?
```

The assistant may answer only from available local data unless the user explicitly asks a general question.

If the local data does not contain an answer, say that clearly instead of inventing one.

---

## 29. AI Chat Actions

For the first version, keep AI mostly read-oriented.

If chat understands an intent such as:

> Create a task tomorrow at 5:30 PM to call Dr. X

Do **not** silently create it.

Instead:

```text
AI -> Task Draft -> Task Review -> user taps Save
```

Likewise, any AI-suggested doctor changes should require a Review/Edit confirmation.

---

## 30. Groq Fallback Router

Implement an explicit `AiRouter`.

### Primary flow

```text
request
  -> openai/gpt-oss-120b
  -> success
  -> return result
```

### Fallback conditions

Fallback to `openai/gpt-oss-20b` for transient/model-capacity problems such as:

- HTTP 429 after respecting any small retry policy / Retry-After
- model unavailable
- selected 5xx responses
- network timeout where a single retry is appropriate
- malformed model result after one schema repair attempt when applicable

### Do not fallback for

- invalid API key / 401
- forbidden / account permission failure
- clearly invalid request caused by app bug
- no internet connection

These failures would generally affect both models and fallback would only waste requests.

### Retry discipline

Avoid retry storms.

Suggested behavior:

```text
Primary request
 -> one bounded retry only when justified
 -> fallback model
 -> return success or user-friendly failure
```

Honor `Retry-After` where provided.

---

## 31. Fallback Tests

The fallback must be tested before considering AI integration complete.

Create deterministic tests using a fake/mock Groq transport.

At minimum test:

1. Primary returns 200 -> fallback is NOT called.
2. Primary returns 429 -> fallback is called.
3. Primary returns 503 -> fallback is called.
4. Primary times out -> bounded retry/fallback policy is followed.
5. Primary returns 401 -> fallback is NOT called.
6. Primary succeeds with valid JSON/schema -> response is parsed.
7. Primary malformed schema -> repair/fallback policy works as designed.
8. Both models fail -> user receives a clean error and app does not crash.
9. Local data is not modified due to a failed assistant request.

Also add a Settings diagnostic action:

```text
Test Groq Connection
```

It should show separately:

- Primary model: Passed/Failed
- Fallback model: Passed/Failed

This is the real API connectivity check and requires the user’s valid Groq API key and internet connection.

Never ship a developer API key merely to make tests pass.

---

# PART E — API KEYS & SECURITY

## 32. No Hard-Coded Secrets

Do not place real API keys in:

- Kotlin source
- `strings.xml`
- `BuildConfig`
- Gradle files committed to the project
- assets
- raw resources
- Git
- screenshots
- logs

Even a personal APK can be reverse engineered.

---

## 33. API Key Setup

Settings > AI Configuration:

```text
Gemini API Key
Groq API Key
```

Actions:

- Save
- Replace
- Delete
- Test connection

Mask existing values.

Never show the entire saved key again after storage.

---

## 34. Credential Protection

Use Android Keystore.

Recommended design:

1. Generate a non-exportable Android Keystore AES key.
2. Encrypt API credentials using AES-GCM.
3. Store only ciphertext + IV/tag metadata in app-private storage/DataStore.
4. Decrypt only in memory immediately before an API request.
5. Do not log decrypted keys.

Optionally require device authentication before revealing/replacing credentials, but do not make this mandatory for v1 unless it harms usability.

The goal is to make accidental extraction harder while acknowledging that a client-only app can never provide the same secret protection as a backend.

---

# PART F — LOCAL STORAGE

## 35. Room

Use Room only as an on-device implementation detail.

Suggested tables:

```text
tasks
doctors
optional_chat_messages
```

Do not add unnecessary relational complexity.

Use migrations; never use destructive migration in release builds without explicit justification.

---

## 36. DataStore

Use DataStore for settings such as:

```text
defaultReminderOffsetMinutes = 5
reminderSoundMode
customSoundUri
activeNotificationChannelId
lastDoctorAttendanceResetLocalDate
appearancePreference
optionalChatHistoryEnabled
```

Encrypted API credential ciphertext may be stored in app-private preferences only when encryption is provided by the Keystore-based credential layer.

---

# PART G — SETTINGS

## 37. Settings Screen

Sections:

### Reminders

- Default reminder offset
- Reminder sound: System / Custom / Silent
- Custom sound picker
- Sound preview
- Test notification
- Exact alarm permission status

### AI

- Gemini API key
- Test Gemini
- Groq API key
- Test Groq Primary
- Test Groq Fallback

### Appearance

- System
- Light
- Dark

### Data

- Clear completed tasks
- Clear chat history
- Export local data (optional future feature)
- Delete all app data with confirmation

### About

- App version
- Local-first privacy note

---

# PART H — PERMISSIONS

## 38. Permission Philosophy

Request the minimum permissions possible and only when needed.

Potential permissions/special access:

- POST_NOTIFICATIONS on Android versions that require runtime notification permission
- CAMERA only when the user chooses Camera
- SCHEDULE_EXACT_ALARM / exact alarm special access when applicable
- RECEIVE_BOOT_COMPLETED for rescheduling reminders/reset logic
- INTERNET for AI calls

Use Android Photo Picker instead of broad storage/media read permission where possible.

Do not request:

- contacts
- location
- microphone
- call logs
- SMS

unless a future explicitly approved feature requires it.

---

# PART I — OFFLINE BEHAVIOR

## 39. Offline Requirements

The following must work without internet:

- open app
- view tasks
- add/edit/delete tasks manually
- scheduled reminder notifications
- mark tasks complete
- view doctors
- add/edit/delete doctors manually
- mark Present/Absent
- midnight attendance reset
- search local tasks/doctors
- app settings not dependent on cloud

These require internet:

- Gemini image extraction
- Groq chat
- API connectivity tests

When offline, AI screens must fail gracefully and never block access to local features.

---

# PART J — ERROR HANDLING

## 40. Network Errors

Display human-readable states such as:

- No internet connection
- Gemini key missing
- Groq key missing
- API key rejected
- Daily/free quota reached
- AI service temporarily unavailable
- Image could not be read

Never display raw stack traces to the user.

Log debug details only in debug builds, and redact secrets.

---

## 41. Data Safety

Every destructive action requires appropriate confirmation:

- delete task
- delete doctor
- delete API key
- delete all data

Undo snackbars are encouraged for task/doctor deletion if implementation remains reliable.

---

# PART K — TESTING

## 42. Unit Tests

Required coverage areas:

### Tasks

- reminder timestamp calculation
- default 5-minute offset
- custom offset
- past reminder validation
- edit cancels/replaces old alarm identity
- delete cancels alarm

### Notifications

- unique notification IDs for same-time tasks
- correct notification channel selected
- custom sound channel versioning
- silent channel

### Doctors

- new doctor defaults to PRESENT
- manual PRESENT -> ABSENT
- manual ABSENT -> PRESENT
- midnight reset sets all doctors PRESENT
- missed-midnight recovery on next app start
- timezone change recomputes next reset

### AI

- Gemini response parsing
- invalid/missing extraction fields
- Groq fallback tests defined above
- local tool query filtering
- assistant cannot mutate data without review

### Security

- credential encryption round trip
- encrypted credential store never returns plaintext through debug/display APIs
- logs redact credentials

---

## 43. Instrumented / UI Tests

Cover critical flows:

1. Create manual task -> review -> save.
2. Edit task time -> old reminder removed/new reminder scheduled.
3. Delete task -> reminder removed.
4. Two tasks same reminder time -> both exist with unique notification IDs.
5. Add doctor manually -> status Present.
6. Mark doctor Absent -> UI updates.
7. Trigger simulated daily reset -> doctor returns to Present.
8. Edit every doctor field -> save -> reopen -> updated values persist and ID is unchanged.
9. Cancel doctor edit -> previous values remain unchanged.
10. Delete doctor -> confirmation -> cancel keeps record; confirm removes record.
11. Deleted doctor no longer appears in local search/filter/assistant retrieval.
12. AI task image draft -> review -> save.
13. AI doctor image draft -> review -> save.
14. Notification permission denied -> app explains but does not crash.
15. Exact alarm access denied -> app provides recovery path.
16. API key missing -> AI screen shows setup action.

Use test doubles for AlarmManager/network where necessary.

---

# PART L — ACCEPTANCE CRITERIA

## 44. Tasks Acceptance

The feature is complete only if:

- user can create tasks manually
- user can scan an image and receive an editable draft
- task date/time can be edited later
- task can be deleted
- default reminder is 5 minutes before
- user can change global default reminder offset
- user can override reminder offset per task
- local notification fires without app being open
- silent/DND is respected
- custom <=5 second sound can be configured
- same-time task reminders both appear
- reboot recovery reschedules future reminders

---

## 45. Doctors Acceptance

The feature is complete only if:

- user can manually add a doctor
- user can scan a doctor image and receive an editable draft
- all requested doctor fields are supported
- Doctors screen displays all saved doctors
- search works
- every saved doctor can be reopened and fully edited
- saving an edit updates only that doctor and preserves its stable local ID
- cancelling an edit preserves the previous saved values
- every saved doctor can be deleted
- doctor deletion requires explicit confirmation
- deleting one doctor does not modify other doctors
- deleted doctors disappear from local search, filters, detail views, and future AI retrieval
- user can mark any doctor Present or Absent
- every new doctor starts Present
- all doctors automatically reset to Present at local 12:00 AM
- midnight reset changes attendance only and never overwrites doctor details
- missed midnight while phone was off is corrected on next app start
- reboot/timezone changes do not permanently break reset scheduling

---

## 46. AI Acceptance

The feature is complete only if:

- Gemini model is `gemini-3.7-flash`
- task images can be converted to structured drafts
- doctor images can be converted to structured drafts
- Groq primary model is `openai/gpt-oss-120b`
- Groq fallback is `openai/gpt-oss-20b`
- fallback behavior has deterministic automated tests
- Settings can test both Groq models separately using the user’s own key
- chatbot can answer questions from local task/doctor data
- chatbot does not hallucinate unavailable local records
- AI cannot directly save destructive/mutating changes without user review

---

# PART M — IMPLEMENTATION ORDER

## 47. Recommended Build Sequence

Implement in this order:

### Phase 1 — Foundation

- new Kotlin/Compose project
- theme
- navigation
- Room
- DataStore
- repository interfaces
- settings shell

### Phase 2 — Tasks

- task entity/DAO/repository
- task list
- task review/editor
- create/edit/delete/complete

### Phase 3 — Notifications

- channels
- notification permission
- exact alarm access
- alarm scheduler
- receiver
- edit/delete cancellation
- same-time notification tests
- boot/time change recovery

### Phase 4 — Doctor Directory

- doctor entity/DAO/repository
- all doctors screen
- search/filter
- doctor review/editor/details
- Present/Absent
- midnight reset scheduler
- recovery logic

### Phase 5 — Secure AI Settings

- Keystore credential encryption
- Gemini key settings/test
- Groq key settings/test

### Phase 6 — Gemini Vision

- CameraX
- Photo Picker
- image preprocessing
- task structured extraction
- doctor structured extraction
- draft review flows

### Phase 7 — AI Assistant

- assistant UI
- local retrieval tools
- Groq 120B
- Groq 20B fallback
- fallback tests
- minimal context generation

### Phase 8 — Polish

- animations
- accessibility
- dark mode
- loading/error/empty states
- performance profiling
- final permission/reboot tests

---

# PART N — CODING RULES FOR THE IMPLEMENTING AGENT

## 48. General Rules

1. Do not introduce a backend.
2. Do not replace native Kotlin/Compose with another UI framework.
3. Do not hard-code API keys.
4. Do not auto-save AI extraction results.
5. Do not bypass silent/DND.
6. Do not let one notification overwrite another.
7. Do not use one giant ViewModel for the whole application.
8. Do not block the main thread.
9. Do not invent doctor data.
10. Do not silently change requirements to make implementation easier.
11. Preserve data through process death and app restart.
12. Use explicit UI states: Loading / Content / Empty / Error where appropriate.
13. Add tests while implementing each subsystem, not at the very end.
14. Run formatting, lint, unit tests, and relevant instrumented tests before declaring completion.

---

## 49. Definition of Done

Before saying the project is complete, verify on a real Android device or emulator as applicable:

```text
[ ] App launches cleanly.
[ ] Tasks persist after restart.
[ ] Doctors persist after restart.
[ ] New doctor defaults Present.
[ ] Doctor details can be fully edited and persist after restart.
[ ] Cancelling doctor edit leaves stored values unchanged.
[ ] Doctor deletion shows confirmation and permanently removes only the selected doctor.
[ ] Deleted doctor disappears from list/search/AI local retrieval.
[ ] Present/Absent manual toggle works.
[ ] Midnight reset logic works in simulated/test clock conditions.
[ ] Default task reminder = 5 minutes before.
[ ] Reminder time can be changed.
[ ] Task can be edited/deleted.
[ ] Exact alarm permission flow works.
[ ] Notification runtime permission flow works.
[ ] Same-time reminders create distinct notifications.
[ ] Silent/DND is not bypassed.
[ ] Custom <=5 sec sound works through notification channel semantics.
[ ] Reboot recovery restores alarms.
[ ] Gemini key is entered locally and encrypted.
[ ] Groq key is entered locally and encrypted.
[ ] Gemini image -> task draft works with valid key.
[ ] Gemini image -> doctor draft works with valid key.
[ ] Every AI draft requires review before Save.
[ ] Groq primary connection test works with valid key.
[ ] Groq fallback connection test works with valid key.
[ ] Automated fallback tests pass.
[ ] AI can answer from local doctor/task data.
[ ] Offline local features remain usable.
[ ] No real secret exists in source control or APK resources.
[ ] Release build completes.
[ ] Unit tests pass.
[ ] Lint passes or every remaining warning is intentionally documented.
```

Do not claim a real API integration has been tested unless valid user-provided credentials were actually used. Automated mock/fake transport tests are mandatory regardless.

---

# PART O — FINAL PRODUCT SUMMARY

Build one private Android app with this experience:

```text
TASKS
Manual entry ----------> Review -> Save -> Local exact reminder
Image scan -> Gemini ---> Review -> Save -> Local exact reminder

DOCTORS
Manual entry ----------> Review -> Save locally
Image scan -> Gemini ---> Review -> Save locally
                           |
                           +-> Present / Absent
                               resets to Present every local midnight

ASK AI
Question
  -> Groq GPT-OSS 120B
  -> local tool query over tasks/doctors
  -> concise grounded answer
  -> fallback to GPT-OSS 20B for defined transient failures
```

Priority order:

1. Reliability
2. Local privacy
3. Correct reminders
4. Correct doctor attendance reset
5. Smooth/simple UI
6. Safe AI behavior
7. Performance
8. Visual polish

This file is the source of truth for the first version of the app.
