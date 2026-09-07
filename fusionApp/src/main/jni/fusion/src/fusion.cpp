// Copyright (c) 2026 XtraCube
#include <unistd.h>
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
#include <utilities/java.h>

#define TAG "FusionCore"

namespace fs = std::filesystem;

static FusionConfig runtimeConfig;

static bool execute_fusion_config(const FusionConfig &config)
{
    fusion_print_config(config);

    fs::path gameLibsPath(config.gameLibraryDirectory);
    fs::path codeCache(config.codeCacheDirectory);

    fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";
    fs::path libUnity;

    if (config.useOriginalLibUnity)
    {
        libUnity = gameLibsPath / "libunity.so";
    }
    else
    {
        libUnity = codeCache / "libunity.so";
    }

    std::string libUnityPath = libUnity.string();
    try_hook_libunity(libUnityPath, (gameLibsPath / "libunity.so").string());

    fs::path patchedLibIl2Cpp = codeCache / "libil2cpp.so";
    allocate_setup_injected(libIl2Cpp.c_str(), patchedLibIl2Cpp.c_str(), 1024 * 1024);

    std::string patchedPath = patchedLibIl2Cpp.string();
    libmain_set_override_il2cpp_path(patchedPath.c_str());
    libmain_set_override_unity_path(libUnityPath.c_str());

    log(LogLevel::INFO, TAG, "FusionCore bootstrap finished successfully.");
    return true;
}

int il2cpp_init_hook(char *domain_name)
{
    log_format(LogLevel::INFO, TAG, "il2cpp_init called with domain: {}", domain_name);
    il2cpp_destroy_init_hook();

    // call the original il2cpp_init function
    int result = il2cpp_init(domain_name);

    if (runtimeConfig.initialized)
    {
        // setup environment variables
        setenv("BEPINEX_GAME_ASSEMBLY_PATH", libmain_get_override_il2cpp_path(), 1);
        setenv("FUSION_BEPINEX_PATH", runtimeConfig.bepInExDirectory.c_str(), 1);
        setenv("FUSION_GAME_BINARY", libmain_get_override_il2cpp_path(), 1);
        setenv("FUSION_GAME_DATA_DIR", runtimeConfig.unityDataDirectory.c_str(), 1);
        setenv("FUSION_APP_DATA_DIR", runtimeConfig.appDataDirectory.c_str(), 1);
        setenv("FUSION_UNITY_VERSION", runtimeConfig.unityVersion.c_str(), 1);

        const char *ssl_cert_path = "/apex/com.android.conscrypt/cacerts";
        const char *backup_cert_path = "/system/etc/security/cacerts";
        if (access(ssl_cert_path, R_OK) == 0) {
            setenv("SSL_CERT_DIR", ssl_cert_path, 1);
        } else if (access(backup_cert_path, R_OK) == 0) {
            setenv("SSL_CERT_DIR", backup_cert_path, 1);
        } else {
            log(LogLevel::WARN, TAG, "No readable SSL cert file found; HTTPS requests may fail.");
        }
        log_format(LogLevel::INFO, TAG, "Using {} for SSL certificates", getenv("SSL_CERT_DIR"));

        fs::path bepInExCoreDirectory = fs::path(runtimeConfig.bepInExDirectory) / "core";

        DotNetConfig dotNetConfig;
        dotNetConfig.runtimeDir = runtimeConfig.dotnetDirectory;
        dotNetConfig.managedLibsDir = bepInExCoreDirectory.string();
        dotNetConfig.entryPointAssembly = "BepInEx.Unity.IL2CPP";
        dotNetConfig.entryPointType = "BepInEx.Unity.IL2CPP.FusionCoreEntrypoint";
        dotNetConfig.entryPointMethod = "Start";

        // set TMPDIR for MonoMod lib drops
        setenv("TMPDIR", runtimeConfig.codeCacheDirectory.c_str(), 1);

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

extern "C" [[maybe_unused]] bool fusion_bootstrap_from_libmain(JNIEnv *env)
{
    (void) env;

    log(LogLevel::INFO, TAG, "FusionCore bootstrap starting...");
    jobject javaConfig = get_fusion_config(env);
    FusionConfig config = fusion_parse_config(env, javaConfig);
    env->DeleteLocalRef(javaConfig);

    log(LogLevel::INFO, TAG, "Executing Fusion bootstrap from libmain namespace...");
    if (!execute_fusion_config(config)) {
        log(LogLevel::ERROR, TAG, "Failed to execute Fusion bootstrap.");
        return false;
    }

    runtimeConfig = config;

    auto il2cpp_path = libmain_get_override_il2cpp_path();
    if (!il2cpp_initialize(il2cpp_path))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}", il2cpp_path);
        return false;
    }

    auto library_size = reinterpret_cast<size_t>(get_injected_pool_base() - il2cpp_get_library_base());
    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_get_library_base(), library_size, allocate_injected))
    {
        log(LogLevel::ERROR, TAG, "Failed to initialize SafeHook");
        return false;
    }

    log(LogLevel::INFO, TAG, "Installing il2cpp hooks...");
    il2cpp_install_init_hook(il2cpp_init_hook);
    log(LogLevel::INFO, TAG, "il2cpp hooks installed successfully!");
    log(LogLevel::INFO, TAG, "FusionCore bootstrap finished successfully.");
    return true;
}

