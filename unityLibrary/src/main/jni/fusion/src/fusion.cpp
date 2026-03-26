// Copyright (c) 2026 XtraCube
#include <jni.h>
#include <filesystem>
#include <logger.h>
#include <libmain.h>
#include <fusion_config.h>
#include <hooking/il2cpp.h>
#include <hooking/safehook.h>
#include <hooking/allocator.h>
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
        setenv("FUSION_GAME_DATA_DIR", config.appDataDirectory.c_str(), 1);
        setenv("FUSION_APP_DATA_DIR", config.appDataDirectory.c_str(), 1);
        setenv("FUSION_UNITY_VERSION", "2021.3.43f1", 1);

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

using scripting_method_invoke_fn = void* (*)(void* method, void* obj, void* args, void* exc, bool);
scripting_method_invoke_fn original_scripting_method_invoke = nullptr;

// this hook prevents crashes from unstripped libunity failing to resolve scripting methods
void* scripting_method_invoke_hook(void* method, void* obj, void* args, void* exc, bool something) {
    if (!method) {
        return nullptr;
    }

    return original_scripting_method_invoke(method, obj, args, exc, something);
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

    // fix unstripped libunity problems
    void *libunity_handle = xdl_open(libUnity.c_str(), RTLD_NOW | RTLD_GLOBAL);
    void *target = xdl_dsym(libunity_handle,
                     "_Z23scripting_method_invoke18ScriptingMethodPtr18ScriptingObjectPtrR18ScriptingArgumentsP21ScriptingExceptionPtrb",
                     nullptr
                     );
    if (!target)
    {
        log_format(LogLevel::ERROR, TAG,
            "Failed to find target function for scripting_method_invoke_hook: {}", dlerror());
    }
    else
    {
        if (
                DobbyHook(target,
                          reinterpret_cast<dobby_dummy_func_t>(scripting_method_invoke_hook),
                          reinterpret_cast<dobby_dummy_func_t *>(&original_scripting_method_invoke))
                == 0)
        {
            log(LogLevel::INFO, TAG, "Successfully hooked scripting_method_invoke");
        } else
        {
            log(LogLevel::ERROR, TAG, "Failed to hook scripting_method_invoke");
        }
    }

    fs::path tempLibIl2Cpp = fs::path(config.appInternalDataDirectory) / "libil2cpp.so";

    allocate_setup_injected(libIl2Cpp.c_str(), tempLibIl2Cpp.c_str(), 1024 * 1024);

    // set our custom libmain override paths
    libmain_set_override_il2cpp_path(tempLibIl2Cpp.c_str());
    libmain_set_override_unity_path(libUnity.c_str());

    // initialize il2cpp
    if (!il2cpp_initialize(tempLibIl2Cpp.c_str()))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}",
                   tempLibIl2Cpp.c_str());
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
