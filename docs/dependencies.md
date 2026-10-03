# Dependency and license boundary

Direct versions are pinned in [the catalog](../gradle/libs.versions.toml) and
the Gradle wrapper. Compose versions come from the pinned BOM. No copied
proprietary application code, remote SDK, analytics, or advertising SDK is
included. The pinned Whisper/ggml CPU runtime is bundled; model weights are
separately imported.

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
and x86_64; Altiro additionally includes its JNI library with statically linked
Whisper/ggml and NDK C++ support. The native runtime source archive is pinned
to commit `927cfce34f31707e17f2bff35c349632fb9e2c3a` and SHA-256
`41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde`.
Its MIT license, additional ggml CPU attribution, OpenAI weights MIT license,
and the NDK LLVM/runtime license notices are included in APK assets.
See [the model manifest](../inference-whisper/src/main/assets/whisper-model.json)
for exact model source/revision/size/hash; the artifact was downloaded and its
actual hash verified. No fine-tuned Chilean model is bundled or imported by
this build.

Verification checks actual ELF segments and uncompressed APK
entries for 16 KiB alignment; this does not replace a page-size device run.
