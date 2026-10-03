# Contributing

Start with [AGENTS.md](AGENTS.md), the [Product North Star](docs/product-north-star.md),
and the [Android specification](docs/plan/android-dictation/spec.md). Check
[current work](docs/plan/current-work.md) before choosing an implementation task.

## Development setup

See [development](docs/development.md) for the current setup boundary. This is
a native Android project; Node, a browser dev server, and desktop dictation
frameworks are not prerequisites.

Run the available checks from the repository root:

```sh
./scripts/quiet-run.sh "verification" ./scripts/verify.sh
```

Use `--core-only` without an Android SDK, or `--docs-only` for documentation.
Full verification needs JDK 21 and the pinned SDK and compiles Android/test APKs,
runs core tests/lint, and checks formatting. Connected tests and the physical
Gate A procedure are separate; do not call compiled tests executed tests.

## Changes

Keep changes focused on a runnable increment in the accepted build order.
Record architecture departures with their reason, evidence, and user-visible
effect. Add behavioral tests when application behavior appears. Confirmed
defects and feature tickets use the [Markdown tracker](docs/agents/issue-tracker.md).

Describe what changed, what was checked, and what remains unverified. Keep
emulator and physical-device results distinct. Private recordings and
machine-specific evidence do not belong in a pull request or Git history.

Use explicit staging paths, review the staged diff, and commit completed work.
The operator performs pushes and publication. Do not change credential setup
or use another credential path to bypass that boundary.

## License and dependencies

Contributions to new app code use Apache-2.0. Preserve upstream notices for
permissively licensed code. Check runtime, model, and application licenses
separately before copying or distributing anything. The APK now bundles the
pinned Whisper runtime; its supported model is a separate verified import.
Read [the dependency boundary](docs/dependencies.md) before adding profiles.
