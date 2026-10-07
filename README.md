# Altiro

Free, open-source Android dictation that keeps your keyboard.

The intended workflow is simple: tap a floating microphone, speak, stop, and
insert the result into the active text field while continuing to use Gboard or
another keyboard. On-device transcription is the default. No account,
subscription, trial expiry, or business word quota.

**Status:** everyday-use preview. A focused native home screen, compact floating
mic/language overlay, model library and diagnostic console wrap the pinned local
Whisper runtime. Stock models support explicit in-app download and verified
file import; the two experimental Chilean conversions currently use import.
Phone smoke results exist, but full editor/resource gates and the redesigned
interface's physical-device acceptance remain open.

## Start here

- [Product North Star](docs/product-north-star.md): the purpose and boundaries.
- [Productization brief](docs/plan/productization.md): the daily-use direction
  and the reasons behind setup, model, overlay and console choices.
- [Android dictation specification](docs/plan/android-dictation/spec.md): the
  supplied implementation guidance, including exact APIs and milestone gates.
- [Implementation defaults](docs/architecture/implementation-defaults.md): how
  to approach the native Android implementation.
- [Current work](docs/plan/current-work.md): what exists and what comes next.
- [Build order](docs/plan/vertical-slice-build-order.md): ordered increments.
- [Contributing](CONTRIBUTING.md): setup and repository workflow.
- [Security policy](SECURITY.md): private reporting and local secret checks.

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

## Install and use

Install a debug APK built from this repository. Open **Settings**, allow the
microphone and enable Altiro in Android Accessibility settings.
This adds the floating control without replacing your keyboard. Accessibility
is used for the selected editor, cursor/composition and one insertion attempt;
Altiro does not collect screen or clipboard contents.

On **Home**, tap **Download** to install the selected stock model once. **Models**
shows each model's size, installation state and source, with file import retained
for offline transfer. Small Q8 is 264 MB; Small FP16 is 488 MB; multilingual Base
is 148 MB. Downloads use pinned HTTPS sources and exact size/SHA-256 checks before
atomic installation. Downloading is explicit; recording and recognition never
make network requests. Test airplane mode after installation.

The experimental Chilean Small Q8/FP16 variants are visible in Models. Prepare
or obtain the exact converted file, select its profile and choose **Import file**.
The original training repository has no compatible downloadable binary, so the
app does not offer a misleading one-tap download. See
[model preparation](docs/development.md#prepare-and-compare-small-models).

Keep your keyboard and tap the floating mic in your text field. Tap its language
control to switch EN/ES before recording; Auto and French are available in the
app. Speak and tap the square Stop action. The microphone is released before
recognition; a separate Cancel discards the session. Drag a control to move the
bubble; while idle, drop it on the dismiss target to hide it in that app. Undo
is briefly available, and Settings can restore one or all hidden apps with
confirmation. TalkBack exposes a Hide action. The idle control is translucent;
its material softens at rest while text and glyphs stay opaque.

An unchanged eligible destination receives one automatic insertion attempt.
Changed field/cursor/composition, lock or a password field blocks insertion.
Recovery exposes Insert/Copy/Discard; uncertain delivery never triggers a retry
or whole-field replacement. After dispatch, another dictation can start directly.
If Android blocks overlay microphone startup, record from Home and return to
your editor for explicit insertion. Settings retains the separate-screen fallback.

The padded dynamic encoder window is enabled and capped at 30 seconds; longer
recordings retain the normal full-window path. Language stays fixed during
recording. Visible app and overlay work request screen-on; manual lock still
cancels. Overlay behavior and visual fit need physical-device confirmation.

**Console** provides live/final phase timings, runtime details, aggregate window
event counts, model-acquisition state and a saved content-free checkpoint.
Copy/Share exports diagnostics; Clear removes them. Settings contains processor,
Flash Attention and dynamic-window controls. **Compare a recording** in Console
offers same-audio model, processor and window comparisons; extra raw logs stay
secondary. Comparisons never insert automatically.
CPU remains the default; Vulkan compatibility and performance are device-specific.

If the bubble is missing, focus the failing field and open Settings to inspect
the last-editor reason. Console exports the fixed reason codes without editor
identifiers. Restoring hidden apps does not establish editor compatibility.

**Settings → Names & terms** offers optional local spelling hints: one per line,
up to 100 terms and 4 KiB. Save applies the list to the next recording; Clear
followed by Save turns hints off. The same saved list is used in every comparison
pass. Hints guide recognition rather than replacing transcript text, and do not
guarantee spelling. Console reports only the configured count, never the words.

See the [product interface checks](docs/testing/everyday-interface.md),
[rewrite checks](docs/testing/interface-rewrite.md),
[Gate A](docs/testing/gate-a.md), [Gate B](docs/testing/gate-b.md), and
[recognition diagnostics](docs/testing/recognition-diagnostics.md).

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
