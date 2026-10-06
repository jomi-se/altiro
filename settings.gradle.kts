pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Altiro"

include(":core", ":network")

if (!providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app", ":inference-whisper", ":editor-fixture")
}
