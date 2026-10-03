# Current work

Updated: 2026-10-03.

## Android integration spike

Source is implemented for the native Android scaffold, separate editor fixture,
accessibility input-method tracking, metadata-only destination authority,
non-focusable movable control, explicit Insert/Copy/Discard, and microphone
foreground-service lifecycle. Recognition returns the fixed phrase
`Dictation test: café, mañana, Kubernetes.` It is not speech recognition.

Application IDs are `org.altiro.app` and `org.altiro.fixture`; API 33 minimum,
API 37 compile/target. The toolchain is pinned in the wrapper/version catalog.
Build/test output is external to the repository. See [development](../development.md).

The recording Activity is the default path. Its focus change invalidates the
original destination and requires explicit Insert. A labeled debug-only probe
can attempt direct microphone startup from the overlay; it remains unverified.
Unknown composition, missing identity, passwords, blocked apps, or stale targets
prevent insertion. A dispatched attempt is consumed and never retried.

## Validation boundary

The scaffold, Kotlin tests, lint, APK packaging, and compiled instrumentation
are local verification targets. Read-only CI includes JVM/build checks and an
API 33 emulator job; it has not been run remotely in this checkout. Physical
phone testing and local emulator execution have not been performed.

[Gate A](../testing/gate-a.md) is **unverified**. Follow that procedure on the
reference phone before integrating Whisper. Check Gboard composition and field
identity, cursor/selection replacement, overlay behavior, the direct/fallback
recording route, lock/cancel, and service disable. Report editor incompatibility
honestly and preserve Copy.

## Local verification

The Kotlin suite has nine passing behavioral tests. Both app and fixture debug
APKs and instrumentation APKs compile; Android lint has no errors. Bundled
AndroidX graphics libraries are checked for ELF and APK 16 KiB alignment.
Instrumentation has been compiled but not executed on this host, and physical
Gate A is unverified. The APK remains a fixed-phrase integration build.

## Next work

Complete device evidence for [editor authority](android-dictation/issues/02-editor-authority.md)
and the [overlay/microphone spike](android-dictation/issues/03-overlay-microphone.md).
Session serialization and bounded temporary PCM/WAV capture from ticket 04 are
implemented as part of this spike but require lifecycle device evidence.

The inference and network modules are reserved. No model/runtime is bundled,
no Internet permission exists, and no signed release or provider integration
has been made. Gate B follows Gate A; later work remains ordered by the
[canonical specification](android-dictation/spec.md) and [build plan](vertical-slice-build-order.md).

Pushes, publication, store submission, and credential changes remain operator-owned.
