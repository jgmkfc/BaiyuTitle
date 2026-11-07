pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" // allow automatic download of JDKs 
}

rootProject.name = "BaiYuTitle"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include("api", "common", "paper")
