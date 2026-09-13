# Athii QA and release handoff

Updated 2026-09-13. Work is in progress; do not interpret this as a completed validation report.

## User requirements

- Tutorial skips steps without task/doctor data, never strands users behind a dark overlay, and moves smoothly.
- Test task creation/editing/completion/limits/reminders, doctor CRUD/limits, chatbot responsiveness and model usage.
- Single task/doctor deletion needs only a confirmation dialog. Device authentication applies only to Settings clear completed tasks, clear chat history, and delete all data.
- Quota bars should clearly show reset windows while retaining measured usage. Add Groq `qwen/qwen3.8-27b` for text and images.
- Run independent work/tests in parallel, test the connected Realme including silent/normal modes, preserve its existing data, produce a signed release APK.

## Environment and safety

- Repo: `/home/dhanush/Documents/yukthi`; no applicable AGENTS.md found; initially clean git tree.
- Build with `./scripts/build.sh` (selects local JDK 21). Android SDK `/home/dhanush/Android/Sdk`.
- ADB is not on PATH: use `/home/dhanush/Android/Sdk/platform-tools/adb`.
- Realme Android 12 serial `S4V8SKXS7PGIHQGI`, installed `com.oki` is a non-debuggable signed release 1.1.1/code 3. Do not uninstall or clear phone storage.
- Emulator `Oki_Test_API35` / `emulator-5554` is running for destructive fixture suites. Existing scripts/test-emulator.sh deliberately allows emulators only.
- `.env` and `.signing.properties` exist. Never print their contents. Default release must not embed credentials; phone update retains configured keys.
- Phone initially had silent/ringer streams muted and DND (`zen_mode`) 0. Restore original device settings after tests.

## In-progress ownership

- Root: shared deletion policy, task form/custom lead time/expired-dialog usability, Qwen model catalog and vision order, test orchestration/device testing/release.
- `tutorial` agent: feature/tutorial and narrow MainActivity/DoctorScreens integration, tutorial regressions.
- `notifications` agent: reminder queue/cap/reopen fixes, notification and repository tests.
- `doctor_chat` agent: doctor validation/limits, malformed AI tool handling, quota reset UI and tests.

## Findings and edits so far

- Shared ConfirmDelete now defaults to dialog-only; Settings requires authentication for `all`, `completed`, `chat`. Regression tests added.
- Custom reminder text field used rememberSaveable(offset), causing it to disappear when typing a preset or blank; removed offset as state key.
- Task list clock now advances without user interaction; expired reminder Change start time scrolls to date fields; dialog actions stack; disabled notifications ignore a hidden invalid custom offset.
- Tutorial doctor Edit/Delete targets were off-screen lazy items, causing dark overlays; agent fixing auto-scroll/target readiness/data filtering.
- Scheduler restore limited first 180 tasks; next-event-per-task scheduling and cap regression fix in progress.
- Groq official docs confirm Qwen3.8 text/images/tools/JSON and 30 RPM, 1K RPD, 8K TPM, 200K TPD. Sources: https://console.groq.com/docs/model/qwen/qwen3.8-27b and https://console.groq.com/docs/rate-limits.

## Validation status

- Initial baseline Gradle unit task was up-to-date, but instrumentation compile overlapped root API edit and saw old ConfirmDelete signature. Rebuild after current edits settle. Latest debug compile + JVM suite passed (112 tests at that snapshot); final added tutorial/edge tests are being run now.
- Phone is connected/unlocked and existing app launches. No phone data has been cleared.
- Required next: compile, unit suite, full emulator instrumentation, lint/format, live synthetic AI checks, phone notification/silent/normal checks and temporary fixture CRUD, signed release build/install preserving signature, artifact checksum and final docs.

## Resume checkpoint

- All three implementation agents finished; tutorial agent hit usage limit after adding controller/overlay tests (no full-tour test created).
- Current Gradle session builds format/unit/debug/androidTest/lint/release; log `/tmp/athii-validation-build.log`.
- Emulator debug/test APK installed; first-run tutorial may need skipping before general UI suite.
- Reviewer found restore() clears due expectedAt before in-flight receiver delivery. notifications agent assigned correction/regression.
- New user requirement: overdue moved to future goes to Upcoming without past lead-time warning (fall back to at-start); completed details read-only, reopen to edit. Root implementing.
- Realme original Settings: alert during DND On, notification permission Allowed, exact Ready, battery Unrestricted; silent/ringer muted, zen_mode 0. Baseline files `/tmp/athii-realme-audio-before.txt` and notification baseline.
- Live Qwen probe returned 403 via urllib; independent live_ai_check agent investigating client/network vs account.

## Latest verified checkpoint

- JVM suite now **123 tests, 0 failures/errors**. `/tmp/athii-latest-debug-build.log` BUILD SUCCESSFUL.
- First all-task build/lint/release passed (`/tmp/athii-validation-build.log`): lint 0 errors/42 warnings. Release APK installed onto Realme with `adb install -r`, existing data preserved. This APK precedes latest recovery and requested completed-task changes; must rebuild/install final.
- Realme test notification posted through opted-in alarm-audio channel while phone in SILENT (baseline dumps saved); scheduled reminder and normal-mode tests still pending.
- Full emulator initial suite running report `app/build/reports/instrumentation/qa-2026-09-13-initial.txt`; found stale model numbering test (5→6) fixed, stale alarm sound assumption assigned notification agent.
- TaskEditingFlowTest new requested behavior tests compiled; need run baseline RED then doctor_chat agent applies fix (agent waiting).
- Recovery race RED reproduced (2 of26 failing) then fixed; full123 JVM green includes fix.
- Live synthetic checks all7 pass (Qwen3.8 text/tools/images, GPTOSS120 text, Gemini Flash text/tools). Earlier urllib403 is user-agent filter; okhttp/curl works. Sanitized `/tmp/athii-live-ai-results.json`.
- Use Java via `/home/dhanush/.local/share/oki/jdk-21/bin/java -jar /home/dhanush/Android/Sdk/build-tools/36.0.0/lib/apksigner.jar`; shell java not on PATH.
- UI helper `/tmp/athii-ui.py SERIAL tree|click LABEL|tap X Y|swipe ...|text VALUE|back|shot PATH`.

## Session 2 checkpoint (2026-09-13 ~10:55 IST)

- Verified before new work: emulator suite `qa-2026-09-13-final.txt` OK (51 tests); phone base.apk sha256 == built release (1.1.1/code 3, 10:09 build).
- Realme manual checks done: silent-mode scheduled reminder posted 10:26:29 for 10:25:00 on `reminders_default_v1_dnd_alarm` (USAGE_ALARM); ColorOS applied a +2m20s window to setExactAndAllowWhileIdle. Doctor create + HH:mm validation OK. Doctor filters (attendance/department/day, combined, reset) all correct. Task tabs Upcoming/Overdue/Completed correct.
- Phone fixtures still present: task "QA fixture silent", doctor "QA Fixture Doc". User data untouched ("Write a journal entry" overdue, doctor "Dhanush"). OPEN QUESTION to user: fixture start changed 10:25→10:51 at ~10:46:57 via editor; not done by scripts (snooze only changes scheduledReminderAt). Waiting on user before more phone input.
- New user requirements implemented:
  - Reminders: all task reminders use `setAlarmClock` when exact access exists (agent; NotificationTest 10/10 on emulator).
  - Autocomplete: inline ghost text kept (user rejected dropdown); accept arrow puts cursor at end; case-insensitive prefix; ghost only when focused and cursor at end (agent; `AutocompleteFieldTest` 4/4).
  - Export: icon beside Tasks/Doctors search → date-range dialog (presets, max 366 days) → `Downloads/Athii/Athii-{tasks|doctor-attendance}-FROM_to_TO.xlsx` (MediaStore Q+, SAF below) with Data + Analytics sheets and native charts. Files: `core/export/{XlsxWriter,Reports,ReportExporter}.kt`, `core/ui/ReportExport.kt`.
  - Attendance history: Room v6 `attendance_log` (PK date+doctorId, denormalized name/department/workingDays), `MIGRATION_5_6` also adds `index_tasks_dueAt`. Logged on save/toggle, snapshot before midnight reset, set-based recursive-CTE backfill (≤366 days) for days app not opened. History starts at update day.
- Verified: JVM suite 128 tests 0 failures (includes `ReportExportTest`; sample workbooks in `app/build/test-output/` load in openpyxl with charts). spotlessApply run.
- User confirmed they edited the fixture to 10:51 themselves (no bug).
- User moved the export icon: now a top-bar icon before Settings on Tasks (task report) and Doctors (attendance report), tutorial target `export_report`; search rows are full-width again.
- Tutorial: user wanted Tasks to get record steps like Doctors. Added `task_list` (card), `task_complete` (checkbox), `task_filters`, `task_search`, `task_delete` after `dashboard` (all needsTask; list shows all tasks while touring these, auto-scrolls to item 4) and `export_report` before `settings`. 20 steps with data. TutorialControllerTest updated.
- Verified after these changes: JVM 128/0; UiFlowTest 6/6 (earlier single failure was on the old search-row layout); lint 0 errors/42 warnings (pre-tutorial build). Emulator suite before tutorial/top-bar change: 56/57.
- Emulator suite on tutorial/top-bar code: OK 57 tests. Release 11:00 installed on Realme (cert 3705b5be… matches, no embedded credentials); attendance export on phone produced a valid 2-chart workbook.
- User disliked the Material DateRangePicker (headline wrapped, chips clipped). Replaced with AlertDialog: wrapping preset FilterChips, From/To date boxes using the platform DatePickerDialog (attendance max = today), "N days · range" summary. Build 11:06 sha256 f6908926…, JVM 128/0, lint 0 errors.
- Realme on build f6908926…: new picker verified visually; task + attendance exports saved to Downloads/Athii (4 test files incl. "(1)" duplicates) and load in openpyxl with 2 charts each. Task fixture complete → Completed tab → read-only detail → reopen → delete dialog without verification → deleted. Doctor fixture deleted with dialog only. Settings completed/chat/all each show "Phone verification required." (cancelled). User data intact. Phone scripts in session scratchpad; beware a trailing Back press leaves Oki.
- Tour replay on Realme: steps 2–17 show correct titles in order; screenshots confirm spotlight on task card, checkbox, delete icon and top-bar download icon. Settings/replay steps not captured in the second run (first run reached 19 of 20); tour finishes and returns to task list.
- Phone chat not re-tested: app has no single-chat delete, so a test chat would persist (asked user).
- User chose: skip phone chat test; delete test reports (done: 4 files + empty Download/Athii removed). Normal-mode script `phone-normal-reminder.sh` aborted safely because dumpsys still read SILENT after the user said they switched; asked user to switch again (ColorOS bell/Quick Settings).
- Normal-mode reminder on Realme PASSED: setAlarmClock registered window=0, scheduled 11:36:00 posted 11:36:01 on `reminders_default_v1_dnd_alarm`; fixture deleted; no notifications left. User restored Silent (verified SILENT, muted 0x1a6, zen 0 = baseline).
- New user-reported AI issues (screenshots): "create 5 task dance" + clarifying answer → only 1 draft; no date should default to today; "12am today" became past 00:00; "alarm mode" lost; batch review lacked single-review fields.
  - Fixes: `summaryContext` keeps last 8 messages verbatim even after summary (`RECENT_VERBATIM_MESSAGES`); prompt rules (missing date → today, past start → ask, count vs time ambiguity → ask, finish original request with all items after clarification, alertMode ALARM on request); summarizer keeps counts/dates/alert choices; `TaskDraft.alertMode` + schema enum; `ChatDraftSaver.reviewTask` and task editor draft path default blank date to today and map alertMode (`draftAlertMode`). New `ChatContextAndDraftTest`; AiEnhancementsTest updated. JVM 132/0.
  - Background agent rewriting `BatchReviewSheet` so each item has full single-review fields in one scroll list.
- ROOT CAUSE of "5 tasks → 1 draft": AssistantRepository deduplicated identical drafts (`draft !in taskDrafts`), collapsing 5 identical "Dance" items. Fixed with `mergeDrafts`: identical items inside one call stay separate; a repeated batch in a later call is still deduped; 50 cap kept. New unit test `identicalItemsInOneCallStaySeparateButARepeatedBatchDoesNot`.
- Live Groq check `LiveAssistantFlowTest` (opt-in marker `app/build/live-ai.enabled`, key from `.env` GROK_API, never printed): 5-task clarification → 5 drafts (today, 23:59, ALARM); missing date → today; "12am today" → asks instead of past draft. JVM 136/0.
- User took the phone away (~11:50): all further testing on emulator-5554; then release build. Remove the live-ai marker before handing off.
- Batch review agent DONE: `BatchReviewSheet` cards reuse `DateTimeFields`/`EndTimeField`/`ReminderLeadTimeField`/`Field`; task cards have title, notes, date+start pickers, end, Task alert, Notification/Alarm (writes `TaskDraft.alertMode`), lead time, past-start warning; blank date shows today; doctor cards match the doctor editor. `DraftReviewTest` 5/5 on emulator (existing scan test switched to performTextReplacement because blank dates now default to today). JVM 136/0 after spotlessApply.
- Emulator chat run DONE (live Groq, keyed debug build, emulator only): single task alarm → 1 draft (today 23:58, alarm description); 5 dance tasks → 5 drafts, batch cards full editor, Alarm selected, today 23:59; single doctor → 1 draft with doctor editor fields; 2 doctors → 2 drafts with all doctor fields. Screenshots `scratchpad/emu-chat-*.png`.
- Full emulator suite on final code: 57/58, only `AiExperienceTest.mixedBatchEdits…` failed on the old "Title" label; updated to "Task title *" and rerun OK (2/2) → effectively 58/58.
- Release 11:52 sha256 2329ea7a…, cert 3705b5be…, no embedded credentials, lint 0 errors/42 warnings, spotlessCheck clean. Live AI marker removed.
- New device: OPPO CPH2757 (serial 616bef82, Android 16), Oki not previously installed. ColorOS blocks `adb install` until the on-phone "Install" prompt is approved (phone must be unlocked). Install in progress.
- OPPO: release 11:52 installed via adb + on-phone Install tap (sha matched). First-run BUG found: the "Welcome to Athii" tour card opened underneath the "Set up your reminders" permission sheet. Fix in MainActivity: `startTourIfNeeded` now waits for `setupSettled = storedSettings != null && !setupNeeded` (sheet not needed or dismissed). Rebuild + emulator TutorialOverlay/UiFlow/DraftReview/AiExperience tests running; OPPO script `scratchpad/oppo-test.sh` reinstalls with `-r` (Oki data still first-run) and checks sheet → tour order, task reminder, doctor, exports, Settings verification prompts, cleanup.
- Overlap-fix build verified: JVM 136/0, lint 0 errors/42 warnings, spotlessCheck clean, release sha256 184d2f3b…, cert 3705b5be…, no embedded credentials; emulator TutorialOverlay/UiFlow/DraftReview/AiExperience OK (16 tests). OPPO run of `oppo-test.sh` on this build started.
- OPPO first run on build 184d2f3b… (installed with -r, sha matched): setup sheet shown alone (no tour card underneath) — overlap fix confirmed; after the sheet closed the tour ran 8 steps with no data (task/doctor steps skipped). ColorOS rejects `pm grant` / `appops set` from adb (SecurityException), so the user allowed Notifications and Alarms & reminders on the phone. The tour ends on Settings; the rerun script returns home first. Rerun of task reminder/doctor/exports/Settings prompts/cleanup in progress.
- OPPO rerun (blind script) FAILED after opening the task form: permissions were still off (dumpsys: POST_NOTIFICATIONS granted=false, app importance NONE, SCHEDULE_EXACT_ALARM default), the task save did not complete, the doctor text went into the open task form, and later steps hit wrong screens; its "deleted" lines were false positives. Verified afterwards: no tasks or doctors were saved on the OPPO. Real tab bounds on this phone: Tasks (213,2672), Doctors (639,2672), Ask AI (1067,2672). Next OPPO checks must be step-by-step with screenshots after the user enables Notifications and Alarms & reminders manually.
- OPPO permissions: user's first two "allowed" answers did not take (single user 0, no clone). Opening Athii's own pages via adb intents worked: `am start -a android.settings.APP_NOTIFICATION_SETTINGS --es android.provider.extra.APP_PACKAGE com.oki` → user toggled → verified POST_NOTIFICATIONS granted=true, importance DEFAULT, appop allow. Then `am start -a android.settings.REQUEST_SCHEDULE_EXACT_ALARM -d package:com.oki` opened (Off at the time); waiting for user toggle and dumpsys confirmation.
- OPPO exact-alarm: Settings page switch reads On after user toggle, but `appops get` (package and uid) still says "default"; proof must come from a real reminder's alarm registration.
- OPPO step-by-step checks: taps and `input text` work (no spaces — spaces produced garbage on this keyboard), but ColorOS ignores injected scrolling (`input swipe`, `input motionevent` drag, DPAD_DOWN, `input roll`), so "At time"/"Save Task" below the fold can't be reached from adb. Unsaved test form "OPPOQAtask" start 12:43 left open; asked the user to either save it by hand, enable ColorOS Developer options → "Disable permission monitoring", or stop OPPO testing.
- OPPO task check: user scrolled and tapped Save at ~12:39 → task list shows "OPPOQAtask · Starts 12:43 pm · Start notification 12:43 pm" (5-min lead had passed, so it fell back to start time as designed). Alarm: `action remind origWhen=2026-09-13 12:43:00.000 window=0 exactAllowReason=permission` — proves exact-alarm access is effective despite appops "default". Notification watcher running until 12:46; export check (tap-only) running; task deletion after the watcher.
- OPPO task export (tap-only) PASSED: new picker "7 days · 7 Sep – 13 Sep 2026" → "Saved Athii-tasks-2026-09-07_to_2026-09-13.xlsx to Downloads/Athii"; openpyxl: Data + Analytics, 2 charts, row = OPPOQAtask 2026-09-13 Sun 12:43 Upcoming Notification Manual. Attendance export (empty directory) running.
- OPPO attendance export (empty directory, tap-only) PASSED: "Saved Athii-doctor-attendance-2026-09-07_to_2026-09-13.xlsx"; openpyxl: Data + Analytics, 1 chart, data row "No attendance was recorded in this date range.", note "Attendance history starts recording after this update." Two test reports in OPPO Downloads/Athii — user chose to KEEP them.
- OPPO reminder PASSED: OPPOQAtask notification posted 12:43:02 for 12:43:00 on `reminders_default_v1` (phone Normal). Deleted via "Delete task?" dialog (no phone verification); removed from all tabs, 0 notifications and 0 remind alarms left. Remaining OPPO-only gaps (need user scrolling): doctor save, Settings phone-verification prompts — both already verified on Realme/emulator; asked user whether to continue.
- OPPO doctor save PASSED (user scrolled and tapped Save Doctor): directory shows "OPPOQADoctor · QADept · Present", header "1 present today". Settings phone-verification prompt check next (user scrolls to Local data; taps + Cancel from adb), then delete the test doctor.
- OPPO Settings prompts PASSED (user scrolled to Local data; adb tapped + Cancel): "Clear completed tasks" → "Delete completed?", "Clear chat history" → "Delete chat?", "Delete all app data" → "Delete all app data?", each with "Phone verification required."; all cancelled, nothing cleared. Next: delete OPPOQADoctor (detail screen, Delete doctor below the fold → user scroll), then final summary.
- OPPO doctor delete PASSED: "Delete doctor?" naming OPPOQADoctor, no phone verification → deleted; directory empty ("0 present today"). Final OPPO state: 0 Oki notifications, 0 remind alarms, notifications granted, two kept reports in Downloads/Athii. OPPO testing COMPLETE.
- User request after the 12:04 candidate: remove "Groq first, then Gemini if needed." from the Ask AI screen note (AssistantScreen.kt). Removed that sentence only; kept the privacy sentences. Left "Groq first · Gemini fallback" (ModelUsageSheet) and the Settings "try Groq first, then Gemini" text unchanged pending the user's call. Rebuilt 17:11: JVM 136/0, lint 0 errors/42 warnings, spotlessCheck clean. NEW CANDIDATE `app/build/outputs/apk/release/app-release.apk` sha256 68378bc9903a825a33ef70c02654c948d788802933e871762d6cb62721e13bf9, cert 3705b5be…, no embedded credentials; dex check: removed sentence absent, privacy note present. Emulator screenshot (`scratchpad/emu-ask-ai-note.png`, debug build of the same source) confirms the Ask AI empty state now shows only "Questions, compact conversation context and matching records go to the responding provider. Chat history stays on this device." Awaiting user approval of 68378bc9…. The 12:04 build below is superseded.
- PREVIOUS CANDIDATE: `app/build/outputs/apk/release/app-release.apk` sha256 184d2f3b976a979227ca4efe4292485a1e53af732837fdbc20b289f7eb25ea83, versionName 1.1.1 / code 3, cert 3705b5be…, no embedded credentials. No source changes since this build. Awaiting user approval. All changes uncommitted.
- Android 16 `dumpsys activity activities` prints `topResumedActivity=`/`ResumedActivity:` instead of `mResumedActivity`; phone scripts updated.
- (Earlier note) Emulator chat run used a debug build with `-PembedAiCredentials=true` (emulator only, app data cleared): `scratchpad/emulator-chat.sh` covers single task alarm, 5 tasks, single doctor, 2 doctors and opens each review. Run `scripts/test-emulator.sh` only after it finishes (it reinstalls a keyless build).
- Remaining: emulator AI UI checks (single/multi task/doctor via chat, needs emulator credentials), full emulator suite, release build, chat reply + usage sheet, tutorial replay with new task steps, restore ringer SILENT/zen 0, user approval, final release sha256.
- Next: install release on Realme with `adb install -r`, phone checks (export both reports and open in viewer, normal-mode reminder, task complete/reopen/delete dialog-only, Settings auth prompts cancelled, chat), delete fixtures, restore ringer/DND to baseline, user approval, final release + sha256.
