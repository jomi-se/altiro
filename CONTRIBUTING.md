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
Full checks also compare actual runtime inputs against the bundled
[reviewed notice inventory](docs/dependencies.md); dependency changes need
metadata/notice review before updating its hashes.

For workflow changes, also install the checksum-pinned validator with
`./scripts/install-actionlint.sh` and run `./scripts/check-workflows.sh`.
It checks Actions syntax, expression contexts and types. A separate workflow
runs this check even when the Android workflow itself cannot start.

## Changes

Keep changes focused on a runnable increment in the accepted build order.
Record architecture departures with their reason, evidence, and user-visible
effect. Add behavioral tests when application behavior appears. Confirmed
defects and feature tickets use the [Markdown tracker](docs/agents/issue-tracker.md).

Describe what changed, what was checked, and what remains unverified. Keep
emulator and physical-device results distinct. Private recordings and
machine-specific evidence do not belong in a pull request or Git history.

Use explicit staging paths, review the staged diff, and commit completed work.
Install the checksum-pinned secret scanner with `./scripts/install-gitleaks.sh`.
Run `./scripts/scan-secrets.sh staged` before committing and
`./scripts/scan-secrets.sh history` before publication. Never commit model/audio
artifacts, signing keys or local design-tool installations. See
[the security policy](SECURITY.md).
The operator performs pushes and publication. Do not change credential setup
or use another credential path to bypass that boundary.

## License and dependencies

Contributions to new app code use Apache-2.0. Preserve upstream notices for
permissively licensed code. Check runtime, model, and application licenses
separately before copying or distributing anything. The APK now bundles the
pinned Whisper runtime; its supported model is a separate verified import.
Read [the dependency boundary](docs/dependencies.md) before adding profiles.
