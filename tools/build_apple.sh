#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[[ "$(uname)" == Darwin ]] || { echo "Requires macOS and full Xcode."; exit 1; }
python3 tools/bootstrap.py
xcodebuild -version
mkdir -p apple/Frameworks .cache/apple/headers
# sherpa's exact pinned upstream build creates simulator and device frameworks.
# Its CMake dependency downloads include upstream hashes. Inspect its build log.
(cd .deps/sherpa; bash build-ios.sh) 2>&1 | tee .cache/apple/sherpa-build.log
cp -R .deps/sherpa/build-ios/sherpa-onnx.xcframework apple/Frameworks/
cp -R .deps/sherpa/build-ios/ios-onnxruntime/1.28.2/onnxruntime.xcframework apple/Frameworks/
cp native/include/cf_native.h .cache/apple/headers/CFNative.h
printf 'module CFNative { header "CFNative.h" export * }\n' > .cache/apple/headers/module.modulemap
for platform in device simulator; do
  sdk=iphoneos; [[ "$platform" == simulator ]] && sdk=iphonesimulator
  build="$ROOT/.cache/apple/$platform"
  cmake -S native -B "$build" -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO
  cmake --build "$build" --config Release --parallel 4
  find "$build" -name '*.a' -path '*Release*' -print > "$build/archives.txt"
  [[ -s "$build/archives.txt" ]] || { echo "No native archives built"; exit 1; }
  /usr/bin/libtool -static -o "$build/libCFNative.a" -filelist "$build/archives.txt"
done
if [[ -e apple/Frameworks/CFNative.xcframework ]]; then
  echo "CFNative.xcframework already exists; move it aside before rebuilding."; exit 1
fi
xcodebuild -create-xcframework -library .cache/apple/device/libCFNative.a -headers .cache/apple/headers -library .cache/apple/simulator/libCFNative.a -headers .cache/apple/headers -output apple/Frameworks/CFNative.xcframework
python3 tools/generate_apple_project.py
xcodebuild -project apple/ContextFriendPro.xcodeproj -scheme ContextFriendPro -sdk iphonesimulator -configuration Debug -derivedDataPath .cache/apple/DerivedData CODE_SIGNING_ALLOWED=NO build
