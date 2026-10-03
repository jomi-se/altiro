# Current work

Updated: 2026-10-03.

## Repository foundation

The repository is prepared as a native Android open-source project, with
Apache-2.0 licensing, shared agent guidance, a Claude adapter, product and
architecture authorities, local Markdown tracking, and repository checks.
The read-only CI workflow checks this documentation foundation; Android
compile/lint/test/fixture jobs belong to the scaffold ticket.

The supplied [Android dictation specification](android-dictation/spec.md) is
the canonical implementation handoff. It is not evidence of implemented
features. No Android code, Gradle build, inference runtime, model, usable APK,
emulator results, or physical-device results exist yet.

## Next: runnable native scaffold

Start [ticket 01](android-dictation/issues/01-native-scaffold.md). Establish a
pinned compatible Android build and the separate fixture app before starting
accessibility insertion. Follow the [build order](vertical-slice-build-order.md).

The first decisive gate is Gate A from the spec: a fake recognizer, real editor
tracking and insertion, non-focusable overlay, and microphone lifecycle on the
reference physical phone. Do not integrate Whisper or optimize recognition
before establishing those Android assumptions.

## Open implementation choices

- Verify and pin current stable toolchain versions at scaffold implementation.
- Choose a durable application ID before distributable installs.
- Confirm execution support on the chosen build host and access to physical
  hardware; emulator and build results cannot stand in for hardware evidence.
- Determine direct versus visible-Activity microphone startup experimentally.

There is no remote repository configured by this setup. Pushes, release
publication, store submission, and credential changes remain operator-owned.
