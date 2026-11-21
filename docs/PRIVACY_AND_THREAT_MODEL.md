# Privacy and threat model

## Boundary

No server, account, telemetry, analytics SDK, remote crash reporting, cloud inference,
or cloud fallback is configured. The network entry points are explicit model downloads
and temporary direct transfers. INTERNET permission on Android also permits LAN
sockets; its presence alone does not mean recordings are uploaded. Source inspection
does not replace app-attributed traffic observation on physical devices.

Targets: lost locked device, ordinary backup extraction, accidental sharing, hostile
bundle/QR data, corrupted files and interrupted writes. A compromised/rooted/jailbroken
device, malware with unlocked process access, coercion, screen photography, OS defects,
or a recipient's independent copy are outside the protection guarantee.

## Vault

Every session has a random 256-bit key wrapped by a platform-protected master key.
All metadata, transcript revisions, pipeline checkpoints, profile embeddings,
preferences, transfer identity secrets and invitation state are encrypted. Android
uses AES-GCM with Android Keystore; Apple uses CryptoKit AES-GCM and a
WhenUnlockedThisDeviceOnly, non-synchronizing Keychain master. Hardware backing is
device-dependent and has not been attested in this run.

Each record authenticates its session/path/chunk identity as associated data. Atomic
temporary ciphertext writes are synced then renamed. The parent directory is synced.
No SQL database, plaintext journal or plaintext audio temporary file is used.
The active recorder retains the already unwrapped session key only in memory, allowing
encrypted writes under screen lock without unwrapping new keys. Apple ciphertext
uses completeUntilFirstUserAuthentication protection, while key unwrap requires unlock.

Capture uses short PCM chunks and bounded writer queues. A committed chunk is synced
before its duration checkpoint. Recovery scans committed chunks, retains them, and
labels the unknown uncommitted tail as an interruption. Backpressure stops capture
with a gap instead of silently dropping a continuing stream. Power-loss guarantees
still depend on the device filesystem; real lock/reboot/storage tests are pending.

Android backup and device-transfer rules exclude all data. Apple app files and models
are excluded from backup, and wrapping keys are ThisDeviceOnly. Exclusions are source
configuration until observed on physical devices. No absolute privacy promise is made.

Deletion removes the wrapped session key before session files. Audio-only deletion
retains text and its timestamps but cannot be reversed. Flash wear leveling means
physical overwrite is not guaranteed. Managed-language memory zeroization is best
effort. Uninstall/device loss can destroy the only useful copy.

## Sharing

User selection constructs a whitelist payload, freezes it for preview, then encrypts
it for a fresh recipient invitation. Approval applies to a specific revision; edits
invalidate approval and retain history. Draft sharing requires an explicit switch.
Meeting payloads omit voice vectors, biometric profiles, private keys and history.
Audio is a separate opt-in field, currently limited to recordings of at most 15
minutes by the portable bundle memory ceiling. Long meeting summaries/transcripts
are shareable; long audio transfer is a known limitation.

libsodium provides a fresh content key, sealed-box key wrapping, XChaCha20-Poly1305
authenticated encryption and Ed25519 signatures. The complete fixed header is
authenticated. A recipient QR authenticates the destination key only when checked
on the actual recipient's screen. A signature with a newly introduced sender key is
**unverified sender**, not proof of identity. Trust requires a separately checked
reciprocal invitation. The 96-bit displayed fingerprint is a human comparison aid.

The authenticated receipt means the recipient committed an accepted import. Sending
bytes, GATT write acknowledgement, or showing all QR frames does not mean acceptance.
Invitations expire on local monotonic time, are invalidated by reboot, and bind to
one envelope hash. Identical-envelope retries are idempotent; different replays fail.
Incomplete meeting imports use a staging state and are discarded on recovery.

Byte/count/depth/version/purpose limits precede import. No ZIP unpacking, remote
resource fetch, HTML renderer, URL navigation, executable content or deserialization
into arbitrary classes occurs. BLE and LAN framing are not trusted identities: a
nearby attacker can still consume bounded receive time or cause denial of service.
The payload and final receipt remain cryptographically checked. Radio metadata,
traffic sizes and timing are not hidden.

Plaintext summary export invokes the OS share sheet only after a warning. The next
app may store or upload it. We do not create persistent plaintext exports ourselves.

## Voice profiles and AI

Enrollment is explicit and requires real extracted speech embeddings. Profiles are
sensitive biometric data; consent text is not a substitute for context-specific legal
requirements. Cross-meeting matches are suggestions, never authentication. Current
cosine thresholds are provisional. Uncertain/overlapping speech remains unknown.

Profiles use a separate sharing purpose and confirmations on both sides. Imported
profiles require the same embedding model hash and preprocessing version, and begin
disabled. Deleting a profile removes that local profile and its suggestion references;
ordinary speaker labels remain. Session-specific anonymous diarization vectors remain
until the source session is deleted. An already copied profile on another device is
independent; no remote deletion or silent sync exists.

Local model output is untrusted. Summary syntax is grammar constrained and source
IDs are checked, but those checks do not prove factual support. Review owners, dates,
numbers, omissions and decisions. Transcript text cannot call tools or export data;
special-token delimiters are escaped before the Gemma prompt is assembled. Natural
German/Hebrew and multi-speaker quality remain unqualified pending held-out testing.
