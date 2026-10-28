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
        // No jitpack / arbitrary repos — keep the dependency supply chain tight
        // (only vetted google() + mavenCentral() artifacts, no unverified deps).
    }
}

rootProject.name = "fireplace"
include(":app", ":engine")
