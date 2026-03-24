plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("${rootProject.projectDir}/local-repo")
    maven("https://jitpack.io")
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
