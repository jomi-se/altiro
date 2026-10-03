# Device validation

No Android device or emulator has been tested for Altiro yet.

The [specification](../plan/android-dictation/spec.md), sections 4 and 16, is the
acceptance authority. Use the separate editor fixture app, the reference
physical Pixel 7, Gboard, a second keyboard smoke test, and the specified host
apps. Record observed failures as well as successes.

## Evidence contract

For each experiment record app revision, build type, compile/target SDK, OS/API,
keyboard/app versions, permission state, recording route, expected behavior,
observed behavior, and a redacted exception category where relevant. Keep device
serials, personal app data, recordings, screenshots, request identifiers, and
raw logs outside Git. Promote only sanitized compatibility conclusions.

Distinguish `unverified`, `passed`, `failed`, and `unsupported`. A passing build
or emulator test is not physical-phone evidence. Do not mark Gate A or B passed
until their specified physical experiments are complete.

## Initial gates

- Gate A: fake recognition, real cursor/selection insertion, non-focusable
  overlay, composition and stale-target guards, microphone startup/fallback,
  cancellation, lock, and service disable.
- Gate B: local transcription in airplane mode, 10/30/120-second samples in
  English/French/Spanish, native cancellation and resource cleanup.
- Gate C: onboarding, model verification/import, visible recovery, privacy,
  first-release checklist, and native 16 KiB page-size checks.

## Speech quality

Evaluate recognition separately from cleanup. Track WER/CER and manual
preservation of names, numbers, units, negation, language switches, and intent.
Measure cold/warm load, decode time, stop-to-text latency, peak memory, and
repeated-session thermal behavior. Do not commit private personal samples or
invent performance numbers before measuring them.
