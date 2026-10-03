plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

val outputRoot =
    providers.environmentVariable("ALTIRO_BUILD_ROOT").orElse(
        "${System.getProperty("java.io.tmpdir")}/altiro-${Integer.toUnsignedString(rootDir.absolutePath.hashCode())}",
    )
allprojects {
    layout.buildDirectory.set(file("${outputRoot.get()}/${if (path == ":") "root" else path.trimStart(':').replace(':', '/')}"))
}

spotless {
    kotlin {
        target("**/src/**/*.kt")
        ktlint("1.7.1")
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts")
        ktlint("1.7.1")
    }
}

if (findProject(":app") != null) {
    tasks.register<Exec>("checkNativePages") {
        dependsOn(":app:assembleDebug", ":editor-fixture:assembleDebug")
        commandLine(
            "python3",
            "scripts/check-native-pages.py",
            "${outputRoot.get()}/app/outputs/apk/debug/app-debug.apk",
            "${outputRoot.get()}/editor-fixture/outputs/apk/debug/editor-fixture-debug.apk",
        )
    }
}
