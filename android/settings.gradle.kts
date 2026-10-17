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

rootProject.name = "Laadmoment"

// core: the planner and the data sources, plain Kotlin, tested on the JVM.
// app: the Android app around it.
include(":core", ":app")
