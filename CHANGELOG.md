# Changelog

Hackathon work for #BuildHybridAI 2026. The first commit is disclosed pre-existing code
(docs/PRE_EXISTING.md); everything listed here was built during the submission period.

## 2026-11-26

- **Notes in the background.** A new foreground service (`ProcessingService`) makes the notes; it
  starts as soon as a recording ends, normally or interrupted, and keeps going with the screen off or
  another app open. Meetings cut off by a killed app continue from their saved progress on the next
  launch. Gemini Nano answers only while the app is on screen, so a meeting waits for the screen
  ("notes_waiting") and continues by itself when the app opens.
- **Percent done** on the meeting screen, in the list chip and in the notification, from real work:
  speech to text 65 %, speakers 25 %, notes 10 %.
- **Record while notes are made.** "New recording" stays available; a running notes job pauses for the
  recording (finished windows are kept, the chip says "Paused · N %") and continues afterwards. Heat and
  low-memory pauses are resumable too, no longer "failed".
- **Speed.** Debug builds compiled whisper.cpp, llama.cpp and sherpa at -O0; they are now optimised
  (-O3), about 3–4× faster than real time for speech to text on the Pixel 11 Pro.
- **Hearing sound.** The recording screen measured raw linear peaks, so a speaker a metre away looked
  like silence. The level is now 100 ms RMS in decibels with an adaptive noise floor (`SoundMeter`,
  4 unit tests): "picking up sound", "sound is coming in, but quietly — move closer", "no sound", and a
  live "Sound heard: mm:ss" counter.
- **Recording notification** no longer sorted under "Silent": status bar icon, running timer,
  Pause/Resume and Finish, a tap returns to the recording screen, shown on the lock screen without the
  meeting's title; no sound or vibration. A leftover "Creating notes" notification after processing is gone.
- **Recorder** is sturdier: a larger buffer, a slow write skips one second instead of ending the
  recording, read errors reopen the microphone up to three times, and interruptions show their reason.
- **Gemini Nano** tries the ways a phone may accept (preview model with JSON, plain, defaults; then the
  stable model), warms the model up once, and says "Gemini Nano is not ready on this phone yet" instead
  of a generic error. Processing failures are logged with stage and error type (never content).
- New copy in English, German and Hebrew (451 strings per language); 97 unit tests.

## 2026-11-25

- QR scanner no longer crashes the app when it closes: the camera frame copy checks its bounds and
  skips bad frames, and the frame reader closes on the camera thread (3 new unit tests).
- **Redesign** after a design review (docs/design/): three bottom tabs (Meetings, Receive, Settings);
  a recording setup screen and a full-screen recording view with real level bars; notes start by
  themselves after recording, with the real steps; distinct states (no speech, not created, needs
  attention, draft, reviewed, received); one main action per state with the rest in a menu;
  provenance per meeting instead of a global label; earlier notes kept and restorable after a cloud
  run; one "Share summary" entry; a full-page "Review what you send"; a Receive tab; Settings grouped
  with On-device AI, Get ready for offline notes and a readable privacy activity; the "Quiet Blue"
  colour scheme on every component, readable status bar, localized dates, Hebrew bidi isolation,
  content text direction. Meeting state logic in `MeetingState.kt` with 12 unit tests (90 in total).
- New and changed copy in English, German and Hebrew (429 strings per language).
- **Logo**: vector redraw and every asset generated from one script (docs/brand/): adaptive, round,
  themed and notification icons, in-app logo, PNGs, Play icon and feature graphic, Devpost thumbnail.
- Debug builds only: Settings → Advanced → "Add an example meeting" (synthetic text, no audio) to check
  the summary and review screens on an emulator without a microphone.
- Fixed while checking on the emulator: a just-received link bounced back to Receive; received notes
  said "Edited on this phone"; system back left the editor without asking (a cached function reference
  kept an old value); option labels were not tappable; "Nothing sent" was shown for requests that may
  have reached the server; English content was mirrored in the Hebrew UI.
- Judge guide, video script and other docs updated to the new screens; docs/DONE.md and docs/PENDING.md.

## 2026-11-24

- Firebase project `contextfriend-pro-hybrid` connected: Firestore pinned to eur3 in
  `firebase.json`, anonymous sign-in declared there too, TTL for expired links defined in
  `firestore.indexes.json` (deploys after the Blaze upgrade).
- Verified on the emulator against the live project: encrypted cloud link created and opened,
  Remote Config kill switch, privacy log (docs/TEST_REPORT.md).
- Fixes from that run: Remote Config is read only after its defaults are applied, and the kill
  switch stays closed until then; received meetings say where they came from and cannot be
  approved or re-shared; tapping a switch's label toggles it; the privacy panel says at once when
  the cloud is switched off.
- Blaze and AI Logic on; Firestore TTL for expired links deployed. AI Logic enforces App Check, and
  a cloud request refused by App Check now says that this copy of the app could not be verified
  instead of a generic failure (English, German, Hebrew).

## 2026-11-22

- Public repository, Apache-2.0 licence, disclosure of pre-existing code, hackathon
  decisions and agent rules.
- Firebase BoM 35.2.0, Firebase AI Logic 18.2.0 and the on-device SDK 16.0.0-beta08 added;
  Kotlin 2.1 to 2.4 (Firebase 35 ships Kotlin 2.4 metadata). Firebase options come from
  untracked `local.properties`, so no key is in the repository.
- Privacy core: deterministic local pseudonymiser (`Redaction.kt`), routing policy
  (`Policy.kt`), meeting templates and prompts (`Prompts.kt`). 55 unit tests with
  synthetic data, including a check that a cloud prompt contains none of the fixture's
  names or identifiers.
- Hybrid notes engine: Gemini Nano through Firebase AI Logic with `ONLY_ON_DEVICE`
  (Gemini Nano 4 preview option, JSON output, BUSY back-off, inference source checked on
  every answer), the prototype's Gemma 4 engine as the local alternative, chosen by policy.
  Map-reduce notes moved into a model-independent `Notes.kt`.
- Opt-in cloud notes: `CloudDraft` prepares the exact outgoing prompt on the device, the
  policy gate runs again right before sending, the answer is re-identified locally. Cloud
  backend: Vertex AI (Agent Platform) in an EU region, `ONLY_IN_CLOUD`.
- Firebase services: App Check (debug provider in debug builds, Play Integrity in release),
  anonymous Authentication, Remote Config (kill switch, cloud model, Nano option, chunk
  size, extra protected terms).
- Local privacy audit log (metadata only, encrypted in the vault, exportable as JSON).
- UI: AI & privacy screen (Gemini Nano status and download, engines, names to protect,
  privacy log), "where it ran" badge, meeting templates (parent–teacher, support plan,
  counselling, staff, medical, legal), privacy panel with the exact outgoing text, tap-to-
  redact name hints and consent. English, German and Hebrew strings.
- 71 unit tests, including the full notes pipeline with a fake model and proofs that the
  cloud model is never called without consent or with the kill switch on.
- CI on GitHub Actions (JVM unit tests). Opt-in "allow screen capture" setting (FLAG_SECURE
  stays the default). Default cloud location `eu`, the Vertex AI multi-region with EU data
  residency and in-EU ML processing.
- Encrypted cloud links ("hybrid if desired"): notes text only, XChaCha20-Poly1305 on the
  phone, Firestore stores ciphertext for at most 7 days; link or QR `CFS1:<id>:<key>`; the
  recipient decrypts locally. Firestore rules allow only that document shape and no listing.
  Remote Config template and `firebase.json` for deployment. 75 unit tests.
