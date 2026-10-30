#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
UPSTREAM="$ROOT/.upstream-llama"
SHA="a34de8618b25dfc32ee43749db47d767a9f0a25d"
if [ ! -d "$UPSTREAM/.git" ]; then
  git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$UPSTREAM"
fi
git -C "$UPSTREAM" fetch --depth 1 origin "$SHA"
git -C "$UPSTREAM" checkout --detach "$SHA"
# Build using the official llama.android Gradle project and native library.
mkdir -p "$ROOT/android"
cp -a "$UPSTREAM/examples/llama.android/." "$ROOT/android/"
# The upstream project relies on the parent repository's native source tree.
# Symlink from android/lib to the full upstream tree remains possible through an absolute CMake path.
cp "$ROOT/overlay/MainActivity.kt" "$ROOT/android/app/src/main/java/com/example/llama/MainActivity.kt"
# CMake in the upstream example assumes llama.android lives under llama.cpp/examples/.
# In this standalone overlay package we explicitly use the checked-out source tree.
python3 - "$ROOT/android/lib/src/main/cpp/CMakeLists.txt" "$UPSTREAM" <<'PYCM'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); source=pathlib.Path(sys.argv[2]).as_posix()
s=p.read_text()
old="set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)"
assert old in s, "Upstream native CMake layout changed"
p.write_text(s.replace(old, f'set(LLAMA_SRC "{source}")'))
PYCM
# Keep the upstream package namespace to avoid breaking C++ / JNI symbols.
# Give the app its own appId and title.
sed -i 's/applicationId = "com.example.llama.aichat"/applicationId = "com.daddyizz.izzai"/' "$ROOT/android/app/build.gradle.kts"
# Patch the app's label in the existing strings resource.
python3 - "$ROOT/android/app/src/main/res/values/strings.xml" <<'PY'
import pathlib,re,sys
p=pathlib.Path(sys.argv[1]); s=p.read_text(); s=re.sub(r'(<string name="app_name">).*?(</string>)', r'\1IzzAI\2',s, count=1); p.write_text(s)
PY
# Android sample project lives within repo; point its CMake relative path to source tree.
# Keep entire native tree available adjacent to Android project.
ln -sfn "$UPSTREAM" "$ROOT/llama.cpp"
echo "Prepared android/ using llama.cpp $SHA."
echo "Run: cd android && bash gradlew assembleDebug"
