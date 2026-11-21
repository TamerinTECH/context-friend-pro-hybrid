# Decisions and validation boundaries

2026-10-27. Specification: ORIGINAL_BRIEF.md, amended by Ron in this conversation.

## Product

ContextFriend Pro is Ron's native offline meeting recorder for 30–60 minute meetings.
Separate repository, two native presentation layers, portable model/runtime and
transfer contracts. No dependency on Central, Box, school permissions or accounts.
Core languages: English, German, Hebrew. Approved content is the sharing default.

## Voice profiles amendment

Ron explicitly requests recognition across meetings AND across devices. Profiles
are local encrypted records by default. Sharing a profile uses a separate `profiles`
bundle purpose, dedicated selection/preview and a sensitive-data confirmation on
both devices. A meeting bundle cannot contain embeddings or enrollment audio.
An imported compatible profile is a suggested identity, never authentication.
Deletion is local; an already accepted copy on another device is independent.
An embedding model or preprocessing change requires re-enrollment, not reinterpretation.

## Platforms

Android API 31 minimum; API 36 compile/target initially, native Kotlin/Compose.
SwiftUI deployment target iOS/iPadOS 16.0 (explicit change from brief's 17+ to permit
iPhone X reader/recording testing). Inference capability is separate from OS support.
Pixel 11 Pro and iPad Air 2026 are reference candidates, not yet qualified devices.
iPhone X is initially reader/recorder only; do not load a large language model there.
Native libraries must compile for iOS 16; API availability is checked at call sites.
No local Mac is connected. The authorized AWS Mac now compiles simulator and device
packages with Xcode 26.1.1. Personal-device signing is still not configured.

On 2026-10-27 Ron authorized low-cost AWS builds and Device Farm tests through
profile `mbot`, with US regions acceptable. This is developer infrastructure only;
it adds no AWS dependency to the app. See AWS_BUILDS.md for resource state,
cleanup, reproducible commands and actual outcomes. Mac capacity availability,
compilation, simulator tests and physical-device tests remain separate evidence.

## Runtime strategy

whisper.cpp for multilingual ASR; llama.cpp for portable GGUF summarization, with
sherpa-onnx for real diarization and speaker embeddings. LiteRT-LM's Swift API is
currently Early Preview, so the C/C++ path permits one auditable inference bridge
on both platforms. Final models are pinned only after artifact hashes/licenses are
verified. Selection is provisional until natural-speech and phone benchmarks pass.
Sequential inference, bounded audio windows/context, cooperative cancellation and
durable stage checkpoints. Context exhaustion is an error, never a complete summary.

## Storage and sharing

Encrypted atomic records and bounded encrypted audio chunks avoid plaintext SQL
journals and audio temporary files. Platform-protected wrapping keys; per-session
content keys. Sensitive records and model files excluded from backup.
One versioned recipient-encrypted bundle shared over QR, BLE and temporary LAN
sessions. Invitation is recipient-authenticated by physical QR; sender identity is
unverified unless separately pinned. Approval is revision-specific.

## Evidence

Upstream references are recorded in THIRD_PARTY_NOTICES.md. No inference performance,
speaker matching threshold, language quality, radio interoperability or hardware
key-security claim is qualified by source code alone.

## #BuildHybridAI amendment (Ron, 21–22 November 2026)

This repository is the public hackathon edition, entered by TamerinTECH GmbH with Ron as
Representative. It supersedes the "no remote inference" boundary above in one way only:
cloud processing is now possible, but always opt-in, per request, after local redaction.

- **Lead audience:** schools (parent–teacher conferences, support-plan and counselling
  meetings). Medical and legal meetings are additional templates, not the headline.
- **Platform:** Android first, reference device Pixel 11 Pro (Gemini Nano 4 via the AICore
  Developer Preview). iOS only if time remains.
- **Generative AI goes through Firebase AI Logic.** Meeting notes use Gemini Nano with
  `ONLY_ON_DEVICE`. The prototype's Gemma 4 engine stays as the local engine for languages
  Nano is not validated for (on-device validation covers English and Korean only) and for
  phones without Nano. Transcription and speaker separation stay on our own engines,
  because Gemini Nano on Android accepts no audio.
- **Cloud:** Gemini through Firebase AI Logic on the Vertex AI (Agent Platform) backend in
  an EU region, Blaze plan with a budget alert. The free Gemini Developer API tier is not
  allowed for apps offered to EEA users (Gemini API Additional Terms, effective 23 Mar 2026).
- **Firebase services:** App Check (debug provider in debug builds, Play Integrity for
  release), anonymous Authentication, Remote Config for routing policy and model names,
  Firestore only for end-to-end-encrypted shares (the server stores ciphertext).
- **Repository:** new public repo with the prototype imported as one disclosed commit
  (docs/PRE_EXISTING.md); the private prototype repository stays untouched.
