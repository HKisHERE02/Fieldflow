pluginManagement { repositories { mavenCentral(); gradlePluginPortal(); google() } }
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "fieldflow-backend"
include(":shared")
project(":shared").projectDir = file("../shared")
