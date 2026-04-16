plugins {
    id("com.android.application")
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
    maven("${rootProject.projectDir}/local-repo")
    maven("https://jitpack.io")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
    implementation("top.canyie.pine:core:0.3.0")
    implementation("io.github.hexhacking:xdl:2.3.0")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
}

android {
    namespace = "dev.allofus.fusioncore"
    compileSdk = 36

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        applicationId = "dev.allofus.fusioncore"
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters.add("arm64-v8a")
            // abiFilters.add("armeabi-v7a")
        }
        proguardFile("proguard-unity.txt")
    }

    externalNativeBuild {
        cmake {
            path = File("./src/main/jni/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("*/armeabi-v7a/*.so", "*/arm64-v8a/*.so")
        }
    }

    lint {
        abortOnError = false
    }
}

