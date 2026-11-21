# ContextFriend Pro

Native, offline Android and iPhone/iPad meeting companion.

Record → process on this device → review and correct → approve a version → share
selected content directly. No central server, account or cloud inference.

Prototype: Android debug and unsigned release builds pass locally and on AWS,
with eight automated tests passing on both an emulator and a physical Pixel 8 Pro.
Apple simulator and device builds pass, with six tests passing on both an iOS
simulator and a physical iPhone X (iOS 16.7.11).
Personal Apple installation still requires signing; broader device qualification
remains pending. See the
[test report](docs/TEST_REPORT.md) for evidence and known limitations.

- [Original brief](docs/ORIGINAL_BRIEF.md)
- [Current status and next-session checklist](docs/HANDOFF.md)
- [Product and architecture decisions](docs/DECISIONS.md)
- [Build and provisioning](docs/BUILD.md)
- [AWS builds, device tests, costs and resumption](docs/AWS_BUILDS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Device qualification plan](docs/TEST_PLAN.md)
- [Privacy and threat model](docs/PRIVACY_AND_THREAT_MODEL.md)
- [Wire protocol](shared/PROTOCOL.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

Android: `android/`. iPhone/iPad: `apple/`. Portable inference: `native/`.
Shared protocols, manifests and fixtures: `shared/`. Reproducible tooling: `tools/`.

Voice profiles can be reused locally and explicitly copied between your devices.
They never accompany an ordinary shared meeting. Each accepted copy is independent.
