plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

tasks.register("clean", Delete::class) {
    description = "clean build files"
    delete(rootProject.layout.buildDirectory)
}
