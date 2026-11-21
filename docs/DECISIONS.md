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
