# Pre-existing code and what was built during #BuildHybridAI

The #BuildHybridAI 2026 rules ask entrants to disclose pre-existing code and to summarise
what was built during the submission period (11 Nov 2026, 13:30 PT – 30 Nov 2026, 14:00 PT).

## Before the submission period (disclosed)

The first commit of this repository, `Import pre-existing ContextFriend Pro native
prototype`, contains code written between 27 Oct and 6 Nov 2026 in TamerinTECH's private
`context-friend-pro` repository. It is imported unchanged, except that AWS build
infrastructure, its account and resource records, Google Play notes and local build
outputs were left out.

That prototype is an offline meeting recorder for Android and iOS:

- recording into an encrypted on-device vault;
- transcription with whisper.cpp;
- speaker separation and opt-in voice profiles with sherpa-onnx
  (pyannote segmentation, TitaNet-S embeddings);
- sourced meeting notes from Gemma 4 E2B through llama.cpp, map-reduced over long meetings;
- review, correction and approval of versions;
- recipient-encrypted sharing over QR, Bluetooth LE and local network.

It used no Firebase, no Gemini Nano, no cloud and no Firebase AI Logic.

## During the submission period

Every commit after the first one. The running list, with dates, is in
[CHANGELOG.md](../CHANGELOG.md); `git log` is the authoritative record.

## Relationship to ContextFriend

[ContextFriend](https://www.context-friend.com) is TamerinTECH's existing AI product for
schools. ContextFriend Pro brings its domain knowledge of classroom and school meetings
to phones. No code from the ContextFriend product is included in this repository.
