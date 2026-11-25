# Firebase setup

How to give a build of ContextFriend Pro Hybrid its own Firebase project. Without one, the app
still records, transcribes, separates speakers and writes notes with the ContextFriend engine
(Gemma 4); Gemini Nano, cloud notes and cloud links then show "not configured".

Nothing below is a secret except App Check debug tokens and keystores. Still, the Firebase
options live in untracked `android/local.properties`, never in Git.

## 1. Project and plan

1. Create a Firebase project (console, or `npx firebase-tools projects:create <id>`). Ours is
   `contextfriend-pro-hybrid`. In Claude Code or another non-interactive shell,
   `npx firebase-tools login` prints a link instead of opening the browser; open it, then run
   `npx firebase-tools login <code>` with the code it shows.
2. **Upgrade to Blaze** and set a budget alert (we use €10). Firebase AI Logic with the
   Vertex AI (Agent Platform) backend always needs Blaze. The free Gemini Developer API tier
   is not an option for this app: the Gemini API terms allow only paid services for apps
   offered to users in the EEA, UK or Switzerland.

## 2. Firebase AI Logic (Vertex AI / Agent Platform backend)

Console → **AI Logic** → Get started → choose the **Agent Platform (Vertex AI) Gemini API**.
This enables `aiplatform.googleapis.com` and `firebasevertexai.googleapis.com` and turns on
App Check enforcement for AI Logic. The app uses location `eu` (the EU multi-region: data
residency and ML processing in EU member states). Change it with `firebase.vertexLocation`.

App Check enforcement for AI Logic starts at once (App Check service `firebaseml.googleapis.com`).
Until a build's debug token is registered (section 4), every cloud request fails with
`401 Firebase App Check token is invalid`, and the app says the copy could not be verified. Check the
state with:

```bash
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "x-goog-user-project: <id>" \
  https://firebaseappcheck.googleapis.com/v1beta/projects/<id>/services
```

## 3. Android app

Register an Android app with package `com.tamerintech.contextfriendpro`, then copy its options
into `android/local.properties`:

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
firebase.apiKey=<from the Android app config>
firebase.appId=1:<number>:android:<hash>
firebase.projectId=<project id>
firebase.senderId=<project number>
# optional, default eu
firebase.vertexLocation=eu
```

`npx firebase-tools apps:sdkconfig ANDROID <appId>` prints these values.
No `google-services.json` and no Google Services Gradle plugin are needed.

## 4. App Check

- **Debug builds** use the debug provider. Run the app once and look in logcat for
  `DebugAppCheckProvider: ... register it with your project`, which prints the token and a ready
  `firebase appcheck:debugtokens:create <token> --project <id> --app <appId>` command. Register it
  that way or under App Check → Apps → your app → **Manage debug tokens**. Never commit or share it,
  and delete it when you are done (`appcheck:debugtokens:delete`).
- **Release builds** use Play Integrity. Link the Cloud project in Play Console
  (Release → App integrity → Play Integrity API → Link Cloud project) and register the
  signing certificate's SHA-256 in App Check. For APKs installed outside Google Play, set
  `PLAY_RECOGNIZED` and `LICENSED` to not required and the minimum to device integrity.
- Enforce App Check for **Firebase AI Logic** and **Cloud Firestore**.

## 5. Authentication

Enable the **Anonymous** sign-in provider. The app signs in anonymously so that Firestore
rules and per-user AI Logic limits have a user. No personal account exists. `firebase.json`
declares it, so one command does it:

```bash
npx firebase-tools deploy --only auth --project <id>
```

## 6. Firestore (encrypted cloud links only)

`firebase.json` pins the default database to **eur3** (Europe multi-region). Set the location
before the first deploy: without it the CLI creates the database in `nam5` (US), and a Firestore
location can never be changed. One deploy enables the API, creates the database and releases the
rules:

```bash
npx firebase-tools deploy --only firestore:rules --project <id>
```

The TTL policy that deletes expired links (`shares.expiresAt`) is in
`firebase/firestore.indexes.json`. Firestore TTL needs billing, so deploy it after the Blaze upgrade:

```bash
npx firebase-tools deploy --only firestore:indexes --project <id>
```

## 7. Remote Config

```bash
npx firebase-tools deploy --only remoteconfig --project <id>
```

| Key | Default | Meaning |
|---|---|---|
| `cloud_enabled` | `true` | Kill switch. `false` = local-only mode. Consent can never be switched off. |
| `cloud_model` | `gemini-4-hydrogen` | Cloud model for opt-in redacted notes. |
| `nano_model_option` | `PREVIEW` | `STABLE`, `PREVIEW` (Gemini Nano 4 Full) or `PREVIEW_FAST`. |
| `nano_chunk_chars` | `3500` | Transcript characters per Gemini Nano request (clamped 1000–8000). |
| `extra_protected_terms` | `[]` | Words always replaced before a cloud request, e.g. the school's name. |

## 8. Gemini Nano on the phone

Supported: Pixel 10 and 11 families and other Gemini Nano 4 devices on the
[ML Kit GenAI list](https://developers.google.com/ml-kit/genai). For Gemini Nano 4
(the `PREVIEW` option):

1. Update **Android AICore**: Settings → Apps → All services → System services → Android AICore → Update
   (on Pixel: Settings → your name → All services → System services).
2. Turn on Developer options (tap Build number seven times).
3. Settings → System → Developer options → **AICore Settings** → **Enable AICore Developer Preview** → Accept.
4. Open ContextFriend Pro → **Settings → On-device AI** → the Gemini Nano card shows *Ready*, or tap
   **Download Gemini Nano** (Wi-Fi; a few minutes).
