import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

val outputRoot =
    providers
        .environmentVariable("ALTIRO_BUILD_ROOT")
        .orElse(
            "${System.getProperty("java.io.tmpdir")}/altiro-${Integer.toUnsignedString(rootDir.absolutePath.hashCode())}"
        )

allprojects {
    layout.buildDirectory.set(
        file(
            "${outputRoot.get()}/${if (path == ":") "root" else path.trimStart(':').replace(':', '/')}"
        )
    )
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktfmt("0.63").kotlinlangStyle()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktfmt("0.63").kotlinlangStyle()
    }
}

if (findProject(":app") != null) {
    // AGP can use different output directories for IDE/ABI-targeted builds.
    // Follow its artifact provider instead of guessing or finding an older APK.
    val debugApks = mutableMapOf<String, Provider<Directory>>()
    subprojects {
        val module = this
        pluginManager.withPlugin("com.android.application") {
            val components = module.extensions.getByType<ApplicationAndroidComponentsExtension>()
            components.onVariants(components.selector().withBuildType("debug")) { variant ->
                debugApks[module.name] = variant.artifacts.get(SingleArtifact.APK)
            }
        }
    }
    fun debugApk(module: String) =
        debugApks.getValue(module).get().file("$module-debug.apk").asFile.absolutePath

    tasks.register<Exec>("checkRuntimeNotices") {
        dependsOn(":app:exportRuntimeInventory", ":app:assembleDebug")
        val selected =
            providers.gradleProperty("runtimeConfiguration").orElse("debugRuntimeClasspath")
        executable("python3")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "scripts/check-runtime-notices.py",
                    "${outputRoot.get()}/app/reports/dependencies/${selected.get()}.json",
                    debugApk("app"),
                )
            }
        )
    }
    tasks.register<Exec>("checkNativePages") {
        dependsOn(":app:assembleDebug", ":editor-fixture:assembleDebug")
        executable("python3")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf("scripts/check-native-pages.py", debugApk("app"), debugApk("editor-fixture"))
            }
        )
    }
}
