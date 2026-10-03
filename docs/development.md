# Development

## Current repository foundation

The repository currently contains guidance, the supplied Android specification,
local work tracking, and repository checks. No Gradle scaffold, Android SDK,
native runtime, model, emulator, or application is provided by this setup.

```sh
./scripts/quiet-run.sh "repository checks" ./scripts/verify.sh
```

The script needs Python 3 and a POSIX shell. It validates required documentation
and local links, without internet access or credentials.

## Android scaffold milestone

[Ticket 01](plan/android-dictation/issues/01-native-scaffold.md) owns the runnable
build and fixture app. Install a compatible JDK and Android SDK on a supported
build host; pin versions and document exact wrapper commands in this file when
they exist. Keep SDK paths in ignored `local.properties` or environment
configuration. Never commit a developer's SDK path.

Check host architecture before installing tools. Availability of Java alone
does not establish that Android build tools, ADB, native toolchains, or emulator
images execute on that host. Source editing and pure Kotlin checks can happen
independently of Android device testing. Do not claim an ARM host has passed
an x86 toolchain or emulator gate without executing it.

Use a physical Android device for the required microphone/editor experiments.
ADB authorization and permission prompts remain visible user-controlled actions.
An emulator supplements the device gate. No Node/browser toolchain is needed.

## Files and artifacts

Keep Gradle/build/native caches, APKs, models, private audio, signing material,
screenshots, and test logs out of Git. Use an external artifact directory for
retained evidence and disposable temporary directories for scratch work. Clean
each task's temporary output when it is no longer needed.

## Release boundary

Debug builds can support development once the scaffold exists. Signed release
APKs require separately supplied signing material and an audited dependency/model
inventory. Publishing a release or submitting to a store is a separate maintainer
action. Preserve signing identity continuity; never commit a signing key.
