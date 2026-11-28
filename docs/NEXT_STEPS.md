# Next steps to the submission

Status: **Saturday 28 November 2026, 13:15 Berlin time.** Deadline: **Monday 30 November, 23:00 Berlin**
(14:00 PT). Our target: submitted by **Monday 18:00**.

Live now:

- Landing page: **https://tamerintech.github.io/context-friend-pro-hybrid/**
- APK: **https://github.com/TamerinTECH/context-friend-pro-hybrid/releases/latest** (1.0.0 release
  candidate 1, signed with the upload key; App Check accepts it on real phones through Play Integrity)
- Repository: https://github.com/TamerinTECH/context-friend-pro-hybrid

## Timeline

| When | Who | What |
|---|---|---|
| **Sat** | Ron | Register on Devpost if not done (TamerinTECH GmbH, Ron as Representative); check that the rules allow a company entry and note the video length limit |
| **Sat** | Ron | Book the two readers for the video (real voices, no TTS) |
| **Sat** | Ron | **Device test** below (about 60 minutes, including downloads). Earlier is better: fixes land on Sunday morning |
| Sat | Ron | Confirm the €10 budget alert; check that the landing page's Impressum link (www.tamerintech.com) leads to the company's legal notice, or send me the right URL |
| **Sun morning** | Agent | Fix what the device test finds; release candidate 2 or the final 1.0.0; Pixel screenshots on the landing page and in the README; real numbers in README, TESTING and DEVPOST |
| **Sun** | Ron + readers | Record the video on the Pixel with the release build ([VIDEO_SCRIPT.md](VIDEO_SCRIPT.md)); upload to YouTube as public with English subtitles; send me the link |
| Sun evening | Agent | Video link on the landing page and in DEVPOST.md |
| **Mon morning** | Agent | Final scan of the history for keys, tokens and real names; delete the App Check debug tokens; final release if anything changed |
| **Mon by 18:00** | Ron | Devpost: paste [DEVPOST.md](DEVPOST.md), add the links (repository, landing page, APK, video), platform Android, **submit**, save a screenshot of the confirmation in `docs/` |

## Device test (Ron, release build)

Use the Pixel 11 Pro. A second Android phone without Gemini Nano is a bonus (it tests the ContextFriend
engine path judges will see most). Note **OK** or what went wrong for each step; a screenshot helps.

**Before you start**

- Screenshots and screen recording are blocked in the app until you allow them: after the first launch,
  **Settings → Privacy → Allow screenshots and screen recording**. You need this for the video too.
- The debug build must go first, because the release is signed with a different key. Uninstalling deletes
  the podcast test meetings and the downloaded models on the phone: Settings → Apps → ContextFriend Pro →
  Uninstall (or tell me and I run `adb uninstall`).

**A. Install and first launch** (about 10 minutes with the download)

1. On the phone, open the landing page → **Download for Android** → open the file → allow your browser to
   install apps → Install. Play Protect may warn about an app from outside the Play Store: note the exact
   message, then install anyway.
2. Open the app. Expected: a white splash where the logo pops in, its sound bars move, the eyes blink,
   then it fades into the app (about one second).
3. Welcome, step 1 (moving level bars) → **Next**; step 2 (shield) → **Next**; step 3 lists speech to
   text, speakers and notes. Tap **How local AI works on this phone**: the guide opens; back returns.
4. **Get this phone ready** → **Download** (120 MB, Wi-Fi). Expected: progress, then "This phone is
   ready". Back on Meetings: "Ready offline: notes are written on this phone by Gemini Nano."

**B. Recording and the notification** (5 minutes)

5. **New recording** → title "Device test" → tick that everyone knows → **Start recording**. Speak, or
   play the demo script from the PC. Expected: level bars move, "The microphone is picking up sound",
   **Sound heard** counts up.
6. Press Home. Expected: a "Recording" notification with a running timer, **Pause** and **Finish**, in the
   normal section (not under "Silent"), with an icon in the status bar. Tap **Pause** → "Paused at …";
   tap **Resume**. Tap the notification itself → the app opens on the recording screen.
7. Record about three minutes in total, then **Finish recording**.

**C. Notes in the background** (5 minutes)

8. Expected: the meeting shows "Creating your notes" with a percentage, and the notification shows the
   percentage too. Lock the phone for a minute; unlock: the percentage moved on. At the end: **Created on
   this phone · Gemini Nano**, "Draft for review", and every point has sources that jump to the transcript.
9. Optional: start another recording while notes are being made. Expected: allowed; the running notes
   show "Paused · N %" and continue after you finish.

**D. Cloud notes through Play Integrity** (the judges' cloud path, 3 minutes)

10. **Settings → Cloud assistance → Names to protect**: add the first names that were spoken.
11. Open the meeting → **Improve with Gemini (optional)** → **Review what you send**: names appear as
    `[PERSON_1]` … → **Send once to Gemini**. Expected: the label says **Cloud-assisted · Gemini, names
    replaced**; **⋮ → Switch to previous notes** brings back the on-device notes.
    If it says "Cloud processing could not verify this copy of the app", Play Integrity refused the phone:
    note the exact text and plug the phone in for me.
12. **Settings → Privacy activity**: one entry "On this phone" for the notes, one "Cloud" entry for the
    request, with the number of replaced items.

**E. Offline** (2 minutes)

13. Airplane mode on → open the meeting → **⋮ → Create notes again**. Expected: new notes from Gemini
    Nano without the internet. Airplane mode off.

**F. Share** (2 minutes)

14. **Mark as reviewed** → **Share summary** → **Encrypted link** → **Create link**: a QR code and
    **Copy link**. With a second phone: **Receive → Scan code** opens the notes there.

**G. Languages** (optional, 2 minutes)

15. **Settings → Language → Deutsch**, then **עברית**: the main screens read correctly; Hebrew is
    right-to-left.

When you are done, send me the list, or plug in the Pixel and I check the logs and take the screenshots
for the landing page and the README.

## If something fails

| Symptom | What to do |
|---|---|
| Notes: "Gemini Nano is not ready on this phone yet" | Open the in-app **Local AI guide**: update AICore, open AICore Settings once, wait a minute, **Refresh**, then **Retry**. On Thursday this was the fix on the Pixel. |
| Cloud: "could not verify this copy of the app" | Play Integrity refused this phone (an emulator or a modified phone always is). Note the message; I check App Check. |
| "No sound yet" while people speak | Move the phone closer; check that no other app uses the microphone. |
| The app was closed while notes were made | Open it again: the notes continue from where they stopped. |
