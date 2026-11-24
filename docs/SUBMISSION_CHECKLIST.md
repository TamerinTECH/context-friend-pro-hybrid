# Submission checklist — #BuildHybridAI 2026

Deadline: **Mon 30 Nov 2026, 14:00 PT = 23:00 Berlin.** Internal target: submitted by 18:00 Berlin.
Judging 10–16 Dec; the app and the Firebase project stay free and available until 16 Dec.

## Ron (external actions; agents never do these)

- [ ] Register for the hackathon on Devpost (entry on behalf of TamerinTECH GmbH, Ron as Representative).
- [x] `npx firebase-tools login` with the TamerinTECH Google account (24 Nov).
- [x] `gcloud auth login` (24 Nov; the login works, only gcloud's legacy ADC copy could not be written).
- [x] Firebase console: Blaze and AI Logic (Agent Platform) are on: billing enabled, `aiplatform` and
      `firebasevertexai` APIs enabled (read back 24 Nov). See FIREBASE_SETUP.md.
- [ ] Confirm the €10 budget alert exists (the agent cannot read budgets without enabling the Budget API).
- [ ] Pixel 11 Pro: AICore update + AICore Developer Preview; connect by USB with USB debugging.
- [ ] Register the App Check debug token from logcat (console → App Check → Manage debug tokens).
      **Blocks cloud notes:** AI Logic enforces App Check, so unregistered builds get HTTP 401.
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
- [x] After Blaze: Firestore TTL deployed (24 Nov, `expiresAt`; Firestore needs up to a day to activate it).
- [ ] Read back the TTL state as ACTIVE (`gcloud firestore fields ttls list`).
- [ ] Decide on App Check enforcement for Firestore (AI Logic is already enforced).
- [ ] Phone checks on the Pixel (fill in docs/TEST_REPORT.md with real numbers):
  - [ ] Gemini Nano 4 status, download, notes from the demo meeting; badge shows ON_DEVICE.
  - [ ] Offline (airplane mode): does Firebase AI Logic on-device work without network and App Check?
  - [ ] AICore quota/BUSY behaviour on a 30-minute recording.
  - [ ] Cloud notes via `eu`: placeholders out, names back; badge IN_CLOUD; audit entry. Emulator
        reaches AI Logic in `eu` and is refused by App Check (24 Nov); retry after the debug token.
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
