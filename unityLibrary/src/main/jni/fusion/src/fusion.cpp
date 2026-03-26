// Copyright (c) 2026 XtraCube
#include <jni.h>
#include <filesystem>
#include <logger.h>
#include <libmain.h>
#include <fusion_config.h>
#include <hooking/il2cpp.h>
#include <hooking/safehook.h>
#include <hooking/allocator.h>
#include <hooking/libunity.h>
#include <dotnet.h>
#include <external/dobby.h>
#include <external/xdl.h>

#define TAG "FusionCore"

namespace fs = std::filesystem;

static FusionConfig config;

int il2cpp_init_hook(char *domain_name)
{
    log_format(LogLevel::INFO, TAG, "il2cpp_init called with domain: {}", domain_name);
    il2cpp_destroy_init_hook();

    // call the original il2cpp_init function
    int result = il2cpp_init(domain_name);

    if (config.initialized)
    {
        // setup environment variables
        setenv("BEPINEX_GAME_ASSEMBLY_PATH", libmain_get_override_il2cpp_path(), 1);
        setenv("FUSION_BEPINEX_PATH", config.bepInExDirectory.c_str(), 1);
        setenv("FUSION_GAME_BINARY", libmain_get_override_il2cpp_path(), 1);
        setenv("FUSION_GAME_DATA_DIR", config.unityDataDirectory.c_str(), 1);
        setenv("FUSION_APP_DATA_DIR", config.appDataDirectory.c_str(), 1);
        setenv("FUSION_UNITY_VERSION", config.unityVersion.c_str(), 1);

        fs::path bepInExCoreDirectory = fs::path(config.bepInExDirectory) / "core";

        DotNetConfig dotNetConfig;
        dotNetConfig.runtimeDir = config.dotnetDirectory;
        dotNetConfig.managedLibsDir = bepInExCoreDirectory.string();
        dotNetConfig.entryPointAssembly = "BepInEx.Unity.IL2CPP";
        dotNetConfig.entryPointType = "BepInEx.Unity.IL2CPP.FusionCoreEntrypoint";
        dotNetConfig.entryPointMethod = "Start";

        // set TMPDIR for MonoMod lib drops
        setenv("TMPDIR", config.appDataDirectory.c_str(), 1);

        // execute the managed assembly
        dotnet_execute_assembly(dotNetConfig);
    }
    else
    {
        log(LogLevel::WARN, TAG, "FusionConfig not initialized. Skipping modloader initialization.");
    }

    log_format(LogLevel::INFO, TAG, "il2cpp_init returned: {}", result);
    return result;
}

extern "C" JNIEXPORT void JNICALL loadFusion(
        JNIEnv *env,
        jclass thisObject,
        jobject nativeConfig
)
{
    log(LogLevel::INFO, TAG, "Loading FusionCore...");

    // Parse the configuration passed from Java
    config = fusion_parse_config(env, nativeConfig);
    fusion_print_config(config);

    // Construct paths to the game and app libraries
    fs::path gameLibsPath(config.gameLibraryDirectory);
    fs::path appInternalDataPath(config.appInternalDataDirectory);

    fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";
    fs::path libUnity;

    if (config.useOriginalLibUnity)
    {
        libUnity = gameLibsPath / "libunity.so";
    } else
    {
        libUnity = appInternalDataPath / "libunity.so";
    }

    // fix unstripped libunity problems
    std::string libUnityPath = libUnity.string();
    try_hook_libunity(libUnityPath, (gameLibsPath / "libunity.so").string());

    // construct path for our patched libil2cpp copy
    fs::path patchedLibIl2Cpp = fs::path(config.appInternalDataDirectory) / "libil2cpp.so";

    // inject a 1MB pool for our hooks to use for code generation and trampoline storage
    allocate_setup_injected(libIl2Cpp.c_str(), patchedLibIl2Cpp.c_str(), 1024 * 1024);

    // set our custom libmain override paths
    libmain_set_override_il2cpp_path(patchedLibIl2Cpp.c_str());
    libmain_set_override_unity_path(libUnityPath.c_str());

    // initialize il2cpp
    if (!il2cpp_initialize(patchedLibIl2Cpp.c_str()))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}",
                   patchedLibIl2Cpp.c_str());
        return;
    }

    // initialize safehook
    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_get_library_base(), allocate_injected))
    {
        log(LogLevel::ERROR, TAG, "Failed to initialize SafeHook");
        return;
    }

    // install il2cpp hooks
    log(LogLevel::INFO, TAG, "Installing il2cpp hooks...");
    il2cpp_install_init_hook(il2cpp_init_hook);
    log(LogLevel::INFO, TAG, "il2cpp hooks installed successfully!");

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
