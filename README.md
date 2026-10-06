# Altiro

Free, open-source Android dictation that keeps your keyboard.

The intended workflow is simple: tap a floating microphone, speak, stop, and
insert the result into the active text field while continuing to use Gboard or
another keyboard. On-device transcription is the default. No account,
subscription, trial expiry, or business word quota.

**Status:** offline recognition preview. Import the supported multilingual
Whisper Small Q8 or FP16 model, then record and transcribe on the phone without network
access. The floating control, conservative insertion, and editor fixture remain.
Initial recording/cancellation/insertion are user-reported on a phone; full
Gate A and B remain unverified. See the
[bounded continuation decision](docs/decisions/0001-offline-preview.md).

## Start here

- [Product North Star](docs/product-north-star.md): the purpose and boundaries.
- [Android dictation specification](docs/plan/android-dictation/spec.md): the
  supplied implementation guidance, including exact APIs and milestone gates.
- [Implementation defaults](docs/architecture/implementation-defaults.md): how
  to approach the native Android implementation.
- [Current work](docs/plan/current-work.md): what exists and what comes next.
- [Build order](docs/plan/vertical-slice-build-order.md): ordered increments.
- [Contributing](CONTRIBUTING.md): setup and repository workflow.

## Product direction

- Android 13/API 33 minimum; native Kotlin and Compose.
- A small accessibility overlay; preserve the selected keyboard.
- Local multilingual recognition through a pinned `whisper.cpp` integration.
- Evaluate English, French, and Spanish, including Chilean conversational speech.
- Explicit Insert and Copy when the original editor is no longer eligible.
- Optional, independently configured remote recognition and cleanup later.

Physical-device gates cover microphone startup, Gboard composition, real
editors, recognition quality, and resource behavior. Keep the visible
recording-screen fallback while the remaining evidence is collected.

## Try offline recognition

Install a debug APK, open Altiro, select **Small · Q8** (264 MB) or **Small ·
FP16** (488 MB), and choose **Download selected model**. Use the corresponding
Import button to copy it into private storage and verify its size/SHA-256.
Both remain installed, so switching needs no new download. Existing Base
installs remain available. Two experimental Chilean Small variants are also
supported; their converted files come from the installation page or the
[preparation command](docs/development.md).

**Record a comparison** processes one recording with both stock Small models,
optionally adding either Chilean variant, and shows each transcript and elapsed
time with a separate Copy button. See the
[comparison procedure](docs/testing/model-comparison.md). No account or network permission
is required by Altiro. The browser performs the explicit download.

Enable Altiro's accessibility service and microphone permission, keep your
keyboard selected, and tap the floating mic in your text field. **Record without
leaving your app** is on by default. Stop releases the microphone before
recognition. An unchanged eligible field allows one automatic insertion attempt;
otherwise focus a destination and tap Insert, or Copy. If recording is blocked,
use **Open recording screen** in Altiro. Turn the setting off to always use that
separate screen, then return to your editor for explicit Insert.
Use ES for Spanish-only speech. Test airplane mode after installing the model.
See [Gate B](docs/testing/gate-b.md) for the quality/lifecycle procedure.

Open **Recognition diagnostics** to see where each run spends its time:
verification, cold model loading, inference and cleanup have separate rows.
Copy/Share exports a content-free timing report, including failed/cancelled runs.
The trace stays in memory for ten minutes after completion and is replaced by
the next recording. See [phone diagnostics](docs/testing/recognition-diagnostics.md).

The in-app **Recognition processor** setting adds experimental **Vulkan GPU**;
CPU remains the default. **Compare CPU and GPU** uses the selected model twice
on one recording and never inserts automatically. GPU initialization/failure
and compute counters appear in diagnostics. A single content-free checkpoint
survives restart; **Clear diagnostics** removes it. Visible recording and
diagnostics screens stay awake during work; manual lock still cancels. See
[GPU testing](docs/testing/gpu-experiment.md). Phone speed/compatibility remain
unverified, and a failed GPU run is never silently retried on CPU.

## Development checks

Use JDK 21, the pinned wrapper, Android SDK Platform 37.0, Build Tools 36.0.0,
NDK 30.0.16248370, and CMake 4.1.2:

```sh
./scripts/quiet-run.sh "verification" ./scripts/verify.sh
./scripts/quiet-run.sh "core checks" ./scripts/verify.sh --core-only
./scripts/quiet-run.sh "documentation" ./scripts/verify.sh --docs-only
```

Full verification automatically formats Kotlin before compiling both debug
apps and test APKs, running core tests and Android lint, and checking documentation. Device tests need a
connected phone or emulator. See [development](docs/development.md) and the
[Gate A procedure](docs/testing/gate-a.md).

## License

New repository content is licensed under [Apache-2.0](LICENSE). Third-party
runtime and model licenses remain separate; see [NOTICE](NOTICE).
