# Testing instructions

> **Draft, 22 Nov 2026.** Steps marked *(phone check pending)* are implemented and unit-tested but
> not yet run on a Gemini Nano phone. They are confirmed or corrected before submission.

For judges and anyone trying ContextFriend Pro Hybrid. No account, login or personal data is
needed. Use only the fictional demo meeting in [demo/parent-teacher-conference.md](demo/parent-teacher-conference.md)
or your own test speech, never a real meeting.

## What you need

| To see | You need |
|---|---|
| Recording, transcription, speakers, local notes (ContextFriend engine) | Any Android 12+ phone with 8 GB RAM. Install the models under **Models** (about 3.6 GB, once, on Wi-Fi). |
| Notes by **Gemini Nano on the phone** (Firebase AI Logic) | A phone on the [Gemini Nano 4 list](https://developers.google.com/ml-kit/genai), for example Pixel 10 or 11, with the AICore Developer Preview turned on (below). |
| **Cloud notes** with names replaced, and **encrypted cloud links** | Internet. Both are opt-in and always show what leaves the phone first. |

### Turn on Gemini Nano 4 (Pixel 10 / 11)

1. Settings → your name → All services → System services → **Android AICore** → Update.
2. Turn on Developer options: Settings → About phone → tap **Build number** seven times.
3. Settings → System → Developer options → **AICore Settings** → **Enable AICore Developer Preview** → Accept.
4. In ContextFriend Pro, open **AI & privacy**. The Gemini Nano card shows **Ready**, or tap
   **Download Gemini Nano** (Wi-Fi, a few minutes).

## Install

*(The first release is published before submission.)* Download the APK from the repository's [Releases](https://github.com/TamerinTECH/context-friend-pro-hybrid/releases)
and install it (allow installs from your browser or file manager). The app is free.

## A five-minute tour

1. **AI & privacy.** Three engines with their status: Gemini Nano (on this phone), ContextFriend
   engine (on this phone), Cloud (only when you ask). Add `Mila Sonnenberg` and
   `Jonas Testmann` under **Names to protect**, as a teacher would add a class list.
2. **Meetings → Start recording.** Confirm that participants were informed, then let two people
   read the demo script, or play it from a second device. Stop.
3. Choose the meeting type **Parent–teacher** and tap **Process locally**. Transcription and speaker
   separation run on the phone, then the notes. The badge under the title shows where the notes
   were written, for example **🔒 On this phone · Gemini Nano · 3 requests**. It comes from the
   `inferenceSource` Firebase AI Logic reports for every request, not from an assumption. *(Gemini Nano path: phone check pending)*
4. **Turn on airplane mode** and process again: the on-device path keeps working. *(phone check pending)*
5. Turn the network back on and tap **Improve with cloud**. The privacy panel shows the exact
   text that would leave the phone: `[PERSON_1]`, `[EMAIL_1]`, `[DATE_1]` instead of names and
   identifiers, how many items were replaced, and words that might still be names (tap one to
   replace it too). Nothing is sent until you tick the consent and press **Send**. The answer is put
   back together on the phone; the badge then shows **☁ Cloud · Gemini, names replaced**. *(phone check pending)*
6. **Encrypted cloud link** on the summary: the notes (never transcript or audio) are encrypted
   on the phone; Firestore stores only ciphertext for seven days. Open it on a second phone with
   **Open cloud link** (scan the QR or paste the `CFS1:` text). *(phone check pending)*
7. **AI & privacy → Privacy log**: one line per AI request, where it ran, how many items were
   replaced, how many characters were sent. Never what was said. **Export log** gives the JSON.

## What to expect without a supported phone

- No Gemini Nano: the app writes notes with the ContextFriend engine (Gemma 4) on the phone and
  says so in the badge. It never falls back to the cloud on its own.
- The cloud and cloud links work on any phone with internet, always after the privacy panel or
  share dialog.

## Automated tests

```bash
python tools/fetch_ci_deps.py
cd android && ./gradlew :app:testDebugUnitTest
```

75 JVM unit tests on synthetic data: redaction (names, genitive and hyphenated forms, honorifics,
e-mail, phone, dates, addresses, IBAN, IDs, school names), re-identification, routing policy
(automatic routing never picks the cloud; source mismatches are policy violations), the full notes
pipeline with a fake model, and proofs that the cloud model is never called without consent or
with the kill switch on. They run on every push ([CI](../.github/workflows/ci.yml)).
