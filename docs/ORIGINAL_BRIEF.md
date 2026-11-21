# ContextFriend Mobile: Implementation Prompt for a Coding Agent

Version: 1.0  
Prepared: 27 October 2026  
Deliverable: a working Android and iPhone prototype with honest, reproducible emulator and physical-device validation.

Copy everything from “Your role and mission” to the end into the coding agent. Give the agent access to the intended repository. This document requests implementation, not another product proposal.

## 1. Your role and mission

Act as the lead mobile engineer, on-device AI engineer, and test engineer for ContextFriend.

Build an installable prototype for BOTH Android and iPhone. ContextFriend records conversations, transcribes them, separates speakers, generates an editable summary, and stores everything encrypted on the user's device. It can share explicitly selected information directly with another device through Bluetooth Low Energy, actual QR payloads, or a local network. There is no central server.

The primary workflow is:

**Record → process locally → review and correct → approve a version → optionally share selected information.**

This is a professional companion product in the ContextFriend family. It should have its own mobile workflow. Do not assume the school product's backend, hardware, account system, or deployment is available.

Implement the software and run the tests you can actually execute. Do not stop after architecture, scaffolding, screenshots, mocked inference, or a plan. Do not describe a feature as working until the relevant implementation and validation exist.

### Product boundaries

- No built-in chatbot, conversational agent, RAG, or vector search.
- No cloud transcription, cloud summarization, remote inference, or silent network fallback.
- No central backend, account registration, mandatory login, cloud database, push-notification dependency, hosted signaling, analytics, advertising SDK, or remote crash-reporting SDK.
- No continuous ambient listening, covert recording, automatic recording after reboot, or call-recording interception.
- No medical diagnosis, legal advice, clinical automation, or claims of regulatory certification.
- No billing, organization administration console, internet-scale synchronization, or special hardware in this prototype.
- Persistent voice profiles are an optional user-enabled feature. They are not authentication credentials or proof of identity.
- An external app may receive a user-approved export through an explicit OS share action. This is a separate trust boundary, clearly explained before export.

## 2. Define “local” and “no central server” precisely

All recording, transcription, speaker processing, summary generation, editing, storage, and normal playback must work on the phone without internet after the required model files are installed.

Content may leave the originating device ONLY through a deliberate user-approved share/export operation. For built-in sharing, it travels directly to the selected recipient and is encrypted for that recipient.

A temporary listener hosted by one participating phone for a local transfer is permitted. It must exist only during an explicit send/receive session. It is not a centralized backend.

The network implementation must work on an isolated Wi-Fi LAN or a user-configured hotspot without internet. Do not assume Android Wi-Fi Direct interoperates with iOS, or that AirDrop/Multipeer Connectivity provides an Android transport. Do not require automatic hotspot creation.

Do not implement a public relay, STUN/TURN dependency, cloud rendezvous, public discovery service, public DNS requirement, or automatic router port forwarding. Arbitrary internet transfers across NAT are outside this prototype. A future user-managed routed network or VPN can reuse the direct protocol; do not claim universal remote reachability.

Model downloads are a separate, explicit setup operation. Support local model-pack import so an installation can be provisioned without the phone accessing the internet. Model setup must never upload user content.

Do not advertise “100% secure” or “100% private” as a verified absolute. Use precise statements such as “Processed on this device” and “No content uploaded by ContextFriend,” supported by the implementation and tests.

## 3. Inspect the environment and make bounded decisions

Before modifying code:

1. Read repository instructions, including applicable AGENTS.md files. Inspect existing code and preserve unrelated changes.
2. Inventory the host OS, Android SDK/NDK/JDK, Gradle, Xcode, simulator runtimes, available build runners, signing configuration, disk space, and connected physical devices.
3. Record which builds and tests can run here. A Linux host alone does not establish that an iOS application compiles. Never fabricate Xcode, signing, simulator, or device results.
4. Inspect existing mobile architecture. If a substantial Flutter or React Native application already exists, retain it when practical and implement required native modules. Do not perform an unrelated rewrite.
5. For a greenfield project, default to:
   - Android: Kotlin, Jetpack Compose, native audio/lifecycle/Bluetooth/storage integrations.
   - iOS: Swift, SwiftUI, native audio/lifecycle/Core Bluetooth/storage integrations.
   - Share protocol schemas, model manifests, fixtures, evaluation scripts, cryptographic test vectors, and portable C/C++ inference code where useful.
6. Write a short architecture decision record explaining the chosen stack, model/runtime versions, licenses, supported OS targets, and unresolved hardware risks. Then implement.

Initial compatibility targets are Android 12/API 31+ and iOS 17+, subject to the verified minimum requirements of the selected runtimes. Build against an appropriate current stable SDK and handle its permissions. Record any necessary change to minimum OS support explicitly.

Do not promise that every phone meeting the OS minimum can run all models. App compatibility, receiver-only support, and full local-AI qualification are separate.

Use reasonable defaults and proceed autonomously with reversible local work. Ask only for genuinely missing access, credentials, signing, gated-model terms, or a decision that blocks implementation. Finish all work that does not depend on the missing item first. Do not purchase services, publish applications, or upload recordings.

## 4. Architecture and state

Keep the implementation understandable. Do not build a new cross-platform framework or a general model marketplace.

Separate these responsibilities behind small interfaces:

| Component | Responsibility |
| --- | --- |
| Recording coordinator | Native capture, interruptions, timestamps, encrypted chunk persistence |
| Vault | Protected keys, encrypted records, retention, deletion, recovery |
| Processing queue | Durable stage state, cancellation, checkpoints, retry, resource limits |
| ASR engine | Local timestamped transcription |
| Speaker engine | Local diarization and optional profile matching |
| Summary engine | Local structured summary with source references |
| Model manager | Verified installation, local import, compatibility, removal |
| Transfer bundle | Versioned portable content and cryptographic envelope |
| Transport adapters | BLE, QR payloads, direct LAN |
| Presentation | UI observes state and sends explicit user actions |

Suggested persisted processing states: recorded, queued, transcribing, diarizing, summarizing, needs_review, approved, interrupted, failed, cancelled. Preserve separate stage outcomes so one failure does not erase a successful transcript.

Use transactional checkpoints and idempotent stage completion. Capture event times using a monotonic clock; retain a wall-clock origin and timezone for display. Device clocks are not trusted timestamp authorities.

Native recording must not depend on a UI timer, a screen staying open, JavaScript execution, or a view/activity remaining alive.

## 5. Screens and user experience

Build a clear, professional interface with accessible controls, readable typography, and restrained ContextFriend blue/yellow accents. Prioritize working flows over decorative animation.

Required screens:

1. **Setup and capability check:** explain local processing, request permissions when needed, inspect model availability and storage, select language, install/import models.
2. **Sessions:** local recordings and received items, processing status, local search by metadata if useful, delete action.
3. **Recording:** explicit start/pause/resume/stop, elapsed time, microphone level, visible recording state, interruption/gap notices.
4. **Session detail:** audio playback when retained, timestamped transcript, speaker labels, editable summary, processing controls.
5. **Review and approval:** correct transcript/speaker labels, edit summary, inspect referenced segments, approve a specific version.
6. **Send/receive:** choose selected fields, preview the exact payload, select Bluetooth/QR/local network, verify recipient, show progress and result.
7. **Voice profiles:** explicit enrollment, rename, delete, disable matching.
8. **Settings:** app lock, audio retention, model storage, trusted devices, local diagnostics, explicit external export.

Implement English, German, and Hebrew UI strings for core flows. Hebrew needs RTL layouts and correct mixed-direction rendering of timestamps, numbers, filenames, and technical identifiers. Keep translated content separate from code.

Provide accessible labels for screen readers and sufficient touch target sizes. Never make color the only status indicator.

No feature should display generated placeholder results as if they came from a real model. Any demo data must have an unmistakable “Demo data” label and remain outside normal user sessions.

## 6. Reliable local recording

Use native microphone capture: Android AudioRecord or an appropriate native equivalent; iOS AVAudioSession with AVAudioEngine or an appropriate native equivalent.

Normalize model input to the selected ASR requirements, typically mono 16 kHz PCM. Preserve accurate timing and document capture/resampling choices. Do not assume the microphone's actual sample rate equals the model sample rate.

Encrypt bounded audio chunks before writing them to persistent storage. Avoid recording an entire plaintext WAV/M4A and encrypting it afterward. Use a bounded producer/consumer queue so encryption and disk work do not block the audio callback.

Choose and document a short durable checkpoint interval, initially at most five seconds. On an abrupt process termination, preserve all committed chunks and identify any lost/uncommitted tail. Never silently stitch over an interruption.

Support:

- Start, pause, resume, and stop initiated by the user.
- Screen-locked recording within supported OS rules.
- Calls/alarms, audio-route changes, microphone revocation, storage exhaustion, low-memory events, and app/process termination.
- Restart recovery that distinguishes incomplete recordings from complete ones.
- Playback from encrypted storage without persistent plaintext audio copies.
- Clear indication of gaps and unavailable segments.

On Android, use a microphone foreground service started from a visible user action, with the appropriate permissions, service type, and a visible notification including Stop. Do not use a microphone service as a fake keep-alive after recording ends.

On iOS, configure the audio session and supported background audio mode correctly. Handle interruptions and media-service resets. Do not promise automatic recording restart after force-quit or reboot.

Record only after a clear user action and acknowledgement that participants must be appropriately informed. The app must not claim this acknowledgement establishes legal consent in every jurisdiction.

## 7. Model installation, capability checks, and resource use

Integrate actual local models. Model file size is not the same as total runtime RAM.

Start evaluation with these candidates, then pin a proven combination:

- ASR: multilingual Whisper small through whisper.cpp, with a smaller multilingual variant available for reduced-capability testing. Do not use an English-only “.en” model for German/Hebrew.
- Summarization: Gemma 4 E2B through a verified compatible LiteRT-LM artifact. Check the current Swift integration maturity before committing to it. A tested C++ bridge or a tested llama.cpp/GGUF alternative is acceptable if needed.
- Diarization: sherpa-onnx with a compatible segmentation model and speaker-embedding model. Its documented pyannote segmentation-3.0 and TitaNet Small route is a candidate. Verify the actual weight licenses and redistribution terms separately.

These are starting candidates, not permission to assume support or benchmark results. Complete an early inference spike on BOTH platforms. Ship one practical backend per task/platform. If changing a candidate, document why, demonstrate the replacement locally, and keep the privacy requirements.

Do not depend exclusively on Apple Intelligence or Android's system generative APIs. Their availability, supported languages, or execution restrictions may differ across devices. Never silently route to Private Cloud Compute or a remote provider.

For every model pack, record:

- Model ID, upstream source, exact revision, language scope.
- Runtime/version, format, quantization, supported backend.
- Exact byte size and SHA-256 obtained from the real artifact.
- License and required notices/attribution.
- Compatibility requirements and measured results when available.

Never invent hashes or licenses. Do not bypass gated terms. Support explicit downloads with progress/cancellation/resume and integrity checking, plus offline import of a documented pack. Verify against a trusted pinned manifest; a hash supplied only by the same untrusted import is not authenticity verification.

Install atomically. Reject corrupt, incomplete, incompatible, or unexpected artifacts before loading. Do not execute code from model packs. Exclude model files from automatic backup and allow removal without deleting sessions.

Provisional qualification candidates: an Android phone with 8 GB RAM, preferably 12 GB, and a recent iPhone such as iPhone 15 Pro or newer with adequate free storage. Treat these as devices to measure, not validated minimum requirements. Include a lower-capacity available device to characterize failure/degradation.

Use record-first, process-after-stop as the baseline. Live transcription is optional after the complete offline pipeline works. Run ASR, diarization, and summarization sequentially by default; release heavyweight models between stages. Bound context, batch size, audio windows, memory, and concurrency. Respond to thermal and memory pressure by pausing or reducing a documented workload, never by switching to a cloud service.

Run post-recording inference in the foreground first. Supported OS background-task APIs may improve convenience, but their time limits, quotas, cancellation, and availability must not affect correctness. Checkpoint work and resume after returning to the app/unlocking. Screen-locked recording does not imply unlimited screen-locked inference.

A phone without models must still receive, decrypt, review, and store a shared summary. Recording-only or reader mode must clearly describe unavailable local-AI features.

## 8. Transcription and speaker processing

Produce a structured transcript with stable segment IDs, start/end times, text, language metadata, and speaker assignment where available. Preserve the original model output and distinguish user corrections.

Support explicit English/German/Hebrew language selection and an automatic mode only if the chosen ASR supports it. Evaluate mixed-language speech. Do not present a language as qualified solely because the model card lists it.

Process long recordings using bounded overlapping windows. Reconcile boundaries without duplicated or missing phrases. Maintain correct time offsets and speaker identities across windows. Preserve the spoken language instead of translating unintentionally. If removing silence for processing, map every result back to the original recording timeline.

Diarization must use audio segmentation/embeddings/clustering or another real local diarization implementation. Do not infer speaker turns by asking the summarizer to guess from transcript text.

Required behavior:

- Anonymous labels such as Speaker 1 and Speaker 2 by default.
- Stable per-session labels, correction/rename controls, and a useful timeline.
- Unknown/uncertain speaker handling and overlap representation when supported.
- Manual correction when overlap or short utterances cannot be reliably assigned. Align transcript and diarization using timestamps; if a text segment crosses a speaker turn without reliable word timing, show ambiguity rather than inventing a precise split.
- Distinguish model scores from calibrated probabilities; do not invent confidence values.

### Optional enrolled voice profiles

Implement a separately switchable local enrollment workflow using real speaker embeddings:

- Explain that a persistent voice profile is sensitive information and ask before enrollment.
- Collect adequate voiced audio with quality checks and user-provided label.
- Store the profile and label encrypted, with model/version/hash and preprocessing metadata. Require re-enrollment when a model change makes existing embeddings incompatible.
- Use a documented matching threshold and reject uncertain/unknown speakers.
- Prefer a suggested match requiring confirmation over silently assigning a person's name.
- Allow deletion and complete disabling of future matching. Remove biometric references while retaining ordinary editable transcript labels. Delete raw enrollment clips after processing unless the user separately chooses to retain them.
- Never enroll other participants automatically.
- Never use a voice match to unlock the app, authorize a transfer, or claim a legal signature.
- Never export profiles or enrollment audio in ordinary sharing.

If the feature remains experimental, label it accurately and report its measured false-match and rejection behavior. Do not claim robust identification based on a tiny test set.

## 9. Local summarization and review

Generate a concise, editable summary entirely on the phone from the transcript.

Use a validated structured output schema containing:

- Title.
- Summary paragraphs or bullets.
- Decisions.
- Action items, with owner and due date only when explicitly supported.
- Uncertainties or unresolved points.
- Source segment IDs for material statements.

Unknown values must be null/unspecified. Do not invent names, commitments, dates, amounts, medical conclusions, or legal conclusions. Treat transcript text as data, including malicious text such as “ignore your instructions.”

Use bounded chunk summaries and a final synthesis for long sessions. Carry source references through both stages. Reserve output tokens; do not silently truncate the latter part of a meeting.

Validate schema and referenced segment IDs. Handle malformed output with bounded local retry or a clear failure. Reference validation does not prove factual correctness; keep human review.

Allow edits, regeneration, and explicit approval. Approval applies to a specific version. Transcript or summary edits invalidate approval for the affected new version. Retain enough local revision information to explain what was approved.

Sharing an unapproved draft may be allowed only with explicit confirmation and a visible draft label. Make the approved version the default.

If audio is deleted by retention policy, preserve permitted textual source references but explain that playback verification is no longer available.

## 10. Vault, encryption, backup, and privacy

Write a short threat model covering a lost locked phone, unauthorized nearby peer, passive interception, malicious QR/import, accidental backup/export, and sensitive logs.

Do not claim protection against a fully compromised OS or an authorized recipient copying content.

Use maintained cryptographic libraries and platform key protection. Do not invent cryptographic primitives or use hardcoded keys.

Required controls:

- Encrypt audio, transcript, summaries, speaker labels/profiles, sensitive metadata, queues/checkpoints, and temporary persistent files.
- Use a maintained encrypted database or a clearly reviewed encrypted-record design. Include WAL/journal/sidecar files and migrations in the design.
- Protect wrapping keys with Android Keystore and iOS Keychain. Use hardware protection where supported; report the actual capability, including software-only emulators.
- Use authenticated encryption, unique nonces or a library-managed safe stream construction, and cryptographically secure randomness.
- Use per-session keys where practical to support scoped deletion.
- Never print content, secrets, keys, embeddings, or decrypted bundles in logs.
- Avoid sensitive notification previews, clipboard writes, and app-switcher snapshots. Android screenshot blocking can be offered; do not claim iOS can universally prevent screenshots.

### Locked-screen recording and key access

Resolve the key-access trade-off explicitly. Obtain and wrap an active recording key while unlocked; retain only the needed active key in native memory during capture. Use a file protection strategy that permits supported ongoing encrypted recording while locked. Do not disable file protection globally.

After interruption/process death, wait for unlock when protected keys/files cannot be reopened. Inference and review may require unlock. Do not weaken storage security to make a background test appear successful.

### Backup and deletion

Disable application cloud backup/sync for sensitive content. Configure current Android backup and device-transfer exclusions, not just one manifest flag. On iOS, exclude vault/model files from backup, avoid iCloud containers, and use appropriate non-synchronizable device-bound key settings. Verify behavior and document any OS/device limits.

Implement per-session deletion and configurable audio retention. Default to retaining encrypted audio for review until the user chooses a retention policy; offer deletion after review/approval. Deletion must remove associated files, database records, embeddings, derived content, queues, and relevant temporary data.

Do not claim forensic overwriting of flash storage. Explain cryptographic erasure where supported and that copies already received/exported cannot be recalled.

No automatic recovery through a vendor cloud. Warn clearly that device-bound storage can be lost with the device or app removal. A manual recovery export is optional and must be separately encrypted; do not expand the prototype into a backup service.

## 11. One portable encrypted bundle for all transports

Implement a versioned .cfbundle format used by BLE, QR, LAN, and optional encrypted file export.

The logical bundle should include only explicitly selected fields:

- Schema version, bundle ID, original session ID, revision ID, creation time.
- Language, title, approved/draft state.
- Selected summary and action items.
- Optional transcript segments and corresponding speaker display labels.
- Optional audio attachments selected separately.
- Provenance of model processing and user edits, without device identifiers unnecessary for the recipient.
- Attachment sizes/hashes and content integrity metadata.

Exclude voice profiles, enrollment audio, unrelated sessions, hidden diagnostics, and device secrets.

Keep identifying content inside encryption. Minimize cleartext framing metadata. Version the envelope and reject unsupported versions safely.

### Pairing and cryptography

Use a simple offline trust bootstrap:

1. The receiving phone displays an invitation QR with its recipient public key, protocol version, random single-use invitation ID/token, short expiry, and only necessary connection metadata.
2. The sender physically scans the recipient's QR and confirms the displayed recipient/fingerprint.
3. Generate a random content key and encrypt the selected bundle with a reviewed authenticated-encryption construction.
4. Wrap the content key for the scanned recipient public key using a maintained public-key encryption implementation.
5. Transfer the encrypted bundle through the chosen transport.
6. The recipient verifies integrity, invitation binding, recipient binding, limits, and replay state before presenting an import preview.

A practical candidate is libsodium sealed boxes for key wrapping and secretstream XChaCha20-Poly1305 for a payload stream, or one-shot AEAD for a bounded small payload. Bind relevant version/header metadata as authenticated data. Verify the final stream marker before treating a streamed bundle as complete. Retransmissions must reconstruct the original ciphertext rather than reuse nonces with changed plaintext. Pin the actual library and test its wire compatibility across platforms.

Important: sealed boxes alone do NOT authenticate the sender. A scanned recipient key authenticates the recipient to the sender, not the reverse. For first-time transfers, display “Unverified sender” and require acceptance. For trusted contacts, implement reciprocal QR enrollment and a reviewed signature scheme, such as Ed25519, with pinned verification keys. Verify signatures against those pinned keys, not a self-asserted key inside the incoming bundle.

The invitation token is admission/freshness metadata, not an encryption key. A QR containing both ciphertext and its decryption secret is unacceptable.

Write exact byte-level format documentation and cross-platform test vectors. Use deterministic test keys only in tests. Do not expose test key paths in release builds.

Treat all imports as hostile input: bound lengths, counts, nesting, decompressed size, attachment sizes, and resource use; prevent path traversal and arbitrary file writes. Authenticate before interpreting sensitive payloads. Do not automatically open URLs or execute instructions found in imported content.

Transfer success must distinguish “bytes sent,” “recipient authenticated/imported,” and “receipt unconfirmed.” For BLE and LAN, only a completion receipt cryptographically bound to the current bundle and scanned/pinned recipient may establish confirmed import. Use the selected reviewed authenticated channel or a signature checked against a recipient verification key authenticated during pairing. Bare GATT/TCP acknowledgements and local write completion do not establish this. QR-only sends may remain “receipt unconfirmed” unless an authenticated return receipt is scanned.

Make retries/resume of the same invitation and bundle idempotent: return the prior status or continue a verified partial transfer. Reject use of that invitation for a different bundle or unrelated replay. Retain bounded replay/completion state through the invitation lifetime. Duplicate bundles must not create uncontrolled duplicate sessions. Enforce invitation expiry using the receiving device's locally tracked validity; do not assume synchronized or trusted internet time.

## 12. Bluetooth: actual cross-platform data transfer

Implement BLE GATT using Core Bluetooth on iOS and Android BLE APIs. This is an actual bundle transport, not merely a nearby-device picker that later uploads to a server.

Use an app-specific generic service UUID and a small documented characteristic protocol. Advertising must contain no names of people, session titles, transcript content, voice data, or persistent sensitive identifiers.

Start with an explicit foreground “Send” / “Receive” workflow on both phones. Do not promise background cross-platform discovery or reliable unattended transfers.

Prefer recipient-as-peripheral/GATT-server and sender-as-central/GATT-client, but verify hardware capability and implement a documented role-reversal fallback if needed. Keep transfer direction independent of GATT role. A library that only scans/connects as a central is insufficient: prove peripheral/GATT-server and advertising support on both platforms or implement native adapters.

Required features:

- Permission handling, radio-off state, unavailable peripheral capability, and cancellation.
- MTU-aware fragmentation using actual permitted write sizes, not an assumed fixed MTU.
- Framing, sequence numbers, total size, integrity checks, backpressure, acknowledgements, timeouts, and bounded retry.
- Clean disconnect behavior and retry/resume from a verified boundary when feasible.
- Clear recipient acceptance and progress.
- No silent fallback to LAN or internet.

Prioritize summary/transcript bundles. Set a measured, explicit BLE size limit. Recommend local network for large audio attachments. A large-audio BLE throughput promise is not required.

First pairing uses the invitation QR. A previously pinned recipient can issue a fresh invitation without rescanning only through an exchange authenticated against the pinned recipient key. If that exchange is not implemented, request a fresh QR scan rather than silently weakening verification. This does not replace the separate actual-data QR mode below.

Required interoperability: Android → iPhone and iPhone → Android. Also test Android → Android and iPhone → iPhone when the necessary devices exist. Missing devices must remain explicit unverified rows.

## 13. QR: actual content transfer with radios disabled

Implement TWO clearly distinct QR functions:

**A. Pairing QR:** recipient invitation, keys/fingerprint and optional local connection metadata.

**B. Data QR:** the actual recipient-encrypted bundle bytes, with no URL fetch, network request, Bluetooth dependency, or hidden server.

For data QR:

- Use a single symbol when the complete encrypted payload fits.
- For larger text, implement bounded multipart QR frames with manual next/previous controls and optional automatic cycling.
- Include protocol version, transfer ID, frame index/count, declared total size, and integrity information.
- Collect duplicates/out-of-order frames safely and show which frames are missing.
- Validate all framing and reassembled authenticated payload before import.
- Apply strict limits and discard incomplete expired sessions.
- Provide a clear error when the selection is too large, recommending BLE/LAN or a smaller selection.

A QR symbol has limited capacity; the theoretical maximum is not the usable encrypted application payload. Choose and document conservative frame payload sizes based on scanner/camera tests, accounting for encoding, headers, error correction, and UTF-8 Hebrew.

Start with approximately 500–800 payload bytes per frame and a bounded maximum such as 64 frames, then tune from evidence. These are engineering starting points, not QR standard limits. Do not mistake the standard's structured-append limit for the capacity of your application-level multipart protocol.

If using compression, compress before encryption, use a maintained codec, and cap decompression output. Do not depend on compression for correctness or promised capacity.

Use this mode for short summaries and selected text. Do not attempt to transmit a full meeting audio recording through hundreds/thousands of QR frames.

Prove both directions with Wi-Fi, cellular, and Bluetooth disabled. The receiving device should need only its installed app, camera, keys, and local storage, not installed AI models.

## 14. Optional network path: direct local transfer

Implement an explicit local-network transport for larger bundles. It must work without a WAN connection or internet route.

One phone hosts a temporary local listener; the other connects using invitation QR metadata. Bonjour/mDNS discovery may improve usability, but direct address/port from the QR and manual entry must work when multicast discovery is unavailable.

Use the same recipient-encrypted bundle and authenticated peer/trust rules. Protect interactive control/receipts with a reviewed authenticated channel. If using TLS, verify/pin the peer certificate/public key from pairing. Never ship “accept all certificates,” downgrade protection, or weaken global transport security settings.

Bind to the intended local interface, use a temporary port, restrict requests to the current transfer, rate-limit malformed requests, and close the listener on cancel/completion/timeout. Do not expose a filesystem browser or persistent HTTP admin endpoint.

Handle iOS local-network privacy descriptions and declared Bonjour service types. Handle current Android local-network permissions for the chosen OS/target SDK; do not assume the INTERNET permission is sufficient forever. Explain that Android's network permission is also needed for LAN sockets and does not itself imply a central server.

Test an isolated router and a manually configured hotspot if available. Guest Wi-Fi/client isolation or firewall rules may block direct peers; diagnose and offer BLE/QR. Do not bypass network policy.

## 15. External export and recipient experience

A received item must be readable without downloading an ASR/LLM model. Preserve sender approval status and provenance; do not automatically re-run AI on received content.

Provide an explicit selected-summary export through the OS share sheet or file export. Warn that the selected recipient app may store/upload the content under its own rules. Do not automatically send anything to a chatbot or email service.

Default external export to the approved summary only. Audio, transcript, and attachments require separate selections. Clearly distinguish plaintext export from an encrypted .cfbundle.

Receiving a bundle creates a local independent copy. Do not promise remote deletion, reliable recall, global revocation, or automatic synchronization without a central authority.

## 16. Testing architecture and truthful reporting

Maintain a matrix for every platform/feature with these separate states:

- Implemented.
- Compiled.
- Emulator/simulator exercised.
- Physical-device verified.

Use test statuses PASS, FAIL, BLOCKED, NOT RUN, and NOT APPLICABLE, with reasons and evidence. “Mocked test passed” is not a hardware or inference pass.

Run real builds and meaningful tests. Use mocks for deterministic failures, protocol simulations, and UI testing; keep them out of normal product flows. The release application must never silently substitute fake transcript/summary/diarization output.

### Build and environment matrix

| Environment | Required evidence |
| --- | --- |
| Android emulator | Actual build/install/start, UI flow, fixture import, vault/processing/transfer logic |
| iOS simulator on a real Mac/Xcode host | Actual build/install/start and corresponding functional tests |
| Physical Android phone | Real microphone, locked-screen recording, native inference, BLE/QR/LAN, resource metrics |
| Physical iPhone | Same native/hardware validation, with actual signing/build configuration |
| Android + iPhone pair | Actual BLE, QR, and LAN transfers in both directions |
| Older/lower-capacity phone if available | Clear supported/degraded/unsupported behavior without crash or cloud fallback |

An emulator/simulator may support useful audio or transport simulations, but it does not establish room-microphone quality, radio interoperability, battery use, thermal performance, hardware-backed key protection, or actual OS lifecycle behavior on a phone.

If a runtime lacks a compatible simulator binary, provide a clearly labeled simulator adapter for UI/protocol tests and retain real on-device integration. Mark simulator inference unsupported; do not call it a real model pass.

If Mac access, signing, devices, or a model entitlement are unavailable, complete the implementable work and runnable tests, then provide exact remaining steps and blockers. Do not mark the overall result “fully tested” until required physical tests have actually run.

## 17. Required test cases

### Functional and recovery tests

- Fresh launch without internet and without models: clear setup/reader behavior.
- Install/import a valid model pack; reject corrupt, truncated, incompatible, and hash-mismatched packs.
- Record, pause, resume, stop, play back, transcribe, diarize, summarize, edit, approve, share, receive, and delete.
- Deny/revoke microphone, camera, Bluetooth, and local-network permissions.
- Handle empty audio, silence, noisy audio, overlap, and unsupported language/device.
- Restart during capture and each processing stage; preserve committed data and report incomplete work.
- Cancel processing; restart/resume without duplicating transcript segments.
- Exhaust disk space and simulate low-memory/thermal warnings without corrupting existing sessions.
- Edit an approved summary and verify approval invalidation.
- Delete audio after review and verify summary/source-reference behavior.
- Ensure a model-free receiving app can open the shared summary.

### Language and speaker evaluation

Create a small licensed or consented corpus of natural human speech in English, German, and Hebrew, with reference transcripts, speaker turns, and expected key facts. Include at least one two-speaker recording per language, mixed-language content, names/numbers/dates, noise, silence, and overlap.

Fixtures generated using TTS are useful for automation but must be labeled synthetic and cannot replace natural speech quality evaluation.

Report WER/CER with documented normalization, diarization error with its collar/overlap policy, incorrect speaker matches, and summary omissions/unsupported claims. Distinguish acoustic/transcription errors from summarizer errors.

Include adversarial transcript content that looks like instructions. It must not cause code execution, network use, data export, or unsupported summary claims.

Use a manually reviewed held-out subset. Source IDs are not a substitute for checking whether summary statements are actually supported.

### Sharing and adversarial tests

For the common bundle and each relevant transport, test:

- Android ↔ iPhone round-trip with exact content preservation, including Hebrew.
- Wrong recipient key, modified ciphertext, modified authenticated header, invalid signature.
- Unverified sender handling and trusted-sender signature validation.
- Expired/reused invitation and replayed bundle.
- Truncated, duplicate, out-of-order, missing, conflicting, and oversized frames/chunks.
- Disconnect, cancellation, retry, app restart during transfer, and incomplete import cleanup.
- Unsupported schema/crypto version, malicious filenames, path traversal, decompression bomb, excessive nesting/counts.
- No automatic navigation to an imported URL.
- No disclosure of unselected audio/transcript/profile fields.
- Sender state distinguishes delivery acknowledgement from unconfirmed receipt.

Prove QR-only transfer without any radio. Prove BLE content transfer without Wi-Fi/cellular. Prove LAN transfer on a router/hotspot without internet, with BLE disabled where practical.

### Privacy and storage tests

- Inspect app-owned persistent files, database sidecars, temp files, diagnostics, and backups for plaintext test markers.
- Verify app-lock behavior, screen-lock recording, key unavailability, and interrupted-session recovery.
- Verify the configured backup/transfer exclusions and document what the platform allows you to observe.
- Check that deletion removes associated records/files/derived data and does not leave readable exports created internally.
- Audit dependencies for automatic networking/telemetry.
- Observe app-attributed traffic or use an isolated network/firewall during normal recording/processing and each transfer mode.
- After provisioning, normal capture/processing must generate no app-attributed external connection attempts. Peer discovery/transfer traffic is allowed only during an explicit share session.
- Run a separate explicit model-download test and distinguish its traffic from content processing.
- Report visibility limits; a single packet capture is not proof of the absence of every possible leak.

## 18. Physical-device benchmark and acceptance gates

Use ordinary supported consumer phones, without root/jailbreak or external AI hardware.

Record device model, OS version, build commit/configuration, model hashes, backend, relevant storage/memory data, charging state, and ambient/thermal conditions where observable.

On at least one Android phone and one iPhone, run:

1. A complete short end-to-end recording and all local processing stages.
2. A 30-minute recording with at least 10 minutes screen-locked.
3. An extended 60-minute recording/recovery test, followed by local processing.
4. An interruption test and a storage-pressure test.
5. Actual cross-platform sharing through all three transports.
6. A repeated run to reveal thermal or memory degradation.

Measure cold/warm model load, ASR real-time factor, stage times, total post-recording processing time, peak memory where measurable, storage growth, battery change, thermal state, and transfer throughput. Distinguish CPU-only, GPU, and simulator results. Never present vendor benchmark numbers as your measurements.

Initial engineering goals:

- No unrecoverable loss of committed recording chunks.
- No silent recording gaps or final-looking incomplete summary.
- Responsive UI and visible progress during inference; cooperative cancellation at bounded safe points.
- No OOM or uncontrolled thermal failure on the phone designated “qualified.”
- A 10-minute clean meeting should target a usable local result within 10 additional minutes on the selected reference phone.
- A clean two-speaker baseline should target WER ≤20% for English/German and ≤25% for Hebrew, and DER ≤20% using the declared scoring policy.
- The manually reviewed acceptance set should contain no fabricated action owner, date, decision, or critical number in a summary. Mark uncertainty or leave a field unspecified when unsupported.

These are prototype targets, not validated claims. Report actual results per language/device. If a target fails, investigate and improve within scope or explicitly leave that capability unqualified. Do not weaken thresholds or remove difficult fixtures to manufacture a pass.

## 19. Implementation sequence

Work in vertical slices, keeping both platform projects buildable:

1. **Foundation:** repository/environment report, stack/model decisions, app shells, schema, vault, native recording on both platforms.
2. **First real local result:** actual model installation/import, recorded/imported audio to transcript and summary on both platforms.
3. **Speaker and review workflow:** real diarization, correction, optional enrolled profiles, approval/versioning, retention and recovery.
4. **Common sharing envelope:** recipient invitation, encryption, safe import/export, cross-platform vectors, reader-only experience.
5. **Actual transports:** small-data QR, multipart QR, BLE bundle transfer, LAN transfer. Demonstrate both transfer directions early.
6. **Qualification:** adversarial/recovery/privacy tests, physical benchmarks, release-build checks, packaging, and honest final report.

Do not postpone proving the iOS runtime or Android↔iPhone BLE path until the end. Run narrow technical spikes early, then implement the full workflow.

Do not add organization dashboards, remote administration, distributed databases, chat, custom hardware, or medical templates to fill time while core requirements remain unfinished.

## 20. Required repository deliverables

Provide:

- Complete Android and iOS source projects and pinned dependencies.
- Reproducible setup/build/run instructions for each supported host.
- Android debug APK and release-like test build if the environment supports it.
- iOS simulator build when built on available Mac/Xcode, plus an installable device build only if valid signing/provisioning was actually available.
- Exact instructions for personal iPhone installation, permissions, provisioning renewal limits if relevant, and real-device tests. Never imply an unsigned IPA installs normally.
- Shared schemas, bundle/QR/BLE protocol specifications, model manifest, and cross-platform test vectors.
- Automated tests plus concise manual emulator/device runbooks.
- An offline provisioning runbook covering installation prerequisites, model packs, and first run without WAN.
- Architecture/security notes, third-party software/model licenses, and a dependency inventory.
- A test report and machine-readable benchmark results with actual commands, dates, environments, status, and evidence.
- Screenshots or screen recordings of real app flows using only synthetic/consented test data.
- A clear known-limitations and pending-hardware-validation section.

Suggested documentation files: README.md, ARCHITECTURE.md, PRIVACY_AND_THREAT_MODEL.md, MODEL_MANIFEST.json, PROTOCOL.md, TEST_PLAN.md, TEST_REPORT.md, BENCHMARK_RESULTS.json, and THIRD_PARTY_NOTICES.md. Adapt to repository conventions.

Do not commit large weights, recordings of real clients, private signing material, secrets, or personal device identifiers unnecessarily. Document model acquisition and verification rather than placing gigabytes of weights into Git.

## 21. Final handoff format

Finish with:

1. What works on Android and iOS, separately.
2. Exact commands to build and run.
3. How to install on an Android phone and an iPhone.
4. How to provision models and operate without internet.
5. How to perform Bluetooth, actual-data QR, and LAN transfers.
6. Which devices/languages/models were measured and their results.
7. The implemented/compiled/simulator/device matrix.
8. Failed, blocked, and unrun tests with precise reasons.
9. Material privacy/security limits and the next concrete action needed.

Never say “complete,” “fully offline verified,” or “fully tested on both platforms” when the corresponding evidence is missing. Preserve useful completed work if hardware access blocks the remaining validation.

Start by inspecting the repository and environment, write the short decision record, and then implement the first working vertical slice.

## 22. Primary implementation references

Verify the current versions and requirements while implementing. These are technical starting points, not substitutes for compilation, device testing, or checking artifact licenses.

- Android Bluetooth permissions: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- Android BLE GATT transfer: https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data
- Android foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android foreground-service start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android local-network permissions: https://developer.android.com/privacy-and-security/local-network-permission
- Android backup behavior: https://developer.android.com/identity/data/autobackup
- Apple Core Bluetooth sample: https://developer.apple.com/documentation/corebluetooth/transferring-data-between-bluetooth-low-energy-devices
- Apple local-network privacy: https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy
- Apple background processing: https://developer.apple.com/documentation/backgroundtasks/performing-long-running-tasks-on-ios-and-ipados/
- Apple file protection: https://developer.apple.com/documentation/foundation/fileprotectiontype
- Apple developer account and signing context: https://developer.apple.com/help/account/basics/about-your-developer-account
- whisper.cpp: https://github.com/ggml-org/whisper.cpp
- LiteRT-LM: https://developers.google.com/edge/litert-lm/overview
- Gemma 4 mobile integration: https://developers.google.com/edge/litert-lm/models/gemma-4
- llama.cpp: https://github.com/ggml-org/llama.cpp
- sherpa-onnx diarization: https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html
- sherpa-onnx Kotlin example: https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/kotlin.html
- sherpa-onnx Swift example: https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/swift.html
- libsodium sealed boxes, including sender-authentication limitation: https://libsodium.gitbook.io/doc/public-key_cryptography/sealed_boxes
- libsodium secretstream: https://libsodium.gitbook.io/doc/secret-key_cryptography/secretstream
- DENSO WAVE QR capacity/standards: https://www.qrcode.com/en/about/standards.html

