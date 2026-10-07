# Dependency and license boundary

Direct versions are pinned in [the catalog](../gradle/libs.versions.toml) and
the Gradle wrapper. Compose versions come from the pinned BOM. No copied
proprietary application code, remote SDK, analytics, or advertising SDK is
included. The pinned Whisper/ggml CPU runtime is bundled; model weights are
separately imported.

The experimental Vulkan backend also uses pinned Khronos Vulkan-Headers/Hpp
1.4.321 and SPIRV-Headers Vulkan SDK 1.4.321.0, with archive hashes in CMake.
Required Apache-2.0/MIT notices are bundled in APK assets; see NOTICE. The host
shader compiler generates Whisper's shaders and is not an APK executable.
Android supplies the Vulkan loader/driver; this does not prove GPU compatibility.

The current app bundles Kotlin, coroutines, kotlinx.serialization, AndroidX/Compose, Guava's
ListenableFuture, JetBrains annotations and JSpecify. The complete external
runtime-input graph is recorded in the bundled
[inventory](../inference-whisper/src/main/assets/licenses/android-runtime-components.json):
96 resolved components, including metadata-only platform/forwarding components,
and 62 unique AAR/JAR inputs. Test libraries, Gradle/plugin tooling and local
project outputs are excluded. The component count is not a count of APK binaries.

Each component records its declared Apache-2.0 license, exact Maven metadata URL
and SHA-256. Guava's declaration is inherited from its pinned parent POM. Binary
inputs have exact hashes. All 43 embedded license records share one identical
Apache-2.0 text, retained without alteration in
[the runtime license asset](../inference-whisper/src/main/assets/licenses/Android-runtime-Apache-2.0.txt).
Some AAR copies are stripped during Android packaging; the explicit asset retains
that text independently. Metadata classification does not relicense upstream
work or establish model redistribution rights.

POM metadata is not the whole notice review. Kotlin's pinned source identifies
ThreeTen-derived standard-library time code under BSD-3-Clause. Its exact
copyright/conditions/disclaimer are retained in
[the BSD notice](../inference-whisper/src/main/assets/licenses/Kotlin-stdlib-ThreeTen-BSD-3-Clause.txt).
Pinned Kotlin and coroutines attribution files are retained as well; the upstream
Kotlin distribution notice labels the compiler, which remains build tooling.
The inventory records exact notice source commits and hashes. The AndroidX
graphics-path native helper source carries AOSP/Filament Apache-2.0 headers;
source review is not a reproducible-build match of its Maven binary.
Bundled upstream texts bypass checkout line-ending conversion and whitespace
lint so their original bytes are preserved.

Representative upstream Maven metadata:

| Component | Version | Declared license |
| --- | --- | --- |
| [Kotlin standard library](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.4.20/kotlin-stdlib-2.4.20.pom) | 2.4.20 | Apache-2.0 |
| [Coroutines JVM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.11.0/kotlinx-coroutines-core-jvm-1.11.0.pom) | 1.11.0 | Apache-2.0 |
| [Compose UI Android](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.1/ui-android-1.12.1.pom) | 1.12.1 | Apache-2.0 |

`checkRuntimeNotices` exports the actual Gradle graph through a component-filtered
artifact view, avoiding generated project artifacts, and checks it against the
reviewed inventory. It rejects new/removed components, changed artifact hashes
and missing/changed retained text, including the actual debug APK asset bytes.
Full verification includes this offline check.
For a separate release-classpath review:

```sh
./gradlew --no-daemon -PruntimeConfiguration=releaseRuntimeClasspath checkRuntimeNotices
```

Reports stay in the external build root's `app/reports/dependencies/` directory.
When dependencies change, re-export the graph, inspect exact Maven POMs including
inherited declarations, inspect AAR/JAR license/notice entries, retain new texts
and update the inventory. Never accept a changed graph by copying hashes without
review. Signed-release qualification still needs the actual release artifact,
native/model provenance and appropriate redistribution review separately.

The debug APKs include AndroidX graphics path native libraries for arm64-v8a
and x86_64; Altiro additionally includes its JNI library with statically linked
Whisper/ggml and NDK C++ support. The native runtime source archive is pinned
to commit `927cfce34f31707e17f2bff35c349632fb9e2c3a` and SHA-256
`41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde`.
Its MIT license, additional ggml CPU attribution, OpenAI weights MIT license,
and the NDK LLVM/runtime license notices are included in APK assets.
See [the model catalog](../inference-whisper/src/main/assets/whisper-models.json)
for exact model source/revision/size/hash. Stock Small FP16/Q8 and experimental
Chilean ES-CL-2 FP16/Q8 artifacts have verified actual hashes. No weights are
bundled in the APK. The Chilean source declares Apache-2.0; the APK retains that
license alongside upstream OpenAI MIT notices. Pinned conversion input hashes
are in [the conversion metadata](../inference-whisper/src/main/assets/chilean-model-preparation.json).
Source data provenance and release redistribution qualification remain separate.

Verification checks actual ELF segments and uncompressed APK
entries for 16 KiB alignment; this does not replace a page-size device run.
