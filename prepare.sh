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
# Fix upstream Android chat formatting for Qwen3 model-specific templates.
# The upstream sample only formats when a template was explicitly supplied,
# which skips a GGUF embedded template. Qwen3 then defaults to thinking.
python3 - "$ROOT/android/lib/src/main/cpp/ai_chat.cpp" <<'PYQWEN'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = "const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());"
assert s.count(old) == 2, "Unexpected llama.cpp template logic"
s = s.replace(old, "const bool has_chat_template = true;")
old = "formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);"
assert s.count(old) == 1, "Unexpected user chat formatting"
# Only prepend Qwen3 nonthinking assistant tokens when the GGUF architecture is qwen3.
# Qwen2.5-3B Instruct must use its own chat template, without Qwen3 tokens.
s = s.replace(old, old + '\n        char model_arch[64] = {};\n        llama_model_meta_val_str(g_model, "general.architecture", model_arch, sizeof(model_arch));\n        if (std::string(model_arch) == "qwen3") {\n            formatted_user_prompt += "<think>\\n\\n</think>\\n\\n";\n        }')
old = "stop_generation_position = current_position + user_prompt_size + n_predict;"
assert s.count(old) == 1, "Unexpected token limit logic"
s = s.replace(old, "stop_generation_position = current_position + n_predict;")
# When starting a new conversation, the existing system-prompt native entry point
# clears llama KV state and conversation history. Also reset sampler token history.
old_system = "reset_long_term_states();\n    reset_short_term_states();"
assert s.count(old_system) >= 1, "Native system prompt reset block changed"
s = s.replace(old_system,
              "reset_long_term_states();\n    reset_short_term_states();\n    common_sampler_reset(g_sampler);", 1)
old_temp = "constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;"
assert old_temp in s, "Native sampler definition changed"
s = s.replace(old_temp, "constexpr float DEFAULT_SAMPLER_TEMP    = 0.0f;")
old_ctx = "constexpr int   DEFAULT_CONTEXT_SIZE    = 8192;"
assert old_ctx in s, "Native context definition changed"
s = s.replace(old_ctx, "constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;")
p.write_text(s)
print("Patched Qwen chat templates, corrected token limit and 4096-token context")

PYQWEN
# Permit changing the system prompt between completed generations.
# The native entry point resets history and KV cache; it does not reload GGUF.
python3 - "$ROOT/android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt" <<'PYRESET'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = 'check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }'
assert s.count(old) == 1, "Upstream system prompt guard changed"
s = s.replace(old, '// IzzAI: allow native system-prompt reset while ModelReady')
p.write_text(s)
print("Patched in-place context reset for subsequent chats")
PYRESET
echo "Prepared android/ using llama.cpp $SHA."
echo "Run: cd android && bash gradlew assembleDebug"
