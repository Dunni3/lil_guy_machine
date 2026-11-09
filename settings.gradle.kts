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
rootProject.name = "lil-guy-machine"

include(":sim", ":decision", ":runlog", ":runlog-jdbc", ":agent", ":app", ":desktop", ":android", ":cli")
