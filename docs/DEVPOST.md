# Devpost submission draft

> Draft for Ron to review and paste. Agents never submit. Everything claimed here must match the
> running app and the video; lines marked ⚠ wait for the phone checks in SUBMISSION_CHECKLIST.md.

**Project name:** ContextFriend Pro Hybrid

**Tagline (≤ 200 chars):** Meeting notes for teachers where the conversation never has to leave
the phone: our own on-device speech engines plus Gemini Nano via Firebase AI Logic, and the cloud
only with names replaced.

**Platform:** Android

**Built with:** Firebase AI Logic (hybrid inference, Gemini Nano 4, Vertex AI / Agent Platform),
Firebase App Check, Firebase Authentication, Cloud Firestore, Firebase Remote Config, Kotlin,
Jetpack Compose, whisper.cpp, sherpa-onnx, llama.cpp, Gemma 4, libsodium.

---

## Inspiration

A teacher in Germany has a dozen conversations a week that matter: parent–teacher conferences,
support-plan meetings, a worried pupil after class. Writing them up takes evenings. AI could do it
in seconds, but these conversations are about children, and pasting them into a cloud chatbot is
exactly what school data-protection rules (and the GDPR) forbid. So teachers either don't use AI,
or they break the rules quietly.

We build ContextFriend, an AI product for schools that runs locally. ContextFriend Pro brings that
idea to the phone in a teacher's pocket.

## What it does

ContextFriend Pro records a meeting and turns it into sourced, reviewable notes, on the phone:

- **Transcribes** the conversation and **tells the speakers apart** with our own on-device engines
  (whisper.cpp, sherpa-onnx with pyannote segmentation and TitaNet speaker embeddings).
- **Writes the notes with Gemini Nano on the phone through Firebase AI Logic**, with
  `InferenceMode.ONLY_ON_DEVICE`. Every statement links back to the transcript lines it came from.
  For German and Hebrew (Gemini Nano is validated for English) and on phones without Gemini Nano,
  our own Gemma 4 engine writes them, still on the phone.
- **Uses the cloud only when the teacher asks**: names, e-mail addresses, phone numbers, dates,
  addresses and school names are replaced on the phone with placeholders like `[PERSON_1]`; the
  teacher sees the **exact text** that would leave the phone, can tap any word that still looks like
  a name, and consents. Gemini in the cloud (Firebase AI Logic, Vertex AI in the EU) writes better
  notes from that text, and the phone puts the real names back.
- **Shows where every result ran**, with a badge driven by Firebase AI Logic's `inferenceSource`,
  and keeps a **privacy log** with metadata only (never content).
- **Shares** notes phone-to-phone (QR, Bluetooth, local network) or through an **end-to-end
  encrypted cloud link**: Firestore stores only ciphertext for seven days.
- Meeting types for schools (parent–teacher, support plan, counselling, staff meeting), plus medical
  and legal templates for other professions with confidential conversations.

## How we built it: Firebase AI Logic hybrid inference as a privacy boundary

Most hybrid apps use the device to save cost or latency and fall back to the cloud. We turned that
around: the device is the default, and the boundary to the cloud is a deliberate, inspected,
consented step.

- **Per-task inference modes, never a `PREFER_*` mode for sensitive content.** Meeting notes use
  `ONLY_ON_DEVICE` (Gemini Nano 4 via `OnDeviceModelOption.PREVIEW`); the opt-in cloud request uses
  `ONLY_IN_CLOUD`. `PREFER_ON_DEVICE` would silently send a meeting to the cloud when the model is not
  downloaded; we never use it.
- **`inferenceSource` is checked on every answer.** An on-device request answered `IN_CLOUD` is
  discarded and logged as a policy violation (unit-tested).
- **Our engines do what Nano can't.** Gemini Nano on Android takes no audio, so speech and speaker
  separation run on our optimised native engines; Nano's 4,000-token request limit is handled by
  map-reducing long meetings into chunks with source ids carried through.
- **A deterministic pseudonymiser** (no model, fully unit-tested) prepares the cloud prompt; the
  placeholder mapping lives in memory for one request only.
- **Firebase around it:** App Check (debug provider in debug builds, Play Integrity in release),
  anonymous Authentication, Remote Config (cloud model, Nano option, chunk size and a kill switch
  for local-only mode; consent cannot be switched off remotely), Firestore for ciphertext-only links
  with strict rules and a TTL, all in the EU. The cloud backend is Vertex AI (Agent Platform) in the
  `eu` multi-region; the free Gemini Developer API tier is not allowed for apps offered in the EEA.
- 75 unit tests run on every push, all on synthetic data.

Code: `android/app/src/main/java/tech/tamerin/contextfriendpro/` — `Hybrid.kt` (Firebase AI Logic),
`Policy.kt`, `Redaction.kt`, `Notes.kt`, `CloudDraft.kt`, `CloudShare.kt`, `Audit.kt`.

## Challenges

- Gemini Nano's request limit and output length: long meetings needed hierarchical summarisation
  that never truncates silently and keeps every statement sourced.
- Language: on-device validation covers English, but our first users speak German, so routing
  picks our own engine there instead of pretending.
- The free Gemini API tier cannot be used for EEA users; we moved the cloud path to Vertex AI in
  the EU.
- ⚠ (fill in from the phone tests: offline behaviour, AICore quota, Nano output quality)

## Accomplishments

- A hybrid design where "hybrid" means the user controls the boundary, and the code proves it with tests.
- Real on-device speech, speakers and notes on a phone, measured on a Pixel 11 Pro: speech to text about
  3–4× faster than real time, and Gemini Nano 4 notes for a 12-minute recording in five on-device requests,
  in the background while the phone is locked.

## What we learned

⚠ Fill in after the phone tests (Gemini Nano 4 quality on structured notes, AICore behaviour).

## What's next

German on-device notes with Gemini Nano once validated, the iOS edition with Apple Foundation
Models through Firebase AI Logic, and pilots with our partner schools.

## Built during the submission period / existing app

ContextFriend (context-friend.com) is TamerinTECH's existing AI product for schools; it brings the
domain knowledge of school meetings and teachers' workflows. ContextFriend Pro's offline native
recorder prototype (transcription, speakers, Gemma notes, encrypted vault, phone-to-phone sharing)
was written between 27 Oct and 6 Nov 2026 and is disclosed as the first commit of the repository.
Built during the submission period: Firebase AI Logic hybrid inference (Gemini Nano on-device,
Gemini in the cloud), the routing policy, the pseudonymiser and the "Review what you send" screen, re-identification,
the privacy log and badges, meeting templates, App Check, Authentication, Remote Config, the
encrypted Firestore links, CI and all tests. See `docs/PRE_EXISTING.md` and `CHANGELOG.md`.

## Try it

- Landing page with install steps: https://tamerintech.github.io/context-friend-pro-hybrid/
- APK (signed, Android 12+): https://github.com/TamerinTECH/context-friend-pro-hybrid/releases/latest
- Testing instructions and a fictional demo script: `docs/TESTING.md` and `docs/demo/` in the repository.

Free, no account needed. Cloud notes work on real phones (App Check with Play Integrity).
