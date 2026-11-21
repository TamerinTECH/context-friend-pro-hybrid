# Changelog

Hackathon work for #BuildHybridAI 2026. The first commit is disclosed pre-existing code
(docs/PRE_EXISTING.md); everything listed here was built during the submission period.

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
