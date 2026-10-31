# IzzAI v0.3 (Android native, offline)

IzzAI is a Kotlin Android chat application running llama.cpp locally using GGUF models. It does not call cloud AI APIs.

## Recommended v0.3 model

- **Qwen2.5-3B-Instruct-GGUF Q4_K_M**, 3.09B parameters, approximately 2.1 GB.
- Publisher: Qwen, https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF
- File: https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf
- Check the model license on its model card before redistribution or commercial use.
- Recommended for testing on 16 GB RAM Android devices; device performance remains unverified.

Download the GGUF on the phone, choose **Import GGUF** inside IzzAI and select it in the Android document picker. When the picker hides unknown GGUF extensions, rename the downloaded file from `.gguf` to `.txt` (do not change its contents), then import. The app restores the original `.gguf` extension within private model storage.

Imported models are stored under their individual filenames, preserving previous imports rather than always overwriting the legacy `izzai-model.gguf`. The selected model path is retained for the next launch. **Chat baharu** now clears both the visible history and native model context.

## Version v0.3 adjustments

- Uses the GGUF model's embedded chat template.
- Qwen3 non-thinking assistant prefix is used only for `qwen3` architecture, not Qwen2.5 `qwen2`.
- Maintains system prompts prioritizing Bahasa Melayu Malaysia.
- Includes a short window of earlier user messages as additional context.
- Includes native session reset to prevent prior conversation carryover.

## Build

Run `Build IzzAI APK` in GitHub Actions, or:

```sh
bash prepare.sh
cd android
bash gradlew assembleDebug
```

## Verification checklist

1. Install as update without deleting previous app data.
2. Import Qwen2.5 3B Q4_K_M; verify model loads.
3. Airplane mode on, Wi-Fi off: ask `Apakah ibu negara Malaysia?` and verify `Kuala Lumpur`.
4. Tell IzzAI `Nama kucing saya Oyen` and ask it to recall.
5. Press `Chat baharu`; verify the model no longer knows `Oyen`.
6. Verify the status bar, navigation bar and keyboard never overlap the UI.

GitHub Actions compilation alone does not establish device-level test success.
