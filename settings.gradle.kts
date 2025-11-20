pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.library") version "8.5.2"
    }
}

rootProject.name = "dianyaapi-jni-gradle"

include(":java")
project(":java").projectDir = file("java")

include(":android")
project(":android").projectDir = file("android")

