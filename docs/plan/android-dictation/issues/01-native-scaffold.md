# 01 — Native Android scaffold and fixture

Status: in-progress
Blocked by: none

## Authority

[Specification](../spec.md), sections 5 and 17; [build order](../../vertical-slice-build-order.md).

## Deliverable

Create `app`, `core`, `inference-whisper`, `network`, and the separate
`editor-fixture` app. Pin compatible current stable Gradle/AGP/Kotlin/Compose,
JDK/SDK versions and wrapper checksum. Record the chosen application ID.
Provide a minimal launchable app and stock EditText/Compose fixture fields.
Add formatting, lint, tests, and debug APK commands with read-only CI.

## Acceptance evidence

- Toolchain pins have checked upstream compatibility sources.
- App and fixture compile on a supported build host; actual results are recorded.
- Domain module is pure Kotlin and independently testable.
- Real separate-process editors exist; no insertion claim yet.
- Development instructions and CI match runnable commands.
- No models, native runtime integration, credentials, or local SDK paths in Git.

## Comments

Native modules, pinned wrapper/toolchain, app and editor fixture are implemented.
Local build checks are recorded in current work; CI is configured but has not
been executed remotely.
