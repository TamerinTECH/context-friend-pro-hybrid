# Changelog

Hackathon work for #BuildHybridAI 2026. The first commit is disclosed pre-existing code
(docs/PRE_EXISTING.md); everything listed here was built during the submission period.

## 2026-11-25

- QR scanner no longer crashes the app when it closes: the camera frame copy checks its bounds and
  skips bad frames, and the frame reader closes on the camera thread (3 new unit tests, 78 in total).

## 2026-11-24

- Firebase project `contextfriend-pro-hybrid` connected: Firestore pinned to eur3 in
  `firebase.json`, anonymous sign-in declared there too, TTL for expired links defined in
  `firestore.indexes.json` (deploys after the Blaze upgrade).
- Verified on the emulator against the live project: encrypted cloud link created and opened,
  Remote Config kill switch, privacy log (docs/TEST_REPORT.md).
- Fixes from that run: Remote Config is read only after its defaults are applied, and the kill
  switch stays closed until then; received meetings say where they came from and cannot be
  approved or re-shared; tapping a switch's label toggles it; the privacy panel says at once when
  the cloud is switched off.
- Blaze and AI Logic on; Firestore TTL for expired links deployed. AI Logic enforces App Check, and
  a cloud request refused by App Check now says that this copy of the app could not be verified
  instead of a generic failure (English, German, Hebrew).

## 2026-11-22

- Public repository, Apache-2.0 licence, disclosure of pre-existing code, hackathon
  decisions and agent rules.
- Firebase BoM 35.2.0, Firebase AI Logic 18.2.0 and the on-device SDK 16.0.0-beta08 added;
  Kotlin 2.1 to 2.4 (Firebase 35 ships Kotlin 2.4 metadata). Firebase options come from
  untracked `local.properties`, so no key is in the repository.
- Privacy core: deterministic local pseudonymiser (`Redaction.kt`), routing policy
  (`Policy.kt`), meeting templates and prompts (`Prompts.kt`). 55 unit tests with
  synthetic data, including a check that a cloud prompt contains none of the fixture's
  names or identifiers.
- Hybrid notes engine: Gemini Nano through Firebase AI Logic with `ONLY_ON_DEVICE`
  (Gemini Nano 4 preview option, JSON output, BUSY back-off, inference source checked on
  every answer), the prototype's Gemma 4 engine as the local alternative, chosen by policy.
  Map-reduce notes moved into a model-independent `Notes.kt`.
- Opt-in cloud notes: `CloudDraft` prepares the exact outgoing prompt on the device, the
  policy gate runs again right before sending, the answer is re-identified locally. Cloud
  backend: Vertex AI (Agent Platform) in an EU region, `ONLY_IN_CLOUD`.
- Firebase services: App Check (debug provider in debug builds, Play Integrity in release),
  anonymous Authentication, Remote Config (kill switch, cloud model, Nano option, chunk
  size, extra protected terms).
- Local privacy audit log (metadata only, encrypted in the vault, exportable as JSON).
- UI: AI & privacy screen (Gemini Nano status and download, engines, names to protect,
  privacy log), "where it ran" badge, meeting templates (parent–teacher, support plan,
  counselling, staff, medical, legal), privacy panel with the exact outgoing text, tap-to-
  redact name hints and consent. English, German and Hebrew strings.
- 71 unit tests, including the full notes pipeline with a fake model and proofs that the
  cloud model is never called without consent or with the kill switch on.
- CI on GitHub Actions (JVM unit tests). Opt-in "allow screen capture" setting (FLAG_SECURE
  stays the default). Default cloud location `eu`, the Vertex AI multi-region with EU data
  residency and in-EU ML processing.
- Encrypted cloud links ("hybrid if desired"): notes text only, XChaCha20-Poly1305 on the
  phone, Firestore stores ciphertext for at most 7 days; link or QR `CFS1:<id>:<key>`; the
  recipient decrypts locally. Firestore rules allow only that document shape and no listing.
  Remote Config template and `firebase.json` for deployment. 75 unit tests.
