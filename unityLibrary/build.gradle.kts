plugins {
    id("com.android.library")
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.20")
}

android {
    namespace = "com.unity3d.player"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        minSdk = 22
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
        consumerProguardFiles("proguard-unity.txt")
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

