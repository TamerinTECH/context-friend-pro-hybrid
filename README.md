# ContextFriend Pro Hybrid

**Meeting notes for teachers, where the conversation never has to leave the phone.**

ContextFriend Pro records a school meeting, such as a parent–teacher conference, a
support-plan meeting or a counselling session. It transcribes the meeting, tells the
speakers apart and writes sourced notes, all on the phone. The cloud is used only when
the teacher asks for it: names are replaced on the device first, the teacher sees exactly
what will be sent, and the answer is put back together on the phone.

> **Status (22 Nov 2026): work in progress** for #BuildHybridAI 2026, the Firebase AI
> Logic hybrid hackathon. The native recorder in the first commit is disclosed
> pre-existing work ([docs/PRE_EXISTING.md](docs/PRE_EXISTING.md)). What is built during
> the hackathon is listed in [CHANGELOG.md](CHANGELOG.md). This README only lists features
> that are built and tested; planned ones are marked as planned.

## How the work is split

| Step | Where it runs | Engine |
|---|---|---|
| Record, store | Phone, encrypted vault | Native Android audio + libsodium |
| Transcribe | Phone | whisper.cpp (ContextFriend engine) |
| Tell speakers apart | Phone | sherpa-onnx: pyannote segmentation + TitaNet-S |
| Meeting notes | Phone | **Gemini Nano via Firebase AI Logic, `ONLY_ON_DEVICE`** *(planned)*; Gemma 4 E2B via llama.cpp for other languages |
| Better notes, if the teacher asks | Cloud, EU region | **Gemini via Firebase AI Logic**, redacted text only, with consent *(planned)* |
| Share | Phone to phone (QR, Bluetooth, local network); optional end-to-end-encrypted cloud link *(planned)* | libsodium; Firestore stores ciphertext only |

## Licence

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Third-party components:
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## More

- [Decisions](docs/DECISIONS.md) · [Architecture](docs/ARCHITECTURE.md) ·
  [Privacy and threat model](docs/PRIVACY_AND_THREAT_MODEL.md)
- [Build](docs/BUILD.md) · [Test report](docs/TEST_REPORT.md) ·
  [Original prototype brief](docs/ORIGINAL_BRIEF.md)
