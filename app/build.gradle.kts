import groovy.json.JsonOutput
import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.altiro.app"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "org.altiro.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "0.5.5-editor-focus"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":inference-whisper"))
    implementation(project(":network"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
}

// External runtime inputs only: project outputs, test libraries and build tools
// have different provenance. Do not serialize machine paths into this report.
val inventoryConfiguration =
    providers.gradleProperty("runtimeConfiguration").orElse("debugRuntimeClasspath")

tasks.register("exportRuntimeInventory") {
    notCompatibleWithConfigurationCache("Resolves a selected runtime graph for release review")
    doLast {
        val selected = inventoryConfiguration.get()
        require(selected in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath"))
        val runtime = configurations.getByName(selected)
        val components =
            runtime.incoming.resolutionResult.allComponents
                .mapNotNull { it.id as? ModuleComponentIdentifier }
                .map { mapOf("group" to it.group, "name" to it.module, "version" to it.version) }
                .sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}" }
        val artifacts =
            runtime.incoming
                .artifactView {
                    componentFilter { it is ModuleComponentIdentifier }
                }
                .artifacts
                .artifacts
                .map { artifact ->
                    val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                    val digest = MessageDigest.getInstance("SHA-256")
                    artifact.file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        var count = input.read(buffer)
                        while (count != -1) {
                            digest.update(buffer, 0, count)
                            count = input.read(buffer)
                        }
                    }
                    mapOf(
                        "group" to id.group,
                        "name" to id.module,
                        "version" to id.version,
                        "filename" to artifact.file.name,
                        "sha256" to digest.digest().joinToString("") { "%02x".format(it) },
                    )
                }
                .distinct()
                .sortedBy { "${it["group"]}:${it["name"]}:${it["version"]}:${it["filename"]}" }
        val output = layout.buildDirectory.file("reports/dependencies/$selected.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "schemaVersion" to 1,
                        "configuration" to selected,
                        "components" to components,
                        "artifacts" to artifacts,
                    )
                )
            ) + "\n"
        )
        println("Runtime inventory: ${components.size} components, ${artifacts.size} artifacts")
    }
}
