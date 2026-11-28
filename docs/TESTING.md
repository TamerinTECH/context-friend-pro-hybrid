# Testing instructions

> **28 Nov 2026.** The Gemini Nano path, background notes and recording were checked on a Pixel 11 Pro
> (docs/TEST_REPORT.md). Steps still marked *(phone check pending)* are implemented and unit-tested and
> are checked on the release build before the submission.

For judges and anyone trying ContextFriend Pro Hybrid. No account, login or personal data is
needed. Use only the fictional demo meeting in [demo/parent-teacher-conference.md](demo/parent-teacher-conference.md)
or your own test speech, never a real meeting.

## What you need

| To see | You need |
|---|---|
| Recording, transcription, speakers, local notes (ContextFriend engine) | Any Android 12+ phone with 8 GB RAM. Download the models with **Settings → On-device AI → Get ready for offline notes** (about 3.6 GB, once, on Wi-Fi). |
| Notes by **Gemini Nano on the phone** (Firebase AI Logic) | A phone on the [Gemini Nano 4 list](https://developers.google.com/ml-kit/genai), for example Pixel 10 or 11, with the AICore Developer Preview turned on (below). |
| **Cloud notes** with names replaced, and **encrypted cloud links** | Internet. Both are opt-in and always show what leaves the phone first. |

### Turn on Gemini Nano 4 (Pixel 10 / 11)

1. Settings → your name → All services → System services → **Android AICore** → Update.
2. Turn on Developer options: Settings → About phone → tap **Build number** seven times.
3. Settings → System → Developer options → **AICore Settings** → **Enable AICore Developer Preview** → Accept.
4. In ContextFriend Pro, open **Settings → On-device AI**. The Gemini Nano card shows **Ready**, or tap
   **Download Gemini Nano** (Wi-Fi, a few minutes).

## Install

1. On the phone, open the [landing page](https://tamerintech.github.io/context-friend-pro-hybrid/) and tap **Download for Android**, or download
   `ContextFriendPro.apk` from the [latest release](https://github.com/TamerinTECH/context-friend-pro-hybrid/releases/latest). Open it and allow your browser to install
   apps. The app is free, without account or ads.
2. Start ContextFriend Pro: a short introduction (three steps) ends in **Get this phone ready**, which
   downloads speech to text and speaker separation (120 MB, use Wi-Fi). The **Local AI guide** in the
   introduction and under Settings explains Gemini Nano and the ContextFriend engine.
3. Cloud notes need a real phone with internet: App Check checks the app with Play Integrity, so
   emulators and modified phones are refused. Everything on the phone works without it.

## A five-minute tour

The app has three tabs at the bottom: **Meetings**, **Receive** and **Settings**.

1. **Settings → On-device AI.** Gemini Nano (on this phone), the ContextFriend engine (on this
   phone) and the speech and speaker models, each with its state. **Get ready for offline notes**
   downloads what is missing. Under **Settings → Cloud assistance**, add `Mila Sonnenberg` and
   `Jonas Testmann` to **Names to protect**, as a teacher would add a class list.
2. **Meetings → New recording.** Choose the summary style **Parent–teacher** and the language
   spoken, tick that everyone knows the conversation is recorded, and tap **Start recording**.
   Let two people read the demo script, or play it from a second device. The level bars show the
   real microphone signal. Tap **Finish recording**.
3. Notes start by themselves, on the phone: **Creating your notes** shows the real steps (speech
   to text, speakers, notes). The label under the title says where the notes were made, for
   example **Created on this phone · Gemini Nano**. It comes from the `inferenceSource` Firebase AI
   Logic reports for every request, not from an assumption. *(checked on a Pixel 11 Pro, 26 Nov)*
   A recording without speech ends as **No speech detected**, never as an empty summary.
4. **Turn on airplane mode** and use **⋮ → Create notes again**: the on-device path keeps working.
   *(phone check pending)*
5. Turn the network back on. Under the summary, tap **Improve with Gemini (optional)**. **Review
   what you send** shows the conversation text first, with `[PERSON_1]`, `[EMAIL_1]`, `[DATE_1]`
   highlighted instead of names and identifiers, words that might still be names (tap one to
   replace it too), where the text goes and the exact request. Nothing leaves the phone until you
   press **Send once to Gemini**. The answer is put back together on the phone; the label then says
   **Cloud-assisted · Gemini, names replaced**, and **⋮ → Switch to previous notes** brings back
   the on-device notes. *(phone check pending)*
6. **Mark as reviewed**, then **Share summary → Encrypted link → Create link**. Only the summary
   is shared, encrypted on the phone; Firestore stores only ciphertext for seven days. On a second
   phone: **Receive → Scan code** (or **Paste a link**). *(passed on the emulator, sender and
   receiver on one device, 25 Nov; second phone pending)*
7. **Settings → Privacy activity**: one entry per AI step or link, where it ran, and details (model,
   requests, replaced items, characters sent). Never what was said. **Export log** gives the JSON.

## What to expect without a supported phone

- No Gemini Nano: the app writes notes with the ContextFriend engine (Gemma 4) on the phone and
  says so in the label under the title. It never falls back to the cloud on its own.
- The cloud and cloud links work on real phones with internet (App Check with Play Integrity;
  emulators are refused), always after **Review what you send** or the share screen.

## Automated tests

```bash
python tools/fetch_ci_deps.py
cd android && ./gradlew :app:testDebugUnitTest
```

90 JVM unit tests on synthetic data: redaction (names, genitive and hyphenated forms, honorifics,
e-mail, phone, dates, addresses, IBAN, IDs, school names), re-identification, routing policy
(automatic routing never picks the cloud; source mismatches are policy violations), the full notes
pipeline with a fake model, and proofs that the cloud model is never called without consent or
with the kill switch on. They run on every push ([CI](../.github/workflows/ci.yml)).
