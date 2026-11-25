# Demo video script (≤ 3:00, English)

Record the **real app on a Pixel** (screen recording: turn on Settings → *Allow screenshots and
screen recording* first) plus a few shots of the phone on a table. Only the fictional demo meeting
([demo/parent-teacher-conference.md](demo/parent-teacher-conference.md)), read by two real people.
No mocked screens, nothing that isn't built. Label the data "fictional demo" on screen once.
Upload to YouTube as public; English voice-over or English subtitles.

| Time | Picture | Voice-over (suggested) |
|---|---|---|
| 0:00–0:15 | Teacher's desk, stack of notes; phone on the table | "A teacher in Germany has a dozen conversations a week about children. Writing them up takes evenings. AI could do it, but these conversations can't go to a cloud chatbot." |
| 0:15–0:30 | App opens → **Settings → On-device AI**: Gemini Nano, ContextFriend engine, speech models | "ContextFriend Pro writes meeting notes on the phone. Gemini Nano through Firebase AI Logic, plus our own on-device speech and speaker engines. The cloud only when you ask." |
| 0:30–0:50 | **New recording** → summary style Parent–teacher → Start → full-screen recording with level bars while two people read the demo script (cut to 10 s) → **Finish recording** | "We record a parent–teacher conference. Fictional, of course." |
| 0:50–1:20 | **Creating your notes** starts by itself (real steps) → summary with source links → label **Created on this phone · Gemini Nano** → tap a source: transcript with speakers | "Transcription and speaker separation run on our engines. The notes come from Gemini Nano, with ONLY_ON_DEVICE. The badge reads Firebase AI Logic's inferenceSource for every request. Every statement links back to what was said." |
| 1:20–1:35 | Swipe down: airplane mode on → ⋮ → Create notes again → still works | "It even works in airplane mode." ⚠ only if verified |
| 1:35–2:10 | **Improve with Gemini** → **Review what you send**: highlighted `[PERSON_1]`, `[EMAIL_1]`, `[DATE_1]`, tap a hinted word, where it goes → **Send once to Gemini** → notes with real names, label **Cloud-assisted · names replaced** | "If you want better notes, the phone replaces names, e-mails, dates and addresses first. You see exactly what would leave the phone, and nothing is sent without your consent. Gemini in the EU writes the notes, and the phone puts the names back." |
| 2:10–2:25 | **Mark as reviewed** → **Share summary → Encrypted link** → QR → second phone: **Receive → Scan code** | "Share with a colleague phone to phone, or through an encrypted link. The server only ever stores ciphertext." |
| 2:25–2:40 | **Settings → Privacy activity**; Remote Config kill switch → "Switched off: local-only mode" | "A privacy log shows where every request ran, never what was said. And one switch puts every phone into local-only mode." |
| 2:40–2:55 | Architecture diagram from docs/HYBRID_ARCHITECTURE.md (5 s) | "Hybrid inference as a privacy boundary: per-task inference modes, a deterministic redaction gate, App Check, Remote Config, Firestore — all in the EU." |
| 2:55–3:00 | App icon + repo URL | "ContextFriend Pro. AI for every teacher, without trading away a child's privacy." |

Before recording: check every claim against the app on that day, cut a scene rather than fake it,
and keep the video under 3:00 (only the first three minutes are judged).
