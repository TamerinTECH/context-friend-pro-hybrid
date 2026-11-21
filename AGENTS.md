# ContextFriend Pro Hybrid — agent guide

Public, Apache-2.0 repository for TamerinTECH GmbH's #BuildHybridAI 2026 entry
(deadline Mon 30 Nov 2026, 14:00 PT = 23:00 Berlin). Read README.md, docs/DECISIONS.md and
docs/PRE_EXISTING.md before editing. The native prototype in the first commit is disclosed
pre-existing work; everything after it is hackathon work and goes into CHANGELOG.md.

## Hard rules

- **Hackathon requirement:** generative AI runs through Firebase AI Logic. On-device it is
  Gemini Nano (Android) or Apple Foundation Models (iOS). Our own engines (whisper.cpp,
  sherpa-onnx, Gemma via llama.cpp) do what those cannot (speech, speakers, languages
  Nano is not validated for). Never present Gemma output as Gemini output.
- **Meeting content is sensitive by definition.** It goes to Gemini Nano only with
  `InferenceMode.ONLY_ON_DEVICE`, never a `PREFER_*` mode, which falls back to the cloud
  silently. Check `inferenceSource` on every response; an `IN_CLOUD` answer to an
  on-device request is discarded and logged as a policy violation.
- **Cloud is opt-in per request:** names and identifiers are replaced locally first, the
  user sees the exact outgoing text and consents, and the result is re-identified on the
  device. The placeholder mapping lives in memory for one request only.
- **The audit log holds metadata only:** never transcript, summary or names.
- **No personal data anywhere in the repo, tests, screenshots or video.** Use clearly
  fictional names and `example.org` addresses. Demo audio is a fictional script read by
  real people, never text-to-speech.
- **No secrets in Git:** Firebase options come from untracked `android/local.properties`;
  never commit `google-services.json`, App Check debug tokens, keystores or `.env*`.
- **Don't claim untested results:** keep implemented, built, emulator-tested and
  physical-device-tested distinct, as in docs/TEST_REPORT.md.
- **Ron does every external action:** Devpost, YouTube, store tracks, social posts.
  Agents prepare drafts.

## Identity and infrastructure

Commits use Ron's GitHub noreply address (repo-local git config). Remote:
`TamerinTECH/context-friend-pro-hybrid`, pushed as `tamerin-tech`.
Firebase: Blaze plan, Vertex AI (Agent Platform) backend in an EU region; the Gemini API
terms allow only paid services for apps offered to users in the EEA, UK or Switzerland.
Stay within a few euros; no new paid services without Ron.

## Build

See docs/BUILD.md. Local builds reuse native sources in `.deps/` (a junction to the
prototype checkout on Ron's machine; `python tools/bootstrap.py` recreates it elsewhere).
