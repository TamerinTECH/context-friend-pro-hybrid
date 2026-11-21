# Qualification runbook

Use consented or public test speech only. Start each destructive recovery/storage test
in an isolated test installation. Preserve original acceptance targets from the brief;
never replace difficult recordings or lower thresholds to manufacture a pass.

## Automated checks

See BUILD.md. Android instrumentation checks encrypted-at-rest markers, committed
audio recovery, incomplete-import cleanup, approval invalidation, purpose separation,
profile consent/model compatibility, recipient binding, tampered headers/ciphertext,
signature length, receipt identity, replay/idempotence, QR duplicate/out-of-order/expiry,
BLE framing/reconnect boundary, nesting limits, independent Python crypto vectors,
real Whisper speech and zero PCM, real speaker turns/embeddings, and native recording
controls. These are narrow tests, not coverage of every required adversarial case.

Apple XCTest includes approval/source rules, vault recovery, QR, the same independent
crypto vector, real Whisper speech/silence and speaker-model fixtures. All six passed
on an iOS 26.1 simulator and AWS's physical iPhone X / iOS 16.7.11 on 27 October 2026.
See TEST_REPORT.md for exact artifacts and evidence. These checks do not replace
the personal-device and sustained-meeting matrix below.

## Mac / device matrix

Record actual model, OS, source revision, model hashes, power/charging state, available
storage/RAM, thermal state and environment in BENCHMARK_RESULTS.json for every run.

1. Build and start Apple simulator; fix compiler/linker issues before physical testing.
2. Install Android on Pixel 11 Pro; Apple on iPhone X and iPad Air (2026).
3. Fresh offline launch with no models: receive/read/edit/share a meeting without AI.
4. Import all valid weights; reject corrupt, truncated and wrong-model files. Interrupt
   and resume each network download, then complete airplane-mode model import.
5. Short real recording → playback → ASR → speaker labels → summary → correction →
   approve → share → model-free recipient accept → signed receipt → delete.
6. Record 30 minutes, including ≥10 minutes locked. Record 60 minutes; force process
   death at several chunk boundaries, reopen, check committed data and explicit gaps.
7. Call/interruption, route change, denied/revoked permissions, silent mic, app lock,
   reboot, low storage, low memory and severe thermal state. No cloud fallback.
8. Cancel/restart each stage. No duplicate source segments; no complete-looking partial
   summary. Change an approved transcript/summary and confirm approval becomes draft.
9. EN/DE/HE UI with large text and Hebrew RTL, external keyboards, VoiceOver/TalkBack.
   Verify source timestamps and playback after edits, imports and audio-only deletion.
10. Enroll one explicitly consenting speaker; test new meetings, uncertain matches,
    enabled/disabled profiles, profile deletion, explicit cross-device profile copy,
    compatibility rejection and the recipient's default-disabled profile state.

## Transport pairs

Run Android→iPhone and iPhone→Android for every mode. Add Android→Android and
Apple→Apple when devices are available. iPad→Android also needs a real test.

- QR: disable every radio on both devices. Transfer a small complete envelope and a
  multipart Hebrew transcript. Scan reordered, repeated, conflicting and missing frames.
  Cancel, expire, restart and replay. The sender remains unconfirmed until the signed
  recipient receipt is scanned. No QR content may open a website.
- BLE: Wi-Fi/mobile data off, Bluetooth on. Verify native peripheral advertising on
  both devices, MTU negotiation, actual payload bytes, pause/disconnect/reconnect,
  consent rejection, cancellation and receipt. Measure throughput before declaring a
  supported size ceiling. If a target cannot advertise, implement and test role reversal;
  it is currently unsupported, with explicit QR/LAN alternatives.
- LAN: use a router/hotspot with no WAN and Bluetooth disabled. Confirm selected private
  interface/ephemeral port only, no lingering listener after leaving/backgrounding,
  bounded malformed lengths, interrupted receipt and explicit retry semantics.
- Every mode: wrong recipient, tampered header/signature/ciphertext, swapped bundle IDs,
  expired/reused invitation, unsupported version/purpose, oversized arrays/strings,
  malformed UTF-8/base64, integer extremes, deep nesting and plaintext marker inspection.

## Natural language / speaker corpus

Collect a licensed/consented held-out set in English, German and Hebrew with at least
two speakers per language, mixed languages, noise, overlap, names, numbers and dates.
Store consent/license and human reference transcript, speaker turns and expected facts
outside Git. Do not substitute TTS for acoustic qualification. JFK is only a smoke test.

Report WER and CER with a declared normalization (Unicode NFC, case folding, punctuation
policy; preserve names/numbers). Score diarization with a declared collar and overlap
policy; separately count false known-person matches. Human reviewers must check source
support, omitted decisions/actions and any invented owner/date/number. Include spoken
prompt-injection text. Source IDs and valid JSON are not proof of truth.

Brief targets remain WER ≤20% EN/DE, ≤25% HE; DER ≤20% under the declared policy;
no fabricated critical owner/date/decision/number in the held-out acceptance set.
Measure cold/warm stage times, peak memory, ASR real-time factor, battery, storage and
thermal state. A clean ten-minute meeting targets a result within ten further minutes.
Run twice for thermal degradation. No device is qualified until these tests execute.

## Privacy observation

Inspect app-owned files, temp paths and backup exports for distinctive test markers.
Observe app-attributed network attempts after provisioning during recording/inference;
repeat with each explicit transport and separately with model download. The emulator
radio-off run proves those tests did not need WAN, not that every OS/network path was
audited. Verify actual backup/device-transfer exclusions and hardware key properties
on the phones. Record visibility limits and failures, not just passing screenshots.
