# Development

## Pinned toolchain

| Tool | Version |
| --- | --- |
| JDK and bytecode | 21 |
| Gradle | 9.6.0; wrapper SHA-256 is pinned |
| Android Gradle Plugin | 9.4.1 |
| Kotlin and Compose compiler | 2.4.20 |
| Compose BOM | 2026.09.00 |
| AndroidX Activity | 1.13.0 |
| Coroutines | 1.11.0 |
| Compile/target SDK | API 37, Platform 37.0 |
| Minimum SDK | API 33 |
| Build Tools | 36.0.0 |
| Spotless / ktlint | 8.0.0 / 1.7.1 |

Pins live in [the version catalog](../gradle/libs.versions.toml). AGP 9 supplies
built-in Kotlin for Android; root JVM/Compose plugins resolve the matching
pinned Kotlin version. No legacy Kotlin Android plugin is applied. Native
NDK/CMake/runtime pins await Gate B.

Compatibility sources: [AGP release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes)
and official Google/Maven artifact metadata. Executing this build is the check
of this particular combination.

## Setup

Install JDK 21 and an Android SDK on a supported host. Install
`platforms;android-37.0` and `build-tools;36.0.0`; platform-tools is needed for
device work. Set `ANDROID_HOME` or an ignored `local.properties` SDK path.
Use the committed Gradle wrapper.

Check host architecture first. Official Linux Android resource tools and
emulators may require x86-64. Host emulation and machine setup are operator
concerns, not portable product instructions. Source/JVM work does not require
an emulator or physical device.

## Commands

```sh
./scripts/quiet-run.sh "verification" ./scripts/verify.sh
./scripts/quiet-run.sh "core" ./scripts/verify.sh --core-only
./scripts/quiet-run.sh "docs" ./scripts/verify.sh --docs-only
./gradlew spotlessApply
./gradlew :app:installDebug :editor-fixture:installDebug
./gradlew :app:connectedDebugAndroidTest :editor-fixture:connectedDebugAndroidTest
```

Full verification checks formatting, core tests, debug builds, Android lint,
instrumentation APK compilation, and 16 KiB alignment of bundled native
libraries. Connected tests execute separately.
They supplement the [physical Gate A procedure](testing/gate-a.md).

Application IDs are `org.altiro.app` and `org.altiro.fixture`. Core is pure
Kotlin. Inference and network modules are reserved until their build gates.

## Output and privacy

Build output defaults to a checkout-specific directory under the system
temporary directory. Set `ALTIRO_BUILD_ROOT` to an external directory to choose
its location; each module has its own subdirectory. Set `GRADLE_USER_HOME` for
an isolated cache. `./gradlew clean` removes configured build output. Keep
retained APKs, recordings, reports, and screenshots outside Git repositories;
clean disposable task tooling when finished.

The spike has no Internet permission. Its recording worker deletes temporary
audio after capture resources are released, including Stop, Cancel, and errors.
Result text is process-memory only. No history is persisted.

## Release boundary

The spike is a debug APK. Release signing needs separately supplied secure
material and dependency/native packaging review. Maintain signing identity
continuity. Pushes, signed releases, store uploads, and publication remain
separate operator actions.
