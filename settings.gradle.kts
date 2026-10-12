pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PocketPhotoBridge"
include(":sony", ":printer", ":bridge")
if (!providers.gradleProperty("headlessOnly").map { it.toBoolean() }.getOrElse(false)) include(":app")
