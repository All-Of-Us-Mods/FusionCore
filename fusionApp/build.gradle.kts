plugins {
    id("com.android.application")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    kotlin("plugin.serialization") version "2.4.10"
}

// we have a custom pine build that fixes 16KB library problem.
val pineAar = file("../libs/canyie-pine.aar")
val roomVersion = "3.0.2"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.annotation:annotation:1.10.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.11.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.3.0")
    implementation("androidx.navigation3:navigation3-ui:1.1.7")
    implementation("androidx.navigation3:navigation3-runtime:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core")
    implementation("com.google.protobuf:protobuf-javalite:4.36.0")
    implementation("com.google.dagger:hilt-android:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    implementation("com.google.accompanist:accompanist-drawablepainter:0.37.3")

    implementation("androidx.room3:room3-runtime:$roomVersion")
    implementation(files(pineAar))

    ksp("androidx.room3:room3-compiler:$roomVersion")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

android {
    namespace = "dev.allofus.fusioncore"
    compileSdk = 37

    buildFeatures {
        prefab = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        applicationId = "dev.allofus.fusioncore"
        versionCode = 1
        versionName = "0.0.1"
        ndk {
            abiFilters.add("arm64-v8a")
            // abiFilters.add("armeabi-v7a")
        }
    }

    externalNativeBuild {
        cmake {
            path = File("./src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-unity.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
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


protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}