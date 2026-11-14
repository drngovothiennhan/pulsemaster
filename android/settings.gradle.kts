pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.5.2"
        kotlin("android") version "2.0.21"
        kotlin("jvm") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PulseMasterAndroid"
include(":app")
include(":core")
project(":core").projectDir = file("../core")
