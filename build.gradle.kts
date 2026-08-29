plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("com.google.protobuf") version "0.10.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
}

tasks.register("clean", Delete::class) {
    description = "clean build files"
    delete(rootProject.layout.buildDirectory)
}
