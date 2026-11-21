"""CI needs only the pinned sherpa-onnx Android archive to compile Kotlin and run JVM unit tests.
The native sources (whisper.cpp, llama.cpp) come from tools/bootstrap.py for full builds."""
from acquire import DEPS, fetch

URL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar'
SHA256 = 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'

result = fetch(URL, DEPS / 'sherpa-onnx.aar')
if result['sha256'] != SHA256:
    raise SystemExit('sherpa-onnx archive checksum mismatch')
print('sherpa-onnx.aar verified', result['bytes'], 'bytes')
