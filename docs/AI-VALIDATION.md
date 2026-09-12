# AI experience validation — 2026-09-12

Implemented chat fallback: Groq GPT-OSS 120B → GPT-OSS 20B → Qwen 3.6 27B → Gemini 3.5 Flash → Flash-Lite. Scans retain Qwen → Gemini Flash → Flash-Lite and now report successful model identity. Chat replies persist every model that contributed during tool rounds.

The usage sheet tracks API-reported tokens, Groq quota snapshots, retry/reset countdowns, and unknown Gemini remaining quotas. Counters persist locally and reset when replacing/deleting the corresponding provider key. Conversation summaries update after replies, use bounded extra API requests, and retain recent messages when summarization fails. Summaries can omit older details; identical output quality or lower total tokens for every short chat is not guaranteed.

The compact usage panel shows documentation baselines separately from measured balances, with equal-width Usage and Docs buttons in one row per model. Details and conversation memory are collapsed by default. The [Groq free-plan table](https://console.groq.com/docs/rate-limits), checked September 12, 2026, lists 8,000 TPM, 200,000 TPD, 30 RPM and 1,000 RPD for each configured Groq model. [Gemini's rate-limit documentation](https://ai.google.dev/gemini-api/docs/rate-limits) directs users to project-specific AI Studio limits; no universal free-tier numbers are assumed for Gemini. These documentation values never control routing or fabricate remaining balances.

Batch review has one entry button, a scrollable editable form per item, independent save actions, stable IDs and saved-state persistence. Regression tests reproduce and cover invalid siblings and outages after drafts are prepared. Smaller output reservations and batches reduce quota/truncation pressure; duplicate single-item tool schemas are no longer sent.

Verified:

- 80 JVM tests passed, including all five fallback models, cancellation/offline handling, cooldowns, exact scan identity, real header/usage parsing, persisted summaries and edited draft state, partial-batch retention, and review validation.
- Debug APK and Android test APK compiled.
- Spotless passed; Android lint passed with no errors (existing warnings remain).
- Seven API 35 emulator tests passed: usage panel, mixed task/doctor review and independent saving, chat persistence, and existing draft review flows.
- Mixed review was visually inspected from the emulator screenshot.
- `git diff --check` passed.

The emulator had stale reminder fixtures reaching Android's alarm limit. Its app data was cleared during diagnosis; an attempted backup archive was not fully valid. Final UI checks used the separate temporary application ID `com.oki.aivalidation` on the emulator to avoid interference. The final APK has the normal `com.oki` application ID. No APK was installed on the connected physical phone.

Normal debug artifact: `artifacts/athii-ai-debug.apk` (ignored build output).
SHA-256: `1e49e8536d1e5788abb71a4fae65e075d61ea7110ac1545b1e0748dec7b8e772`.

Compact usage-panel follow-up: 84 JVM tests passed (including two documentation-cap tests), debug assembly, Android test compilation, Spotless and lint passed. The seven emulator checks above preceded this layout follow-up; they were not rerun for it. The updated UI assertion reflects the shorter unknown-quota label. The refreshed APK uses `com.oki` and contains no private bootstrap credential asset.

No live credentialed AI requests were run. Provider integration is covered by deterministic transports and official API documentation, not a live account smoke test. Full account usage outside this app cannot be inferred from local counters. Gemini's remaining quota is deliberately not fabricated.

## Chat presentation and greeting follow-up

- Markdown tables render as selectable cells with headers, alignment and horizontal scrolling. Parsing covers optional outer pipes, escaped pipes, inline code, empty cells and fenced-table examples.
- Chat history stores last-message timestamps, with lifecycle-aware relative ages and a single bounded timer while the sheet is open. Legacy chats retain unknown dates until new activity; dates are not fabricated.
- Fresh standalone greetings use a small system prompt, no tools and low reasoning. They skip the extra memory-summary call. Greetings with prior context and actual task/doctor requests retain normal tools and context.
- Usage totals explicitly say "Used" and remain cumulative; quota bars show remaining provider snapshots. No fixed token savings or identical output quality is promised.
- All 91 JVM tests, lint, formatting and signed release assembly passed. A stale compiled 800-task constant initially conflicted with concurrent task-limit changes to 300; rebuilding without Kotlin incremental compilation resolved the test failure without modifying those task changes.
- Artifact: `artifacts/athii-chat-update.apk`; SHA-256 `64eed3185455606906b9eb6a899ce47609626c2c8a0c9a680ee8859a2a873426`. This follow-up has not rerun emulator UI tests or live AI requests.

Settings flicker investigation found shared busy-state dimming and a conditionally inserted progress bar as likely contributors; no flicker-specific change was made. Silent/DND sound behavior was inspected but not changed, pending clarification of the intended behavior.
