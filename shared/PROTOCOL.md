# ContextFriend portable protocol v1

All integers are unsigned big-endian. UUIDs are 16-byte RFC 4122 byte order. Text is
UTF-8. Base64url uses no padding. Public vectors: `protocol-vectors.json`.
No compression, filenames, archive entries, URLs-to-fetch or executable fields.

## Invitation

`CFI1:` followed by base64url JSON: `version=1`, UUID `id`, X25519 `boxKey` (32 bytes),
Ed25519 `signKey` (32 bytes), `ttl` (1..600 seconds), optional private IPv4 `host` and
`port` (1024..65535). Invitation text is limited to 2048 characters. The receiver's
encrypted pending record holds boot identity, monotonic deadline, bound bundle hash,
completion and signed receipt. Never trust a sender-supplied wall-clock expiry.

## Encrypted bundle

| Offset | Length | Field |
|---:|---:|---|
| 0 | 4 | ASCII CFB1 |
| 4 | 1 | purpose: 1 meeting, 2 profiles |
| 5 | 16 | invitation UUID |
| 21 | 16 | bundle UUID |
| 37 | 32 | recipient box public key |
| 69 | 32 | recipient signing public key |
| 101 | 32 | sender signing public key |
| 133 | 24 | fresh XChaCha nonce |
| 157 | 80 | libsodium sealed box containing fresh 32-byte content key |
| 237 | 4 | ciphertext byte count (includes 16-byte AEAD tag) |
| 241 | variable | XChaCha20-Poly1305 ciphertext; AAD = complete 241-byte header |
| end−64 | 64 | Ed25519 signature over ASCII `CFB1-SIGN:` + header + ciphertext |

Maximum envelope is 64 MiB; minimum 321 bytes. No allocation follows an unchecked
length. Verify recipient, pending invitation, signature and AEAD before parsing.
JSON nesting max 16. Payload `version`, `bundleId` and `purpose` must match header.

`meeting` contains sessionId, revision, createdAt, language, title, approved, summary,
optional transcript/speaker-label map/audio, structuredSummary and model-stage provenance. Optional
fields are omitted or null. It never contains history, profile suggestions or vectors.
Transcript max 20,000 segments; title 500 chars; summary 300,000; segment text 20,000.
Structured summary/source references are included only with the transcript and an
unedited generated summary. Its IDs must resolve to that included transcript. Manual
summary edits do not inherit an implied generated-source mapping. Segment IDs and
chunk indices must be unique; timeline bounds are 0..24 hours.
Audio is base64 little-endian PCM16 mono 16 kHz with index, startMs and samples;
individual chunks max 80,000 samples. No automatic audio attachment.

`profiles` is mutually exclusive with `meeting`. Max 100 profiles. Each carries a
UUID, consentVersion=1, name, exactly 192 finite embedding values (not all zero), modelHash,
preprocessing=`mono16k-f32-sherpa-v1`, createdAt, imported and enabled. Receiving
overrides imported=true/enabled=false. Compatibility uses the local trusted manifest.

## Receipt

`CFR1:<invitation UUID>:<bundle UUID>:<envelope SHA256 hex>:imported:<signature>`.
The last field is exactly 64 signature bytes in base64url, signed by the recipient
over the preceding five colon-separated fields. Verify it against the invitation's
signing key and the exact sent envelope. QR receipts work with every radio disabled.

## QR payload frames

`CFQ1:<full envelope SHA256>:<zero-based index>:<count>:<total bytes>:<base64url chunk>`.
Chunks are 600 bytes except the final chunk. Max 64 frames, 38,400 envelope bytes.
Frames may arrive out of order and repeat identically. Conflicting repeats, mixed
hashes/counts, invalid sizes and partial sets expire/reject. Full SHA256 then envelope
authentication are required. This is actual encrypted content, not a locator.
UI currently advances frames manually for camera reliability. Cap is provisional.

## BLE GATT

Service `cf010001-e21b-48ac-b9dc-1920e92fbc01`; control `cf010002-...` (read/write),
data `cf010003-...` (write with response), receipt `cf010004-...` (read).
Advertisement contains the generic service only. Recipient is peripheral; sender
is central. Both platforms contain native implementations of both application roles.

Write control: CFB1 + 32-byte binary envelope SHA256 + u32 envelope length. Read
control: u32 next verified in-memory offset. Data writes: u32 offset + bytes, bounded
by actual write-with-response capacity (Android MTU−7 for data). Control needs at
least 40 payload bytes; insufficient MTU fails visibly. Sequential acknowledgements
provide backpressure. Identical already accepted chunks are allowed. Offset/hash
binding permits reconnect/resume while the same foreground receive session lives.
After full envelope validation, recipient previews and accepts; sender polls receipt.

2 MiB ceiling, 10-minute receive session, 15-second sender operation timeouts.
This is an engineering ceiling, **not a measured radio throughput result**. Manual
retry is bounded by the invitation lifetime. App restart requires a new invitation.
No silent LAN fallback. Role-reversal protocol is not implemented; unsupported
peripheral hardware is explicitly limited to QR/LAN until device qualification.

## LAN

Explicit receiver binds a temporary TCP listener on a private IPv4 interface. Its
invitation carries host/ephemeral port. No DNS, internet endpoint, HTTP server,
multicast discovery or persistent daemon. Sender writes CFL1 + u32 byte count +
the same envelope. Receiver returns u32 receipt length (≤1024) + UTF-8 receipt after
acceptance. 10-minute session deadline; receiver limits accepted connection attempts.
Transport confidentiality/authentication comes from the common envelope, not LAN trust.
