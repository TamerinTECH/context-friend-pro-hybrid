# Pending

Status: **Thursday 26 November 2026, 09:35 Berlin time.** Deadline: **Monday 30 November, 23:00 Berlin**
(internal target 18:00). Judging 10–16 December; the app and the Firebase project stay available until
16 December. What is finished is in [DONE.md](DONE.md).

## Ron (only you can do these)

1. ~~Connect the Pixel 11 Pro~~ done 25 Nov; Gemini Nano answers since AICore's own chat was opened (26 Nov).
2. ~~App Check debug tokens~~ registered for the emulator and the Pixel (delete them after testing).
3. **`gcloud auth login`** again: Google asks for reauthentication, so the agent cannot read back the
   Firestore TTL state.
4. **Confirm the €10 budget alert** exists on the billing account.
5. **Judge access:** decide on a Play Integrity release (link the Cloud project in Play Console, register
   the release SHA-256 in App Check) or the fallback (judges see the on-device features; the video shows
   the cloud path). See [SUBMISSION_CHECKLIST.md](SUBMISSION_CHECKLIST.md).
6. **Logo:** confirm the vector redraw in [brand/](brand/README.md), or send the original SVG or AI file
   and I swap it in.
7. **Review the redesign screenshots** (`D:\work\TamerinTECH\screenshots\contextfriend-pro-redesign-2026-11-25\`).
8. **Devpost:** register (TamerinTECH GmbH, you as Representative), find two people to read the demo
   script, record the video on the Pixel ([VIDEO_SCRIPT.md](VIDEO_SCRIPT.md)), upload to YouTube with
   English subtitles, review [DEVPOST.md](DEVPOST.md), submit, and save the confirmation.

## Agent, on the Pixel

- ~~Gemini Nano notes with the label "Created on this phone · Gemini Nano"~~ done 26 Nov (`nano-v4-full`).
- ~~A real speech recording end to end (record → automatic notes in the background)~~ done 26 Nov; review →
  share still to run on the Pixel.
- Gemini Nano in airplane mode; AICore quota and BUSY behaviour on a 30-minute recording.
- Easy setup: one-tap "Get this phone ready" on first run, an always-visible "Local AI · ready" line,
  Gemini Nano without developer settings (stable model), Gemma as automatic fallback.
- Cloud notes through `eu` once the debug token is registered: placeholders out, names back, label
  "Cloud-assisted", previous notes restorable.
- QR scan and encrypted link between the Pixel and the emulator; phone-to-phone transfer.
- Screenshots on the Pixel in English, German and Hebrew.
- Record the results in [TEST_REPORT.md](TEST_REPORT.md) and remove the "(phone check pending)" markers.

## Agent, release and submission

- Signed release APK as a GitHub Release with checksums.
- Read back the Firestore TTL as ACTIVE; decide App Check enforcement for Firestore.
- Final scan of the history for keys, tokens and real names.

## Redesign items cut for the deadline

From the design review ([design/README.md](design/README.md)), deliberately left for after the submission:

- Dark theme (the tokens exist; the app is light only for now).
- Preview received notes before they are saved (today they are saved, then shown).
- Edit or remove single sentences on "Review what you send" (today: tap a word to replace it).
- The recording timer counts paused time (existing behaviour from the prototype).
- Revoke an encrypted link from the app (the code exists, there is no button yet).
- The full error-state matrix: low storage during recording, audio route changes, process death.
- Checks at 200 % font size and 320 dp width, a TalkBack pass, reduced motion.
- The share preview repeats the title in the first line of the text.

## Known limits

- Speech to text is very slow on the emulator (its CPU), so timings must come from the Pixel.
- Gemini Nano can be refused although AICore reports it available, until AICore has its feature
  configuration (on the Pixel: after AICore's own chat was opened). The app says "not ready yet" and
  offers Retry.
- Gemini Nano works only while the app is on screen: in the background the notes wait for the next launch.
- Gemini Nano is used for English only (validated languages); German and Hebrew use the ContextFriend
  engine (3.3 GB download).
- A sideloaded APK fails App Check for the cloud unless Play Integrity is set up (item 5).
