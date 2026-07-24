pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        kotlin("jvm") version "2.2.20"
        kotlin("plugin.serialization") version "2.2.20"
        id("com.vanniktech.maven.publish") version "0.30.0"
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "sikkerkey-sdk"
