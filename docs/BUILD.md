# Build and personal installation

This repository is independent of `../context-friend`. Do not run its deployment
workflows, copy production environments, or start its servers for this app.
For the authorized AWS build/test environment, costs and resume commands, see
[AWS_BUILDS.md](AWS_BUILDS.md).

## Provision once while online

Host prerequisites: Python 3.10+, Git, Android Studio/JDK 21, Android SDK 36,
NDK 27.1.12297006, CMake 3.22.1. Android wrapper is Gradle 8.13. On Mac, full Xcode
with the selected iOS platform, CMake and wget are additionally needed.

```powershell
python tools/bootstrap.py
python tools/provision_models.py
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME = "$pwd\.cache\gradle"
Set-Location android
.\gradlew.bat :app:assembleDebug :app:assembleRelease
```

Set the SDK path in untracked `android/local.properties` (`sdk.dir=...`) or
`ANDROID_HOME`. The APKs are under `android/app/build/outputs/apk/`.
Debug uses a local development signing key. Release is **unsigned** unless the untracked
`android/local.properties` names the Play upload key (`upload.storeFile`,
`upload.storePassword`, `upload.keyAlias`); then `:app:bundleRelease` writes the signed
bundle for Play. See [PLAY_STORE.md](PLAY_STORE.md). Install debug with `adb install -r <app-debug.apk>` on an explicitly connected,
USB-debugging-authorized phone. Models are not bundled into the app.

`bootstrap.py` checks exact Git revisions and the sherpa AAR hash.
`provision_models.py` enforces the committed manifest hashes; it never replaces
trusted hashes with whatever a server returns. Downloads use HTTPS; credentials,
if supplied via `HF_TOKEN` or the authorized sibling env file, are sent only to
Hugging Face and stripped on cross-host redirects. No token is embedded in either app.
The downloaded public artifacts in this run did not require a token.

## Offline setup

Copy the APK and the five files in `models/` to the phone by USB, or use Files on
Apple. In Models choose **Import model file**, then the corresponding raw file.
Receiving/reviewing meetings works without models. ASR needs one Whisper model;
speaker processing needs segmentation plus TitaNet; summaries need Gemma 4 E2B.
Keep several GiB of free working space in addition to the 3.4 GiB model pack.
Never put model archives, keys, personal recordings or signing material into Git.

Airplane mode can be used after setup. QR payloads need only cameras/screens.
Bluetooth and LAN are explicit alternatives; no automatic transport fallback.
Building fully offline also requires already cached Maven/Gradle, Swift package,
native source/build dependencies, Apple platform SDKs and signing prerequisites.
Copying weights alone does not provision a clean build machine.

## Apple on Ron's Mac

The checked-in Xcode project is generated deterministically by
`python3 tools/generate_apple_project.py`. It has iOS/iPadOS 16.0 deployment targets,
a shared scheme and XCTest target. Source parsing on Windows is not an Apple build.

```sh
python3 tools/bootstrap.py
python3 infra/aws/provision-ci.py apple-test-models
bash tools/build_apple.sh
xcodebuild -project apple/ContextFriendPro.xcodeproj -scheme ContextFriendPro \
  -destination 'platform=iOS Simulator,name=<installed simulator>' test
```

The script builds real CFNative, sherpa-onnx and ONNX Runtime frameworks; no simulated
inference adapter is used. The CFNative simulator slice currently targets Apple Silicon
Macs (arm64); Intel Mac simulator support is pending. The pinned sherpa build also
builds its x86_64 slice. First build requires network for its pinned dependencies.
Inspect `.cache/apple/sherpa-build.log` if it fails. A native framework already in
`apple/Frameworks` is preserved; move old output aside before rebuilding.

Open `apple/ContextFriendPro.xcodeproj` in Xcode. Add your Apple account, select your
Personal Team under Signing & Capabilities, select the connected iPhone/iPad, enable
Developer Mode if requested, and Run. Let Xcode create the matching provisioning
profile and trust the developer on the device if prompted. Personal-team provisioning
may need renewal/reinstallation; Xcode shows the actual expiry. Do not assume an
unsigned IPA can be installed. Distribution/TestFlight is separate work.

Grant microphone access when recording, camera for QR, Bluetooth for BLE, and local
network only when using LAN. iPhone X is a reader/recorder and light-ASR candidate;
the large summary model is prevented on devices below 6 GiB physical RAM. This
threshold is a conservative guard, not a hardware qualification result.

## Automated tests

Prepare Android's **test-only**, ignored model assets (not shipping APK assets):

```powershell
Copy-Item models/ggml-tiny.bin,models/segmentation.int8.onnx,models/nemo_en_titanet_small.onnx android/app/src/androidTest/assets/
cd android
.\gradlew.bat :app:connectedDebugAndroidTest
```

An emulator/device must already be connected. Instrumentation uses isolated test
vault namespaces. `jfk.pcm` is a short natural speech fixture converted from the
pinned whisper.cpp example; it is not private meeting data. FlowTest records the
test device's microphone for a few seconds and deletes its test session. Its capture is
not evidence of room-microphone quality. Tests can uninstall/reinstall their target.
Never run destructive test fixtures on an app containing valuable user data.

`EndToEndTest` runs the whole pipeline with real models (audio → Whisper → speakers →
Gemma summary, a quiet recording, and a meeting longer than one summary batch). It needs network
for the download test, about 8 GB of device RAM and the Gemma file in the app's storage; it skips
the pipeline tests when that file is absent. If the host loses `am instrument`, the test keeps
running on the device and logcat tag `TestRunner` still reports the result.
`CoreInstrumentationTest` does not replace this test: it calls the native libraries directly and
never goes through `Processing`.

**Never time Whisper or Gemma on a debug build.** Its native libraries have no optimization flags
(`CMAKE_BUILD_TYPE=Debug`). The long-meeting test took 4 h 5 min there and 15–17 min on release code,
on the same emulator. To test the bundle's own code:

```sh
java -jar bundletool.jar build-apks --bundle=app-release.aab --output=device.apks --overwrite --connected-device --device-id=emulator-5554 --adb=<adb>
java -jar bundletool.jar install-apks --apks=device.apks --device-id=emulator-5554 --adb=<adb>
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

bundletool signs with `~/.android/debug.keystore`, the test APK's key, which is what lets
`am instrument` target a non-debuggable app. `run-as` does not work there, so download the models
through the app's own buttons. Replacing a debug install with this needs an uninstall first, which
deletes the app's models; later bundle builds update in place and keep them.

The AVD `ContextFriendPro` lives on `F:\context-friend-pro\avd` since 3 November (about 16 GB), named by
`path=` in `~/.android/avd/ContextFriendPro.ini`. On a debug build:

```sh
PKG=com.tamerintech.contextfriendpro
adb shell "run-as $PKG sh -c 'mkdir -p no_backup/models'"
adb exec-in "run-as $PKG sh -c 'cat > no_backup/models/gemma-4-E2B_q4_0-it.gguf'" < models/gemma-4-E2B_q4_0-it.gguf
adb shell "run-as $PKG sh -c 'printf %s <sha256 from MODEL_MANIFEST.json> > no_backup/models/gemma4-e2b.verified'"
adb shell am instrument -w -r -e class tech.tamerin.contextfriendpro.EndToEndTest $PKG.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s CFE2E:I
```

`exec-in` returns before the device has finished writing: wait until the file has its full
size before going on. The app recomputes the SHA-256 before using the model either way.

```sh
python -m pip install -r tools/requirements-test.txt
python tools/protocol_vectors.py
python tools/check_sources.py
```

The public crypto vector contains deliberately public test keys. It is used by
Python, Android and Apple tests; do not reuse these keys for content.
Apple's test-only fixtures live in ignored `apple/ContextFriendProTests/Fixtures/`.
The provisioning command verifies the same three model hashes as Android and
copies the public JFK PCM fixture. They are resources of the XCTest bundle, not
the normal app target. The Apple tests exercise real Whisper and the native
speaker bridge as well as storage and protocol logic.

## Windows summary smoke probe

The exact portable inference bridge can also be compiled with MSVC/CMake:

```powershell
cmake -S native -B .cache/native-windows -G 'Visual Studio 18 2026' -A x64
cmake --build .cache/native-windows --config Release --target cf_probe
.cache/native-windows/Release/cf_probe.exe models/gemma-4-E2B_q4_0-it.gguf shared/summary-fixture.txt
```

Use the generator installed on your host. This is a development test, not a mobile
service, and the phones never connect to it. CPU timings on this PC do not predict
phone performance.

Primary platform instructions: [Xcode requirements](https://developer.apple.com/xcode/system-requirements/),
[Apple membership and personal development](https://developer.apple.com/help/account/membership/program-enrollment/),
[Android microphone foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types).
