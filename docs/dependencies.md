# Dependency and license boundary

Direct versions are pinned in [the catalog](../gradle/libs.versions.toml) and
the Gradle wrapper. Compose versions come from the pinned BOM. No model,
Whisper runtime, copied proprietary application code, remote SDK, analytics,
or advertising SDK is included in the integration spike.

The current app bundles Kotlin, coroutines, and AndroidX/Compose components.
The following upstream Maven metadata was inspected for the resolved build:

| Component | Version | Declared license |
| --- | --- | --- |
| [Kotlin standard library](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.pom) | 2.4.20 | Apache-2.0 |
| [Coroutines JVM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.pom) | 1.11.0 | Apache-2.0 |
| [Compose UI Android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.pom) | 1.12.1 | Apache-2.0 |

This is an initial dependency review, not the complete signed-release inventory.
Before distribution release, inventory every resolved runtime/native dependency,
retain required notices, and audit the exact inference runtime and model weights
separately. Test/build tooling is distinct from APK dependencies.

The debug APKs include AndroidX graphics path native libraries for arm64-v8a
and x86_64. Verification checks their actual ELF segments and uncompressed APK
entries for 16 KiB alignment; this does not replace a page-size device run.
