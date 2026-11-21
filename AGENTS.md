# ContextFriend Pro

Read README.md, docs/DECISIONS.md and docs/ORIGINAL_BRIEF.md before editing.
The brief plus Ron's decisions below are the specification. This repository is
independent of ContextFriend Central, the Box, schools and their production releases.

- Native Kotlin/Compose Android and Swift/SwiftUI iPhone/iPad. No backend, accounts,
  remote inference, telemetry or automatic network fallback.
- Ron's personal meetings last 30–60 minutes. Reference hardware: Pixel 11 Pro,
  iPad Air (2026), and iPhone X for reduced-capability/reader testing. Mac access later.
- Explicit opt-in voice profiles persist across meetings. A separate, explicitly
  approved encrypted profile transfer can copy selected profiles between devices.
  Ordinary meeting sharing never includes profiles. Imported profiles require
  compatible model/preprocessing hashes and recipient confirmation. No silent sync.
- User content stays encrypted at rest. Recording starts only on a visible action.
  No fabricated transcripts, speaker embeddings, summaries, benchmarks or hashes.
- Keep implemented, compiled, emulator-tested and physical-device-tested distinct.
  Apple builds require Mac/Xcode. Never claim Windows validated an iOS build.
- Do not publish, spend money, upload recordings, or touch the sibling live services.
- Exception authorized by Ron on 2026-10-27: use AWS profile `mbot` for minimum-cost
  US build/test resources for this app, with cleanup and resume documentation.
  See docs/AWS_BUILDS.md. This does not authorize publishing or personal-data uploads.
- Ron subsequently authorized committing and pushing this prototype and its docs
  to the private `TamerinTECH/context-friend-pro` repository. This does not authorize
  an app-store release, public visibility or uploads of ignored local artifacts.
- Ron authorized on 2026-11-03 starting the Google Play process for Android: a private
  draft in the TamerinTECH developer account and internal testing only. Sending the app
  for review or any wider track still needs his explicit go. See docs/PLAY_STORE.md.
- Every Slack message begins exactly `My agent says: `.
- Do not use Computer Use unless explicitly requested. CLI builds and automated
  application tests are the development verification path.
- Run meaningful tests/builds after changing their implementation. Record commands,
  outcomes and remaining limitations in docs/TEST_REPORT.md.
