// Copyright (c) 2026 XtraCube
#include <jni.h>
#include <filesystem>
#include <logger.h>
#include <libmain.h>
#include <fusion_config.h>
#include <hooking/il2cpp.h>
#include <hooking/safehook.h>

#define TAG "FusionCore"

namespace fs = std::filesystem;



extern "C" JNIEXPORT void JNICALL loadFusion(
        JNIEnv *env,
        jclass thisObject,
        jobject nativeConfig
)
{
    log(LogLevel::INFO, TAG, "Loading FusionCore...");

    FusionConfig config = parseFusionConfig(env, nativeConfig);

    fs::path gameLibsPath(config.gameLibraryDirectory);
    fs::path appLibsPath(config.appLibraryDirectory);

    fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";

    fs::path libUnity;
    if (config.useOriginalLibUnity)
    {
        libUnity = gameLibsPath / "libunity.so";
    } else
    {
        libUnity = appLibsPath / "libunity.so";
    }

    // set our custom libmain override paths
    set_override_il2cpp_path(libIl2Cpp.c_str());
    set_override_unity_path(libUnity.c_str());

    // initialize il2cpp
    if (!il2cpp_initialize(libIl2Cpp.c_str()))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}",
                   libIl2Cpp.c_str());
        return;
    }

    // initialize safehook
    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_get_library_base(), nullptr))
    {
        log(LogLevel::ERROR, TAG, "Failed to initialize SafeHook");
        return;
    }

    log_format(LogLevel::INFO, TAG, "Game library directory: {}",
               config.gameLibraryDirectory.c_str());

    log_format(LogLevel::INFO, TAG, "App library directory: {}",
               config.appLibraryDirectory.c_str());

    log(LogLevel::INFO, TAG, "FusionCore loaded successfully!");
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *globalEnv;
    if (vm->GetEnv(reinterpret_cast<void**>(&globalEnv), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR; // Failed to obtain JNIEnv
    }

    jclass clazz = globalEnv->FindClass("dev/allofus/fusioncore/ActivityBridge");
    if (!clazz) {
        return JNI_ERR; // Class not found
    }

    static const JNINativeMethod methods[] = {
            {"loadFusion", "(Ldev/allofus/fusioncore/FusionConfig;)V",
                    reinterpret_cast<void *>(loadFusion)}
    };

    jint ret = globalEnv->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(JNINativeMethod));
    if (ret != JNI_OK) {
        return ret; // Failed to register natives
    }

    return JNI_VERSION_1_6; // Successful initialization
}
