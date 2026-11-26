# Done

Status: **Thursday 26 November 2026, 09:35 Berlin time.** What is built and checked for the #BuildHybridAI
2026 entry. Open items are in [PENDING.md](PENDING.md). Evidence for every check is in
[TEST_REPORT.md](TEST_REPORT.md); the history is in [../CHANGELOG.md](../CHANGELOG.md).

## Repository and rules

- Public repository `TamerinTECH/context-friend-pro-hybrid`, Apache-2.0, entered by TamerinTECH GmbH.
- The pre-existing prototype is one disclosed baseline commit (`f064f77`, [PRE_EXISTING.md](PRE_EXISTING.md));
  everything after it was built in the submission period.
- No keys, tokens or personal data in Git: Firebase options live in untracked `android/local.properties`.
- CI (GitHub Actions) runs the JVM unit tests on every push: **90 tests, all passing**.

## Firebase (project `contextfriend-pro-hybrid`)

- Blaze plan; Firebase AI Logic with the Agent Platform (Vertex AI) backend, location `eu`.
- App Check: debug provider in debug builds, Play Integrity in release builds. **Enforced for AI Logic**
  (an unregistered build gets HTTP 401); Firestore is not enforced yet.
- Anonymous Authentication, Remote Config (kill switch `cloud_enabled`, cloud model, Gemini Nano option,
  chunk size, extra protected terms).
- Firestore `(default)` in **eur3**, rules that accept only the encrypted link document, TTL policy on
  `shares.expiresAt` deployed (activation not read back yet).

## App: the hybrid core (22–24 Nov)

- Gemini Nano through Firebase AI Logic with `ONLY_ON_DEVICE`; the ContextFriend engine (Gemma 4) for
  German, Hebrew and phones without Gemini Nano. Automatic routing never picks the cloud, and every
  answer's `inferenceSource` is checked (a mismatch is discarded as a policy violation).
- Opt-in cloud notes with `ONLY_IN_CLOUD`: names and identifiers replaced on the phone, the exact text
  shown first, the answer re-identified on the phone.
- Encrypted cloud links (XChaCha20-Poly1305 on the phone, ciphertext in Firestore for 7 days),
  phone-to-phone transfer without internet, privacy log with metadata only.
- English, German and Hebrew.

## App: the redesign (25 Nov)

Based on the design review in [design/](design/README.md):

- **Navigation:** three bottom tabs, Meetings, Receive and Settings, instead of six hidden chips.
- **Recording:** a "Before you begin" screen (title, summary style, language spoken, a one-time
  checkbox) and a full-screen navy recording view with a large timer and level bars from the real
  microphone signal. The microphone permission is asked only here.
- **Notes start by themselves** after "Finish recording", with the real steps shown (speech to text
  with progress, speakers, notes). No hidden "Process locally" step.
- **Clear states:** No speech detected, Notes not created yet, Needs attention, Draft for review,
  Reviewed, Received. A silent recording is never shown as a summary.
- **One main action per state:** Mark as reviewed → Share summary. Everything else is in the ⋮ menu
  (rename, summary style, create notes again, improve with Gemini, previous notes, play, delete audio,
  delete), with destructive actions last and in red.
- **Where the notes were made** is shown per meeting ("Created on this phone · Gemini Nano",
  "Cloud-assisted · Gemini, names replaced", "Received by encrypted link · sender not verified"); the old
  global "Processed on this device" label is gone.
- **Previous notes are kept:** after a cloud run (or creating notes again) the earlier notes stay in the
  history; ⋮ → "Switch to previous notes" swaps them back.
- **Share summary:** one entry point, what you share first, then how (encrypted link, nearby phone,
  another app). Drafts can still be shared and are marked Draft.
- **Review what you send:** a full page with the conversation text first, replaced names highlighted,
  tap-to-replace hints, where it goes, the exact request behind an expander, and one "Send once to
  Gemini" button.
- **Receive** tab: scan a code, paste a link, or receive from a nearby phone.
- **Settings** grouped into This phone, Privacy and Advanced, with pages for On-device AI, Get ready for
  offline notes (real sizes and free space), Privacy activity (readable events with details), recording
  storage, cloud assistance, language, models and voice recognition.
- **Design system:** "Quiet Blue" colours on every component (no default lavender), 16/14 sp text,
  48 dp touch targets, readable status bar icons, edge-to-edge insets.
- **German and Hebrew:** dates and times in the app language, technical values isolated in Hebrew,
  meeting content takes its own text direction, long German labels wrap instead of overflowing.

## Logo and brand

- The ContextFriend logo redrawn as a vector, with every asset generated from one script
  ([brand/](brand/README.md)): adaptive and themed (monochrome) app icon, round icon, notification icon,
  in-app logo, PNGs from 48 to 1024 px, Play icon 512, Play feature graphic, Devpost thumbnail.

## On the Pixel 11 Pro: background notes and recording (26 Nov)

- Notes are made in a background service that starts when a recording ends (normally or interrupted),
  keeps going with the screen off, and continues after the app was killed from the saved progress.
  Checked live: the 44-minute meeting picked up again by itself after several reinstalls.
- Percent done on the meeting screen, the list chip and the notification.
- "New recording" works while notes are being made: the running notes pause ("Paused · N %") and
  continue afterwards. Checked live.
- Native engines optimised in debug builds: speech to text about 3–4× faster than real time on the Pixel.
- The recording screen shows sound that arrives: decibel level bars, "sound is quiet, move closer", and
  "Sound heard: mm:ss". Checked live: a podcast from a speakerphone across the desk showed
  "Sound heard: 05:26".
- The recording notification is visible (not "Silent"), with a timer, Pause/Resume, Finish and a tap
  back to the recording.
- Gemini Nano: the app tries the request variants a phone may accept and names the problem when AICore
  refuses ("not ready on this phone yet"); failures are logged with stage and error type.

## Bugs found by checking on the emulator and fixed

- Remote Config read before its defaults; received meetings offered Approve and re-share; switch labels
  did nothing; the cloud panel asked for consent while the cloud was off (24 Nov).
- App Check refusals showed a generic failure; now a plain message (24 Nov).
- The QR scanner crashed the app when closed (25 Nov).
- Opening a received link bounced back to Receive; received notes said "Edited on this phone"; system back
  left the editor without asking about unsaved changes; option labels (language, engine, summary style)
  were not tappable; the privacy activity said "Nothing sent" for requests that may have reached the
  server; English content was mirrored in the Hebrew UI (25 Nov).

## Docs

- Judge guide [TESTING.md](TESTING.md) and [VIDEO_SCRIPT.md](VIDEO_SCRIPT.md) updated to the new screens;
  [DEVPOST.md](DEVPOST.md), [FIREBASE_SETUP.md](FIREBASE_SETUP.md), [HYBRID_ARCHITECTURE.md](HYBRID_ARCHITECTURE.md),
  [SUBMISSION_CHECKLIST.md](SUBMISSION_CHECKLIST.md).
