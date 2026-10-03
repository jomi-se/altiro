# 01 — Native Android scaffold and fixture

Status: ready-for-agent
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

No execution evidence recorded yet. Repository foundation is complete;
the runnable Android scaffold is this ticket's next deliverable.
