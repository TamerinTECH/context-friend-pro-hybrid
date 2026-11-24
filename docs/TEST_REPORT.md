# Prototype test report — 27 October 2026

> Sections dated 27 Oct – 6 Nov 2026 are the record of the pre-existing prototype
> (docs/PRE_EXISTING.md). The AWS runbook and raw AWS evidence they link to were not
> imported into this public repository.

**Android builds and eight tests passed on an emulator and a physical Pixel 8 Pro.
Apple simulator/device builds and six tests on both a simulator and physical iPhone X also passed.
Personal Apple installation and broader device qualification remain pending.
This is not a production-ready or fully tested release.**

## Delivered

- Native Kotlin/Compose app and Swift/SwiftUI iPhone/iPad source project.
- Native encrypted recording, checkpoint recovery, local model import/download,
  real Whisper, diarization/embedding and Gemma runtime integration, review/correction,
  version approval, retention, opt-in reusable voice profiles and explicit profile copy.
- Recipient-encrypted common envelope; QR payload frames, native BLE GATT and
  temporary LAN transport implementations; model-free receiving and signed receipts.
- Pinned model manifest and locally downloaded/verified model pack; no HF token needed
  for these public artifacts. No credentials copied to either app.
- EN/DE/HE core strings, shared schema/vector, automated tests, notices, threat model,
  offline provisioning, Mac build instructions and device acceptance runbook.

## Actual verification

| Check | Result | Evidence / limit |
|---|---|---|
| Android ARM64 + x86_64 debug build | PASS | Gradle assembleDebug; real native runtimes linked |
| Android release-like build | PASS, unsigned | assembleRelease and release vital lint; not a signed distribution |
| Debug install/start | PASS | Android 15/API 35 x86_64 emulator, no startup AndroidRuntime exception |
| Instrumentation | **8/8 PASS** | `evidence/android-instrumentation.xml`, 86.482 seconds |
| AWS Android build | PASS | Final CodeBuild `1fc6d323-f218-40f4-8157-17c27a00f156`; debug, unsigned release and corrected test APKs; Gradle 8m06s |
| Physical Android instrumentation | **8/8 PASS** | Device Farm Pixel 8 Pro / Android 15, run `33fafbeb-d730-4ad8-a0e0-4fca4b0da0f9`; 10/10 AWS checks include setup and teardown |
| Radio-off test run | PASS for those tests | Emulator Wi-Fi/cellular disabled; not an app-attributed packet audit |
| Native recording controls | PASS on emulator and physical Pixel | Start, encrypted chunks, pause/resume/stop; short capture only, no lock/room-quality claim |
| Real ASR | PASS smoke | Whisper Tiny recognized expected words from public JFK natural speech; digital zero produces no text |
| Real speaker runtime | PASS smoke | Actual segmentation turns and a finite, nonzero 192-value TitaNet embedding |
| Structured summary bridge | PASS on Windows CPU | Same C++ bridge, real Gemma weights; `evidence/host-summary.json` |
| Cross-language protocol vector | Python + Android + Apple PASS | Independent libsodium envelope, Hebrew UTF-8 and exact receipt |
| Apple simulator + device builds | PASS | Xcode 26.1.1 on AWS M2 Large; iOS/iPadOS 16.0 deployment target; unsigned device IPA |
| Apple XCTest | **6/6 PASS on simulator** | iPhone 17 Pro / iOS 26.1 simulator; real Whisper/speaker bridge, protocol, vault, approval and QR tests |
| Physical Apple XCTest | **6/6 PASS** | Device Farm iPhone X / iOS 16.7.11, run `41a8211b-074c-4b8d-ac46-757ac23240c3`; AWS setup and teardown also passed |
| Apple source parser | PASS, 12 Swift files | Syntax only; does not validate Swift API types, linking, runtime or signing |
| Portable speaker C API | PASS Apple link, simulator and physical iPhone smoke | Actual segmentation and 192-value speaker vectors, plus earlier host compile |
| APK signature / packaging | PASS debug | apksigner verify, zipalign 16 KiB; all 16 native ELF files have ≥16 KiB LOAD alignment |
| Models | PASS integrity | All five artifacts match committed size/SHA256; see manifest |

Full passing command from `android/`:

```text
gradlew.bat :app:connectedDebugAndroidTest :app:assembleRelease --console=plain
BUILD SUCCESSFUL in 2m 3s
```

Final packaging then ran `:app:assembleDebug :app:assembleRelease` successfully.
The final APK was reinstalled and FlowTest ran **three further times**, all passing
(13.106 s, 12.715 s, 12.659 s), to exercise the corrected stop-recording race.
Host commands: `python tools/check_sources.py`, `python tools/protocol_vectors.py`,
`python tools/provision_models.py`, MSVC `cf_probe` and `cf_speaker_api_check` targets.
Native runtime headers/API were inspected against pinned source, not guessed SDK stubs.
Resolved Android dependencies are recorded in `evidence/android-dependencies.txt`.

The Windows summary probe took **58.329 seconds**, including model load, for a tiny
invented two-segment source while other builds ran. This is not a phone benchmark.
Manual inspection found no invented named owner or date in that fixture; the agreement
was retained in paragraphs/actions but the decisions list was empty. No held-out quality
claim follows from this test. Actual phone WER/CER/DER, RTF, battery, peak memory,
thermal behavior, 30/60-minute tests and transport throughput are **NOT RUN** in
`BENCHMARK_RESULTS.json`.

## Defects found and corrected during testing

- Physical-device Compose synchronization waited indefinitely for the continuously
  updating microphone meter. FlowTest now advances Compose frames explicitly during
  real capture and cleans up its own recording even on failure. Duration/chunk and
  recorder-state assertions remain. The physical rerun passed; no recorder stub was used.
- The first real Xcode build found an audio-reader initializer capturing `self`
  before its chunk index was initialized. The closure now uses the local opened key.
  The Xcode project also now restricts simulator targets to arm64, matching the
  supplied native simulator framework. Apple Intel simulator support remains absent.
- Running simulator tests with signing disabled made both Keychain-using tests fail.
  Normal ad-hoc simulator signing supplies an application identity and both tests now
  pass. Device artifacts remain unsigned for Device Farm re-signing. Storage errors
  now carry operation/OS codes for diagnosis, without logging keys or user content.
- Cloud source ZIP timestamps from Windows could appear in the future on a UTC
  builder and make Ninja regenerate indefinitely. Packaging now uses fixed historical
  timestamps. A later artifact step wrongly assumed JVM test output existed; it now
  reports NO-SOURCE explicitly and leaves actual instrumentation to Device Farm.
- Final audio flush could race the writer's shutdown; writer now waits for producer
  completion and drains its bounded queue.
- The initial microphone route notification caused a spurious pause; only an actual
  established-route change now interrupts capture.
- A normal AudioRecord stop could return an empty read and mark a session interrupted;
  error handling now distinguishes explicit shutdown from an active capture failure.
- An interrupted import could look complete; it now stages and commits last, with
  recovery cleanup and idempotent receipt retry.
- A receipt signature with extra decoded bytes could pass a native verification call;
  exact signature length is now required before verification.
- Generic model chat formatting did not cover Gemma 4's canonical format; the pinned
  single-user/no-thinking format is implemented, with special-token text escaped.
- Unconstrained model output could omit empty sections; generation now uses a schema
  grammar and then verifies all source IDs.
- Hierarchical summary reduction now preserves the full union of original source IDs.

## Remaining limits and blockers

1. **Personal Apple signing is not configured.** Simulator and device builds now pass,
   but the unsigned IPA cannot be installed directly on Ron's iPhone/iPad. Device Farm
   re-signing only enables its own test devices. No TestFlight or store release exists.
   Apple Silicon simulators are supported; Intel Mac simulator support is absent.
2. **Physical Pixel 8 Pro and iPhone X passed smoke tests through AWS Device Farm.**
   Ron's own devices have not been tested; Pixel 11 Pro and iPad Air 2026 remain
   untested hardware models. iPhone X has a
   large-summary-model memory guard. Full-AI end-to-end behavior, including Gemma
   generation on a phone, remains unverified.
3. BLE/QR/LAN hardware interoperability in both directions has not run. The BLE 2 MiB
   and QR 38,400-byte ceilings are provisional engineering limits, not measured radio/
   camera limits. Native peripheral adapters exist; role reversal for hardware that
   cannot advertise is not implemented. Such devices must explicitly choose QR/LAN.
4. Transfers currently keep a bounded envelope in memory. Optional audio sharing is
   limited to recordings of at most 15 minutes; 30–60-minute recordings can share
   notes/transcripts but not their entire audio. Partial large-audio selection/streaming
   is not implemented.
5. The natural EN/DE/HE multi-speaker held-out corpus and quality scoring are pending.
   Speaker thresholds and identity suggestions are experimental; summary source IDs
   and JSON syntax do not prove factual correctness.
6. Full recording under lock, real interruptions/reboot/storage pressure, physical
   backup/key properties, app-attributed traffic inspection and accessibility/RTL
   visual QA remain pending. The committed screenshot is a real emulator test flow.
7. Summary generation is foreground work with bounded windows, not a guaranteed
   long-running OS background job. Current source recovery supports reopening and
   explicit retry; interruption/low-memory behavior needs device stress testing.
8. Voice-profile deletion removes the enrolled profile and match suggestions. Original
   anonymous session-derived vectors remain until their meeting is deleted. Accepted
   copies on another device remain independent.

Only the separately authorized AWS build/test resources were created. No production
ContextFriend service, VM or sibling repository was modified. No artifact was
published to a store or remote repository.

## AWS physical Android evidence

The first physical run (`7958d175-961b-4eb0-9fb1-0441a5f91659`) passed all seven core
tests and failed FlowTest with `ComposeNotIdleException` at its Pause interaction.
The corrected run (`33fafbeb-d730-4ad8-a0e0-4fca4b0da0f9`) passed all eight app tests.
Wi-Fi, Bluetooth, NFC and GPS were requested off; this is not an app-attributed
network audit or proof that all device/platform traffic was absent.

That passing run used the cloud-built app from build `9d865979-aa89-4abb-bd77-c19abba794cb`
(its Gradle build passed; the enclosing job failed in the report-copy step), plus
the locally rebuilt test-only APK. App SHA256:
`2f02d9fc55699dd1453ea7137bd5b71960d53de34072ce29e1366ac7f0b6c2cd`;
test APK SHA256: `63a34ed7d52ab324babf9a454c048313101e006ac5ba9a71ad099cd7de39dbe8`.
The later fully successful cloud build has separate hashes; the exact tested
binaries are not interchangeable evidence. See [AWS runbook](AWS_BUILDS.md) and
[AWS resource/build/test evidence](evidence/aws-2026-10-27.json),
[all eight instrumentation transcripts](evidence/android-devicefarm-2026-10-27.txt),
and [final cloud artifact hashes](evidence/aws-artifacts-2026-10-27.json).

The final cloud build includes the corrected FlowTest and passes source checks,
independent protocol vectors, APK signature verification and 16 KiB ZIP/ELF
alignment checks. Eight isolated infrastructure regression tests pass, covering
account, packaging, duplicate-Mac, cleanup-target and signed-URL redaction guards. The JVM test task
reports NO-SOURCE; it is not counted as an executed unit-test suite.

Real Whisper Tiny recognized expected words from the public JFK recording; digital
zero produced no transcript. Real segmentation returned turns, and TitaNet returned
192 finite, nonzero embedding values. Encrypted recovery, approval invalidation,
recipient-bound transfer, profile consent/model compatibility, independent crypto
vectors and hostile QR/BLE frame rejection also passed. These are smoke checks,
not ASR/diarization accuracy benchmarks or physical radio-transfer tests.

## Handoff

Install `output/ContextFriendPro-aws-tested-debug.apk` on the Pixel for a personal
prototype trial; this is the uploaded app from the passing physical AWS run.
`output/ContextFriendPro-debug.apk` retains the earlier emulator-tested local build.
`output/ContextFriendPro-release-unsigned.apk` is for inspection/signing later.
Exact sizes and hashes: `evidence/artifacts.json`. Import the verified files from
`models/` through Models before local inference; no internet is required afterward.
For Apple, follow BUILD.md on the Mac, then TEST_PLAN.md on the actual devices.

The successful Apple cloud build is `9d149e32-6955-4622-bfb3-d9d59a0c476c`, source
SHA256 `c8f5b20808acad928f56f974d27ea71da2b35f19f9aa0624b162ffef48c652be`.
Artifacts and raw XCTest results are in `output/aws/9d149e32-6955-4622-bfb3-d9d59a0c476c/`.
The six simulator tests took 0.840 seconds according to XCTest; this is a smoke-test
duration on a Mac, not an iPhone inference benchmark.

## AWS physical Apple evidence

The physical iPhone X / iOS 16.7.11 job in run
`41a8211b-074c-4b8d-ac46-757ac23240c3` passed all six CoreTests, plus AWS setup and
teardown. It used the successful Apple build above: unsigned app SHA256
`dbd4637af8ffe28e8a28747b7e2ad0c3894eb6f66e7fc1c8e8eae0c3127d4179`,
XCTest ZIP SHA256 `b7e2476fbf4534337362008fea39c9d3cf8bac9568b0387b7778cd91e4466350`.
AWS re-signed these packages for its device. This does not provide personal-device signing.
See [all six XCTest transcripts](evidence/apple-devicefarm-2026-10-27.txt),
[simulator results](evidence/apple-simulator-2026-10-27.txt) and the
[resource/test snapshot](evidence/aws-2026-10-27.json). Raw logs and videos are in
`output/aws/devicefarm/41a8211b-074c-4b8d-ac46-757ac23240c3/`.

The real native tests recognized expected words from the public JFK fixture,
rejected digital silence, returned speaker turns and finite nonzero 192-value
embeddings. Vault recovery, the independent encrypted transfer vector, approval
invalidation and QR frame handling also passed. This suite does not exercise live
iPhone microphone capture, camera/BLE/LAN transfers, a large summary model,
30/60-minute recordings or accuracy/battery/thermal qualification.
The device log warns that CFBundleDevelopmentRegion is not a string; bundle
localization metadata and iPad orientation declarations should be reviewed during
the personal-signing/visual-QA pass. Neither warning failed the core test suite.

## Google Play preparation, 3 November 2026

Android only. Everything below ran on an x86_64 emulator (AVD `ContextFriendPro`, 8 GB RAM) or
on the Windows bridge, never on a physical phone.

| Check | Result | Evidence / limit |
|---|---|---|
| `EndToEndTest#meetingLongerThanOneBatchIsSummarized` | **PASS, 14,686 s (4 h 5 min) on the debug build; see "Final bundle" for the real speed** | 90 invented segments, 5,272 characters: two batches and a merge, `state=needs_review`, sources valid. Read from logcat (`TestRunner` finished with no failure), because a low-memory guard stopped the host's `am instrument` during the run. Ran before the callback fix, which is outside this test's path (ASR and speakers are preset). |
| `EndToEndTest#recordedSpeechBecomesTranscriptAndSummary` | **CRASH before the callback fix; PASS after it (below)** | Reached the speaker step, then the process aborted about 72 s into the test. |
| `EndToEndTest#everyRequiredModelCanBeDownloadedInTheApp` | **PASS, 2 s** | Downloaded `pyannote-segmentation3` from the pinned Hugging Face file. On-device SHA-256 equals the manifest's `d582f4b4…27b5d`. |
| `CoreInstrumentationTest#realSpeakerModelsReturnTurnsAndEmbedding` with callback | **PASS, 3 s** | New regression check. `processWithCallback` reached the lambda and returned as many turns as `process`. |
| Long-meeting first batch on the Windows bridge | Valid JSON, 186 s | `cf_probe` with the test's exact 68 segments, the emulator running beside it |
| `:app:assembleDebug :app:assembleDebugAndroidTest :app:bundleRelease` | PASS | Signed AAB, `jarsigner -verify` passes |

`am instrument -e notClass …EndToEndTest` on the fixed build: **8/8 PASS, 86 s** (seven
`CoreInstrumentationTest` tests, including the callback check, and `FlowTest`).

Defects found and corrected:

- **Models could not be downloaded in the app.** The segmentation model came from an archive
  the downloader refused. It now comes from a pinned single file with the same hash.
- **Summaries failed with `input_too_long` from roughly 3 minutes of speech.** On the Windows
  bridge a batch failed at 3,000 and 4,000 characters and passed at 2,000, because UUID source
  ids cost more tokens than the words. Rows now use short ids, and a batch that runs out of
  context is split in two. The 4,000-character batch then passed in 118 s.
- **Any recording crashed the app at the speaker step.** sherpa-onnx finds the diarization
  callback through JNI as `invoke(IIJ)Ljava/lang/Integer;`. Kotlin 2 compiles a plain lambda to
  invokedynamic, which has no such method: `JNI DETECTED ERROR … NoSuchMethodError`, signal 6.
  The lambda now carries `@JvmSerializableLambda`. Earlier tests called `process()` directly, so
  8/8 passing never covered this path. The abort was seen on a debug build (CheckJNI). A release
  build was not run with the old code.

Remaining limits:

- Times on this emulator do not predict phone speed. The Android llama.cpp build sets
  `GGML_NATIVE OFF`, so it probably runs without AVX2 here and without dotprod/i8mm on ARM.
  Neither has been measured.
- The fixtures prove the pipeline runs, not that summaries are good. On unconnected invented
  sentences the summary mostly copied them back, with repeats and decisions filed as plain text.
- Not covered: German or Hebrew, a real meeting, multiple real speakers, or 30–60 minutes.

### Final bundle, 3–4 November 2026

Bundle `output/play/ContextFriendPro-0.1.0-1.aab`, versionCode 1, signed with the upload key, not
uploaded. SHA-256 `422ac29e08bd4a66bc0d165cbd08a3a5044191523f0b1d67b172499fe405dafe`.

How it ran: `bundletool build-apks --connected-device`, then `install-apks`, so the emulator got the
splits Play would deliver (base, `config.en`, `config.x86_64`, `config.xxhdpi`) with debuggable off.
bundletool re-signs them with the debug key, which is also the test APK's key, so `am instrument` can
run against the release code. The dex and native libraries are the bundle's; the signature is not,
and Play re-signs for delivery anyway. Still the x86_64 emulator (8 GB, 6 cores), never a phone.

| Test | Debug build | Final bundle | Conditions |
|---|---|---|---|
| `recordedSpeechBecomesTranscriptAndSummary` (11 s public speech) | PASS, 573 s | **PASS, 35 s** | Airplane mode |
| `meetingLongerThanOneBatchIsSummarized` (6 min invented speech) | PASS, 14,686 s | **PASS, 875 s** | Airplane mode |
| `quietRecordingIsNotPresentedAsASummary` (new) | not run | **PASS, 7 s**; failed first on the code without the fix | Airplane mode |
| `CoreInstrumentationTest` and `FlowTest` | 8/8, 86 s | **10/10, 18 s**, with two new tests; the UI one failed first without its fix | Airplane mode |
| `everyRequiredModelCanBeDownloadedInTheApp` | PASS, 2 s | **PASS, under 1 s** | Online |
| `bundletool validate`, `jarsigner -verify`, upload certificate `CN=TamerinTECH` | | PASS | Host |
| 16 of 16 native libraries with 16 KB load alignment | | PASS | Host |
| `invoke(IIJ)Ljava/lang/Integer;` present in the bundle's dex | | PASS | `dexdump` |
| `tools/check_sources.py`, `tools/protocol_vectors.py` | | PASS | Host, on the final source |

Driving the real UI of the release build with uiautomator:

- All four required models came down through their Download buttons and reported "Installed and
  hash verified", Gemma included. This ran on the first release build (`c7f63c98…`); the two later
  builds updated in place, kept the models and did not change the download code.
- Recording in airplane mode: permission prompts, consent, a foreground service with type
  microphone, Stop, then Process locally. No crash. Repeated on `319819c1…`, retried on the final bundle.
- All tabs, German, and Hebrew with a right-to-left layout: no crash. Ran on `319819c1…`.

Defects found by these runs and corrected:

- **Quiet audio became a transcript and a summary.** A real microphone never delivers pure zeros, so
  `engine.cpp:44` does not catch it and Whisper labels it `[BLANK_AUDIO]`. The app stored that as
  speech, and Gemma "summarized" it to "Ready for review". Whisper's labels are now dropped
  (`notSpeech`). A quiet recording ends as failed. The earlier silence check used pure zeros, so it
  never reached Whisper. The same defect probably exists on Apple (`Processing.swift:53`); not fixed.
- **A false "The operation could not finish" dialog.** Two quick state changes cancel the list still
  loading, and `runCatching` reported that cancellation as a failure (`MainActivity.kt`). No data was
  affected. Processing changes state twice in a row, so this could appear during any processing.

Correction to the explanation above and in earlier notes: 4 h 5 min was mainly a debug-build effect,
not missing AVX2. AGP builds the debug native libraries with `CMAKE_BUILD_TYPE=Debug` and no
optimization flags; release uses `-O2`. Same tests, 15–17 times faster. **Debug-build timings of
Whisper and Gemma mean nothing: measure on release code** (BUILD.md). AVX2 and ARM instruction
flags remain unmeasured.

Limits that remain:

- No physical phone has run Gemma, this bundle, or the ARM64 libraries with Gemma. Emulator times
  do not predict phone speed, heat or battery.
- On this emulator the summary stage alone took 2.4 to 2.8 times the 6 minutes of speech (875 s
  here, 1,002 s on the build before). The brief's target is a result within ten minutes for a clean
  ten-minute meeting, so this speed would miss it.
- The emulator's microphone is silent: real room capture did not run through this bundle. The speech
  results fed a public recording in at the vault, not through the microphone.
- Summary quality is unchanged and unproven. The 6-minute summary is still mostly the invented
  sentences copied back.
- Not tested: German or Hebrew speech, real meetings, several real speakers, 30–60 minutes, or
  leaving the app or locking the screen during processing (no foreground service there yet).
- A failed quiet recording shows only "Processing failed — retry available", without the reason.

Evidence on this PC, outside Git: `F:\context-friend-pro\logs\` (`final-bundle-*`, `release-*`,
`flow-rapid-before-fix.log`).

## #BuildHybridAI edition — 22 November 2026

Hackathon work (docs/PRE_EXISTING.md). Host: Windows 11, JDK 21 (Android Studio JBR), Gradle 8.13,
Kotlin 2.4.0, Firebase BoM 35.2.0, firebase-ai 18.2.0, firebase-ai-ondevice 16.0.0-beta08.

| Check | Result | Where |
|---|---|---|
| JVM unit tests (`:app:testDebugUnitTest`) | **75/75 PASS** (Redaction 34, Policy 21, Notes 9, CloudDraft 7, ShareLink 4) | Host and GitHub Actions |
| Debug APK, release Kotlin compile | PASS | Host |
| UI smoke test, no Firebase options in the build | PASS: AI & privacy screen shows "Firebase is not configured" for Gemini Nano and the cloud, the ContextFriend engine as installed; meeting types shown; cloud button hidden | Emulator `ContextFriendPro`, Android 15, x86_64 |
| `EndToEndTest#recordedSpeechBecomesTranscriptAndSummary` | **PASS**, 612 s on a debug (unoptimised native) build: Whisper transcript, speaker stage, notes through the new policy-routed pipeline; policy chose `gemma-local` (`auto_nano_not_ready`); provenance and audit entry recorded; audit log contains no transcript words | Same emulator |

Not yet run: anything with Gemini Nano (needs a Gemini Nano 4 phone and the AICore Developer
Preview), the cloud path and the encrypted links (need the Firebase project), offline behaviour of
Firebase AI Logic's on-device path. These are tracked in docs/SUBMISSION_CHECKLIST.md.

Observation: with the generic template, Gemma used the template line as the title. The prompt now
asks for a title that names the topic.

## Firebase project connected — 24 November 2026

Firebase project `contextfriend-pro-hybrid` (Spark plan until the Blaze upgrade), Android app
`com.tamerintech.contextfriendpro`, set up by command line: Firestore `(default)` in **eur3**
(read back: `locationId: eur3`), rules released, anonymous sign-in enabled, Remote Config template
version 1 deployed. Debug build with the project's options from `local.properties`, same emulator.

| Check | Result | Where |
|---|---|---|
| Firebase start from build options (no google-services.json) | PASS: Firebase, App Check debug provider, Remote Config and Auth start; `Default FirebaseApp failed to initialize` before that is expected, because the app initialises Firebase itself | Emulator, logcat |
| Encrypted cloud link, create | **PASS**: anonymous sign-in, Firestore write accepted by the rules, which only allow exactly `v, ct, nonce, owner, createdAt, expiresAt`; QR and `CFS1:` text shown | Emulator |
| Encrypted cloud link, open | **PASS**: pasted `CFS1:` link fetched, decrypted on the device and stored as a received, read-only meeting | Same emulator (sender and receiver on one device; a second device is still to do) |
| Remote Config kill switch | **PASS**: `cloud_enabled=false` deployed → *Refresh* → "Switched off: local-only mode"; a consented Send was stopped by the policy gate ("The cloud is switched off. Notes stay on this phone."); `cloud_enabled=true` restored and read back | Emulator + live Remote Config |
| Privacy log | PASS: `cloud_blocked · cloud_disabled`, `cloud_share_opened`, `cloud_share` (166 characters, `end_to_end_encrypted,ttl_7d`), metadata only | Emulator |
| JVM unit tests | 75/75 PASS | Host |

Defects found and fixed in this run:

- Remote Config was read before its defaults were applied, so a first start without network
  would have shown the cloud as off and used 0 as the Nano chunk size (clamped to 1,000). The first
  read now waits for the defaults, and until then the kill switch stays closed.
- A received meeting said "Audio was deleted" and offered *Approve*, *Send* and *Encrypted cloud
  link*. It now says where the notes came from and offers only *Edit* and *Export*.
- Tapping a switch's label did nothing; only the switch itself reacted. The whole row is now
  the switch (also one element for screen readers).
- With the kill switch on, the privacy panel still asked for consent before refusing. It now says
  straight away that the cloud is switched off.

Open: link creation took about 10 s because the unregistered App Check debug token was rejected
first (App Check is not enforced yet). The Firestore TTL policy for `shares.expiresAt` needs the
Blaze plan; it is defined in `firebase/firestore.indexes.json` and deploys after the upgrade.

### Blaze and AI Logic — 24 November 2026, evening

Read back with gcloud: billing enabled; `aiplatform.googleapis.com` and
`firebasevertexai.googleapis.com` enabled; App Check enforcement **ENFORCED** for
`firebaseml.googleapis.com` (Firebase AI Logic), not enforced for Firestore.

| Check | Result | Where |
|---|---|---|
| Firestore TTL for `shares.expiresAt` | Deployed with `deploy --only firestore:indexes`; state `CREATING` right after (Firestore can take up to a day to activate a TTL policy) | Live project |
| Cloud notes, consented Send of a test meeting (blank audio, no names) | **Refused by App Check**: `401 UNAUTHENTICATED Firebase App Check token is invalid`; notes unchanged; privacy log `cloud_failed · gemini-4-hydrogen · eu · Firebase App Check token is invalid` | Emulator, unregistered debug token |
| Location `eu` on the AI Logic endpoint | Routed to the service (the 401 comes from App Check), while a made-up location returns 404 at the front end. The model name can only be confirmed once App Check passes | Same request, plus a direct REST call that copies the SDK's |
| JVM unit tests | 75/75 PASS | Host |

Fixed in this run: a cloud request refused by App Check showed the generic "The cloud request
failed". It now says that this copy of the app could not be verified (Firebase App Check), in
English, German and Hebrew. Judges on a phone that fails Play Integrity would otherwise get no
clue.

Next: register the emulator's debug token, then repeat the cloud notes run (placeholders out,
names back, badge `IN_CLOUD`).
