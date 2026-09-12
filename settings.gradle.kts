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

rootProject.name = "AquaScope"
include(":app")
include(":smriti-app")
include(":core-database")
include(":core-hardware")
include(":core-telemetry")
include(":feature-capture")
include(":feature-gaming")
include(":feature-guardian")
include(":feature-memory")
include(":smriti-aqua")
include(":smriti-core")
