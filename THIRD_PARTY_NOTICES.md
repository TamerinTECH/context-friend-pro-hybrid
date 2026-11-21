# Third-party software, models and fixtures

Pinned code acquisition is in `tools/bootstrap.py`; artifact hashes, sizes and model
revisions are in `shared/MODEL_MANIFEST.json`. Runtime source is in ignored `.deps/`.
License text copies in `shared/licenses/` ship as Android assets. Preserve applicable
copyright and attribution when redistributing binaries or models. The generated Apple project includes the same license folder.

## Direct runtime inventory

| Component | Version / revision | License and upstream |
|---|---|---|
| whisper.cpp | v1.9.5, d1be6fde11ac6e0407606b4e42fe72d34add8037 | MIT, [source](https://github.com/ggml-org/whisper.cpp) |
| llama.cpp / ggml | v0.7.0, 620d2225cc436ab0b001e3200e590e3b286ef9ab | MIT, [source](https://github.com/ggml-org/llama.cpp) |
| sherpa-onnx | v2.0.1, af2d8d81dbfa61c4cea4c568da78f3982edca005 | Apache-2.0, [source](https://github.com/k2-fsa/sherpa-onnx) |
| ONNX Runtime | 1.28.2 Apple build; Android supplied with pinned sherpa AAR | MIT, [source/license](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) |
| swift-sodium | 0.11.0, cfd195c76882aa9b997560ca7cb95d72fbf5db00 | ISC, [source](https://github.com/jedisct1/swift-sodium) |
| libsodium | binary supplied by pinned wrappers | ISC, [source](https://github.com/jedisct1/libsodium) |
| lazysodium-android | 5.2.0 | **MPL-2.0**, [corresponding 5.2.0 source](https://repo.maven.apache.org/maven2/com/goterl/lazysodium-android/5.2.0/lazysodium-android-5.2.0-sources.jar); unmodified wrapper |
| JNA | 5.17.0 | dual LGPL-2.1-or-later / Apache-2.0, [license](https://github.com/java-native-access/jna/blob/5.17.0/LICENSE); Apache option |
| ZXing core | 3.5.3 | Apache-2.0, [source](https://github.com/zxing/zxing) |
| Kotlin + serialization / coroutines | Kotlin 2.1.20; serialization 1.8.1; coroutines 1.10.2 | Apache-2.0 |
| AndroidX / Compose / Material3 | versions pinned in app Gradle; BOM 2025.05.01 | Apache-2.0 |

Android build: AGP 8.13.1, Gradle 8.13, NDK 27.1.12297006, CMake 3.22.1, JDK 21.
Test libraries: JUnit 4.13.2, AndroidX runner 1.6.2/ext-junit 1.2.1/rules 1.6.1,
Compose test APIs; host test dependencies in `tools/requirements-test.txt`.
Swift uses platform SwiftUI, AVFoundation, Core Bluetooth, Network, CryptoKit and
Keychain APIs. No analytics, ad, crash-reporting or hosted inference SDK is configured.

Transitive native dependencies are those of the pinned runtime builds, including
kaldi-native-fbank, kissfft, sentencepiece, utf8proc and sherpa's FST components.
Before public distribution, produce and audit the resolved dependency/license
inventory for the actual Apple binary too; it has not been built in this run.

## Models

- Whisper Tiny multilingual and Small Q5_1: converted by Georgi Gerganov / whisper.cpp
  from OpenAI Whisper; MIT. [Pinned repository](https://huggingface.co/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1).
- Gemma 4 E2B instruction QAT Q4_0 text model: Google; the official repository
  metadata identifies Apache-2.0. [Pinned model](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/tree/675cff42a74c774d6cb76f76d8eacb49b48c9b93),
  [Gemma 4 license](https://ai.google.dev/gemma/docs/gemma_4_license).
  No image/audio projector is downloaded; only text summarization is integrated.
- pyannote segmentation 3.0 int8 ONNX: CNRS / pyannote, converted by sherpa-onnx.
  Same bytes in sherpa-onnx's release archive and in the pinned
  [Hugging Face repository](https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/tree/9403a6902bb58e3d5ae8c7e77c3422de279db2e0)
  the app downloads from. It includes its MIT license, copied here. Ron confirmed acceptance of the
  upstream Hugging Face access conditions on 2026-10-27.
  [Model and conditions](https://huggingface.co/pyannote/segmentation-3.0),
  [conversion documentation](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/models.html).
- TitaNet-S (small) 192-dimensional speaker embeddings: NVIDIA NeMo, converted by
  sherpa-onnx. The **small model's** NGC card links its license to NeMo's Apache-2.0
  license. This is distinct from the large model's license; do not substitute weights
  without rechecking licensing, dimension, hash and compatibility.
  [Small model card](https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/titanet_small),
  [linked NeMo license](https://github.com/NVIDIA-NeMo/Speech/blob/main/LICENSE).

Listed EN/DE/HE languages are evaluation targets, not measured quality claims.
TitaNet's source training distribution does not establish Hebrew speaker performance.
No vendor benchmark is presented as a ContextFriend measurement.

## Test speech

`android/app/src/androidTest/assets/jfk.pcm` is 16 kHz mono PCM16 converted with
FFmpeg from `samples/jfk.mp3` in the pinned whisper.cpp example repository. It is a
short excerpt of John F. Kennedy's US presidential inaugural address, used as a public
natural-speech smoke fixture. [Source sample](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5/samples).
It is not a multi-speaker corpus and cannot qualify German, Hebrew, modern microphones,
privacy-sensitive conversations, diarization accuracy or summary quality.

Protocol vectors and the summary fixture are invented test content. Keys in the
protocol vector are public test material. No private meeting, private signing key,
Hugging Face token or production environment file is included.
