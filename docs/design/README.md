# Redesign, 25 November 2026

The app was redesigned after a design review of the 24 November screenshots: eight concept boards and a
written handoff. The handoff and its design tokens are kept here as the reference:

- [redesign-handoff-2026-11-25.txt](redesign-handoff-2026-11-25.txt): navigation, screens, states, copy,
  acceptance criteria.
- [design-tokens.json](design-tokens.json): the "Quiet Blue" palette, type scale, spacing.

## What was built

| Handoff | In the app |
|---|---|
| Three bottom destinations: Meetings, Receive, Settings | `AppShell.kt` |
| One primary action per screen, other actions in a menu, no horizontal action rows | `MeetingScreen.kt` |
| Recording setup, full-screen recording with a real level signal | `MeetingsScreen.kt` |
| Notes start automatically after recording, real steps | `AppShell.kt`, `MeetingScreen.kt` |
| No speech, failed, interrupted, draft, reviewed, received as distinct states | `MeetingState.kt` (unit-tested) |
| Provenance per result, no global "processed on this device" | `MeetingState.kt`, `MeetingScreen.kt` |
| Review resets after edits; earlier notes kept and restorable | `Domain.kt`, `MeetingState.kt` |
| One "Share summary" entry: encrypted link, nearby phone, another app; drafts labelled | `ShareScreens.kt` |
| Full-page "Review what you send": text first, replacements highlighted, exact request expandable | `ShareScreens.kt` |
| Receive: scan, paste, nearby | `ShareScreens.kt` |
| Settings groups; On-device AI; Get ready for offline notes; readable privacy activity | `SettingsScreens.kt` |
| Complete colour scheme, 16/14 sp text, 48 dp targets, status bar contrast, insets | `Theme.kt`, `Ui.kt`, `MainActivity.kt` |
| Localized dates, isolated technical values in Hebrew, wrapping German labels | `Ui.kt` |
| Copy changes (section 7) | `shared/translations.json` |

## Not built yet

See [../PENDING.md](../PENDING.md#redesign-items-cut-for-the-deadline): dark theme, a preview before
saving received notes, sentence editing on the cloud review, the paused-time timer, revoking links in the
app, the full error-state matrix, and the font-scale, narrow-width and TalkBack checks.

## Two points where the app differs from the handoff

- **"Automatic" never falls back to the cloud.** The handoff asks to verify this. It is enforced in code:
  Gemini Nano runs with `ONLY_ON_DEVICE`, cloud notes with `ONLY_IN_CLOUD`, and every answer's
  `inferenceSource` is checked; a mismatch is discarded and logged (`Policy.verifySource`, unit-tested).
- **The hybrid story stays visible** for the hackathon: "On-device AI" and "Privacy activity" are the first
  rows in Settings, and the cloud option is offered as a card under each local summary.
