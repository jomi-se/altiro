# Development

## Pinned toolchain

| Tool | Version |
| --- | --- |
| JDK and bytecode | 21 |
| Gradle | 9.6.0; wrapper SHA-256 is pinned |
| Android Gradle Plugin | 9.4.1 |
| Kotlin and Compose compiler | 2.4.20 |
| Compose BOM | 2026.09.00 |
| AndroidX Activity | 1.13.0 |
| Coroutines | 1.11.0 |
| Compile/target SDK | API 37, Platform 37.0 |
| Minimum SDK | API 33 |
| Build Tools | 36.0.0 |
| NDK | 30.0.16248370 |
| CMake | 4.1.2 |
| whisper.cpp | 1.9.4; exact commit/archive SHA-256 pinned in CMake |
| Spotless / ktfmt | 8.0.0 / 0.63 |

Pins live in [the version catalog](../gradle/libs.versions.toml). AGP 9 supplies
built-in Kotlin for Android; root JVM/Compose plugins resolve the matching
pinned Kotlin version. No legacy Kotlin Android plugin is applied. Native
source is fetched at an exact revision/hash into external build output.

Compatibility sources: [AGP release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
and official Google/Maven artifact metadata. Executing this build is the check
of this particular combination.

## Setup

Install JDK 21 and an Android SDK on a supported host. Install
`platforms;android-37.0`, `build-tools;36.0.0`, `ndk;30.0.16248370`, and
`cmake;4.1.2`; platform-tools is needed for
device work. Set `ANDROID_HOME` or an ignored `local.properties` SDK path.
Use the committed Gradle wrapper.

Android builds now include the experimental Vulkan backend. Install a host
`glslc` shader compiler (pinned shaderc 2023.8; tested glslang 14.0.0),
on PATH or set `ALTIRO_GLSLC` to its executable. It runs on the build host, not
Android. Vulkan-Headers/Hpp 1.4.321 and SPIRV-Headers Vulkan SDK 1.4.321.0 are
fetched with exact revisions/archive hashes in CMake; Android's Vulkan loader
comes from the NDK/system. Host JNI smoke builds default to CPU-only; use
`-DALTIRO_VULKAN=ON` only with a configured host Vulkan SDK/compiler.
The pinned runtime receives audited changes from
[`patch-whisper-gpu.py`](../scripts/patch-whisper-gpu.py): reject failed requested
GPU initialization, expose total compute counters/active backend, and propagate
Ninja into the host shader-generator build. It also applies a Clang Android
Release `optnone` attribute only to shader-pipeline registration, avoiding an
enormous inlined function during compilation. Numerical CPU compute, other
Vulkan dispatch functions and GPU shaders keep their optimization settings;
phone timings include the registration cost. Anchor
checks fail on source drift; runtime source and upstream licenses remain pinned.
CI uses Ubuntu 24.04 and `glslc=2023.8-1build1`; CMake rejects a different
shaderc release until its shaders have been reviewed/tested. Tool upgrades
require rebuilding and repeating the phone experiment. Android Release builds
omit DWARF debug metadata only for the large Vulkan dispatcher translation unit
(`-g0`); CPU code and GPU shader optimization settings are unchanged. Installed
APKs strip native debug information. Use a Debug native configuration when
source-level dispatcher debugging is needed.

Check host architecture first. Official Linux Android resource tools and
emulators may require x86-64. Host emulation and machine setup are operator
concerns, not portable product instructions. Source/JVM work does not require
an emulator or physical device.

## Commands

```sh
./scripts/quiet-run.sh "verification" ./scripts/verify.sh
./scripts/quiet-run.sh "core" ./scripts/verify.sh --core-only
./scripts/quiet-run.sh "docs" ./scripts/verify.sh --docs-only
./gradlew spotlessApply
./gradlew :app:installDebug :editor-fixture:installDebug
./gradlew :app:connectedDebugAndroidTest :editor-fixture:connectedDebugAndroidTest
```

Verification automatically formats Kotlin and Gradle scripts with ktfmt before
compilation. There are no ktlint style or line-length rules; formatting is owned
by the formatter. The same command runs locally and in CI.

Full verification runs core tests, debug builds, Android lint,
instrumentation APK compilation, and 16 KiB alignment of bundled native
libraries. Connected tests execute separately.
They supplement the [physical Gate A procedure](testing/gate-a.md).

Application IDs are `org.altiro.app` and `org.altiro.fixture`. Core is pure
Kotlin. Inference builds the JNI library; network adapters remain reserved.

For a host smoke test of the actual production JNI bridge, supply the verified
base model and a canonical mono PCM16/16 kHz copy of upstream's JFK sample:

```sh
./scripts/quiet-run.sh "native smoke" ./scripts/check-whisper-native.sh /path/to/ggml-base.bin /path/to/jfk-canonical.wav
```

This tests full/dynamic-window recognition with Flash Attention off/on, short-window
sample-count boundaries and sentence endings, audio beyond 30 seconds, Auto's
separate full window, native cancellation, stale handles, exact silence, and
malformed WAV rejection. It does not execute
Android, evaluate conversational speech, or establish phone latency. JDK 21,
CMake, a C++ compiler, and build-network access are needed for this optional
host check. No model download is required by routine PR checks; manifest
schema/pins are checked separately.

## Output and privacy

Build output defaults to a checkout-specific directory under the system
temporary directory. Set `ALTIRO_BUILD_ROOT` to an external directory to choose
its location; each module has its own subdirectory. Set `GRADLE_USER_HOME` for
an isolated cache. `./gradlew clean` removes configured build output. Keep
retained APKs, recordings, reports, and screenshots outside Git repositories;
clean disposable task tooling when finished.

The app has no Internet permission. The browser handles explicit model download;
file-picker import verifies size/SHA-256 before atomic installation. Inference
rechecks the model, takes ownership of the completed WAV after microphone
release, and deletes audio after native work finishes, including cancellation.
Capture errors discard audio. Result text is process-memory only; no history is
persisted. Models are durable private files excluded from backup/transfer.

**Recognition diagnostics** displays the latest session's monotonic timings
without text/audio/editor data. It updates during native work, survives result
discard and expires ten minutes after completion. Explicit Copy/Share includes
app/OS versions, permission state and runtime/decode settings. One bounded
content-free checkpoint is stored privately outside backup for restart recovery,
replaced by the next run and deleted by Clear diagnostics. No raw native logs
or transcripts are persisted/exported. See [phone diagnostics](testing/recognition-diagnostics.md)
and [GPU testing](testing/gpu-experiment.md).

## Release boundary

The preview is a debug APK. Release signing needs separately supplied secure
material and dependency/native packaging review. Maintain signing identity
continuity. Pushes, signed releases, store uploads, and publication remain
separate operator actions.

## Prepare and compare Small models

The [catalog](../inference-whisper/src/main/assets/whisper-models.json) lists
stock Small Q8_0/FP16, existing Base, and experimental Chilean ES-CL-2 Q8_0/FP16.
All have separate private slots, exact sizes and hashes. The app picker selects
the next ordinary dictation model; **Record a comparison** runs both stock Small
models on identical audio, optionally adding either Chilean variant. See the
[phone procedure](testing/model-comparison.md).

Prepare stock files without conversion dependencies, using Python 3.12+:

```sh
python3 scripts/prepare-whisper-models.py --output /path/outside/checkout/models
```

To regenerate stock Q8 from FP16 and convert both Chilean variants, also install
`uv`, CMake, and a C++ compiler. Conversion dependencies run in a temporary,
isolated cache; no GPU or model training is involved:

```sh
./scripts/quiet-run.sh "prepare models" python3 scripts/prepare-whisper-models.py --output /path/outside/checkout/models --regenerate-q8 --chilean
python3 scripts/check-model-manifest.py /path/outside/checkout/models/ggml-small.bin /path/outside/checkout/models/ggml-small-q8_0.bin /path/outside/checkout/models/ggml-small-es-cl-2-f16.bin /path/outside/checkout/models/ggml-small-es-cl-2-q8_0.bin
```

Omit `--chilean` for stock-only preparation; omit `--regenerate-q8` to download
the published stock Q8 file. All sources and revisions are explicit constants
in the script. A profile change needs matching catalog/source pins, measured
output size/hash, conversion audit, and a new phone comparison; do not edit the
app allowlist to accept arbitrary downloaded weights.

Chilean preparation uses the pinned upstream `convert-h5-to-ggml.py`, safetensors
input, OpenAI mel filters, Torch 2.9.1, Transformers 4.48.3, and NumPy 2.2.6.
Every converted FP16 tensor is audited against the source using safetensors 0.5.3.
Stock quantization must reproduce the published Q8 hash. When preparing the Chilean variants, every compared profile
must load and recognize the public upstream JFK sample; converted output must
match the app catalog before replacing an existing file. Keep `preparation.json`,
source model card, and license notices with the generated files. No model weights
are bundled in Git or the APK. Source download/preparation makes explicit network
requests; installed app inference has no Internet permission.

For a production-JNI host compatibility smoke of all four prepared Small files,
use JDK 21 and a canonical mono PCM16/16 kHz public sample with a 44-byte WAV
header (strip optional source metadata chunks before supplying it):

```sh
./scripts/quiet-run.sh "Small JNI compatibility" ./scripts/check-whisper-models.sh /path/outside/checkout/models /path/to/jfk-canonical.wav
```

These are compatibility checks, not Chilean speech accuracy or Pixel latency
benchmarks. Real phone validation and release provenance qualification remain.
