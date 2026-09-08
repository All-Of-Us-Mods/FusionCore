import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
    id("com.google.protobuf")
    id("kotlin-parcelize")
    alias(libs.plugins.hikage)
}

// we have a custom pine build that fixes 16KB library problem.
val pineAar = file("../libs/canyie-pine.aar")
dependencies {
    // local files
    implementation(files(pineAar))

    // core libraries
    implementation(libs.core)
    implementation(libs.annotation)
    implementation(libs.appcompat)
    implementation(libs.protobuf.javalite)
    implementation(libs.material)

    // hikage
    implementation(platform(libs.hikage.bom))
    implementation(libs.hikage.core)
    implementation(libs.hikage.runtime)
    implementation(libs.hikage.runtime.attribute)
    implementation(libs.hikage.extension)
    implementation(libs.hikage.widget.androidx)
    implementation(libs.hikage.widget.material)
}

android {
    namespace = "dev.allofus.fusioncore"
    compileSdk = 37

    buildFeatures {
        prefab = true
        buildConfig = true
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
            // this can mess up ResourceHooks
            //noinspection NotShrinkingResources
            isShrinkResources = false
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

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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