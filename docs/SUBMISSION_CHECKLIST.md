# Submission checklist — #BuildHybridAI 2026

Deadline: **Mon 30 Nov 2026, 14:00 PT = 23:00 Berlin.** Internal target: submitted by 18:00 Berlin.
Judging 10–16 Dec; the app and the Firebase project stay free and available until 16 Dec.

## Ron (external actions; agents never do these)

- [ ] Register for the hackathon on Devpost (entry on behalf of TamerinTECH GmbH, Ron as Representative).
- [x] `npx firebase-tools login` with the TamerinTECH Google account (24 Nov).
- [ ] `gcloud auth login` (needed for the Vertex AI checks and budget commands).
- [ ] Firebase console: upgrade the project to Blaze, budget alert €10; AI Logic → Get started →
      Agent Platform (Vertex AI) Gemini API. See FIREBASE_SETUP.md.
- [ ] Pixel 11 Pro: AICore update + AICore Developer Preview; connect by USB with USB debugging.
- [ ] Register the App Check debug token from logcat (console → App Check → Manage debug tokens).
- [ ] Decide judge access: Play Integrity release APK (needs the Play Console ↔ Cloud project link)
      or another way; see "Judge access" below.
- [ ] Find two people to read the demo script; record the video (VIDEO_SCRIPT.md); upload to
      YouTube as public with English subtitles.
- [ ] Review DEVPOST.md, paste it into Devpost, add repo URL, video URL, APK link, platform Android.
- [ ] Submit and save a screenshot of the confirmation in `docs/`.

## Agent (in the repo)

- [x] Public repo, Apache-2.0 detected, disclosure of pre-existing code, CHANGELOG.
- [x] Firebase AI Logic imported and called at runtime (`Hybrid.kt`), per-task inference modes.
- [x] Privacy core, policy, notes pipeline, cloud draft, encrypted links: 75 unit tests, CI green.
- [x] Firebase project `contextfriend-pro-hybrid` (24 Nov): Android app, local.properties, Firestore
      in eur3 + rules, Remote Config template, anonymous Auth.
- [ ] After Blaze: Firestore TTL (`deploy --only firestore:indexes`), AI Logic, App Check enforcement.
- [ ] Phone checks on the Pixel (fill in docs/TEST_REPORT.md with real numbers):
  - [ ] Gemini Nano 4 status, download, notes from the demo meeting; badge shows ON_DEVICE.
  - [ ] Offline (airplane mode): does Firebase AI Logic on-device work without network and App Check?
  - [ ] AICore quota/BUSY behaviour on a 30-minute recording.
  - [ ] Cloud notes via `eu`: placeholders out, names back; badge IN_CLOUD; audit entry.
  - [x] Remote Config kill switch → local-only mode (emulator, 24 Nov).
  - [~] Encrypted link: created and opened on the emulator (24 Nov); phone ↔ emulator still to do.
- [ ] Release APK signed, published as a GitHub Release with checksums.
- [ ] README and TESTING.md: remove "(phone check pending)" markers or correct the claims.
- [ ] Final scan: `git log -p` for keys, tokens, real names; no personal data anywhere.

## Judge access (open decision)

Judges install an APK. App Check must accept it for the cloud features:

1. **Play Integrity, recommended:** link the Firebase/Cloud project to the existing Play Console app
   `com.tamerintech.contextfriendpro`, register the release signing certificate SHA-256 in App Check,
   and allow apps not installed from Play (device integrity only). On-device features never need it.
2. **Fallback:** if Play Integrity cannot be set up in time, the on-device features still work for
   judges; the video shows the cloud path. The rules accept debug tokens, but a token inside a public
   APK would let anyone call our AI Logic quota, so we don't ship one.
