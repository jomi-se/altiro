# Altiro

Free, open-source Android dictation that keeps your keyboard.

The intended workflow is simple: tap a floating microphone, speak, stop, and
insert the result into the active text field while continuing to use Gboard or
another keyboard. On-device transcription is the default. No account,
subscription, trial expiry, or business word quota.

**Status:** Android integration spike. The application records a real microphone
test and returns the fixed phrase `Dictation test: café, mañana, Kubernetes.`
It includes a floating control, conservative insertion, and a separate editor
fixture. It does **not** yet recognize speech. Physical-device Gate A remains
unverified; local Whisper integration follows that gate.

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
- Evaluate English, French, and Spanish, including accented technical speech.
- Explicit Insert and Copy when the original editor is no longer eligible.
- Optional, independently configured remote recognition and cleanup later.

Android integration must be proven before model integration. A physical-device
gate covers microphone startup, Gboard composition, and insertion into real
editors. See the specification for the visible recording-screen fallback and
the limits of asynchronous insertion.

## Development checks

Use JDK 21, the pinned wrapper, Android SDK Platform 37.0 and Build Tools 36.0.0:

```sh
./scripts/quiet-run.sh "verification" ./scripts/verify.sh
./scripts/quiet-run.sh "core checks" ./scripts/verify.sh --core-only
./scripts/quiet-run.sh "documentation" ./scripts/verify.sh --docs-only
```

Full verification compiles both debug apps and test APKs, runs core tests and
Android lint, and checks formatting and documentation. Device tests need a
connected phone or emulator. See [development](docs/development.md) and the
[Gate A procedure](docs/testing/gate-a.md).

## License

New repository content is licensed under [Apache-2.0](LICENSE). Third-party
runtime and model licenses remain separate; see [NOTICE](NOTICE).
