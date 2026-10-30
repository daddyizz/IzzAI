# IzzAI v0.2 (Android, offline)

Native Android Kotlin frontend for an on-device GGUF model. Integration base: the official `ggml-org/llama.cpp` `examples/llama.android` project at commit `a34de8618b25dfc32ee43749db47d767a9f0a25d`.

## What is implemented

- Kotlin chat interface and streaming inference using upstream `AiChat` / `InferenceEngine` native runtime
- Import GGUF with Android's document picker, copy into the app's private model storage
- Saved chat locally in `conversation.json` with a 100-message cap
- Filter visible `<think>...</think>` and `[Start thinking]...[End thinking]` traces
- Offline inference once the APK and model are on the phone
- No external AI service, no cloud account, no API key

## Build via GitHub Actions

Upload this folder to a **new** GitHub repository (for example `daddyizz/IzzAI`) including `.github/workflows/build-apk.yml`, then run `Build IzzAI APK`. The workflow checks out a pinned version of the official native engine, patches its Android example, compiles a debug APK and publishes it as an artifact. No APK is bundled in this ZIP.

Or use Linux / Android Studio with Android SDK+NDK:

```sh
bash prepare.sh
cd android
bash gradlew assembleDebug
```

## Reuse the existing model from Termux

The app **cannot** directly access `~/izzai/models` inside Termux due to Android sandboxing. Run this in Termux first:

```sh
termux-setup-storage
cp ~/izzai/models/Qwen3-0.6B-Q4_K_M.gguf ~/storage/downloads/
```

Open IzzAI, tap **Import GGUF**, select the copied `.gguf` file in Downloads. The app creates its own internal copy. After the model loads, turn off mobile data and Wi-Fi to test offline.

## Important limitations

- This is a build-ready source candidate, **not** a device-tested APK. The native engine build and NDK setup may need fixes once CI output is available.
- The model and chat history are local to the app, but the UI does not yet feed saved chat history back into inference after app restart. Only the native engine's active session has conversation continuity.
- Model loading and generation may be resource-heavy; context/window and token limits depend on the upstream runtime.
- For greater privacy, avoid sharing any exported chat data or GGUF files with untrusted apps.
- App label, theme and accessibility require device QA.
