// Copyright (c) 2026 XtraCube
#include <unistd.h>
#include <jni.h>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <unordered_map>
#include <logger.h>
#include <libmain.h>
#include <fusion_config.h>
#include <hooking/il2cpp.h>
#include <hooking/safehook.h>
#include <hooking/allocator.h>
#include <hooking/libunity.h>
#include <external/dobby.h>
#include <external/xdl.h>

#define TAG "FusionCore"

namespace fs = std::filesystem;

static FusionConfig runtimeConfig;
static FusionConfig stagedConfig;
static std::string stagedPatchedIl2CppPath;
static std::mutex stageMutex;
static bool hasStagedConfig = false;

using BootstrapStartupFn = void (*)();
using BootstrapJniOnLoadFn = jint (*)(JavaVM *, void *);

static std::unordered_map<std::string, std::string> read_key_value_file(const char *configPath)
{
    std::unordered_map<std::string, std::string> values;
    std::ifstream input(configPath);
    std::string line;

    while (std::getline(input, line)) {
        if (line.empty()) {
            continue;
        }

        size_t split = line.find('=');
        if (split == std::string::npos) {
            continue;
        }

        std::string key = line.substr(0, split);
        std::string value = line.substr(split + 1);
        values[key] = value;
    }

    return values;
}

static bool parse_bool_value(const std::string &value)
{
    return value == "1" || value == "true" || value == "TRUE";
}

static bool parse_fusion_config_from_file(const char *configPath, FusionConfig *config)
{
    auto values = read_key_value_file(configPath);
    if (values.empty()) {
        log_format(LogLevel::ERROR, TAG, "Config file is empty or unreadable: {}", configPath);
        return false;
    }

    config->gameLibraryDirectory = values["gameLibraryDirectory"];
    config->appLibraryDirectory = values["appLibraryDirectory"];
    config->appDataDirectory = values["appDataDirectory"];
    config->unityDataDirectory = values["unityDataDirectory"];
    config->unityVersion = values["unityVersion"];
    config->useOriginalLibUnity = parse_bool_value(values["useOriginalLibUnity"]);

    if (config->gameLibraryDirectory.empty() || config->appDataDirectory.empty()) {
        log_format(LogLevel::ERROR, TAG, "Invalid config file (missing required fields): {}", configPath);
        return false;
    }

    config->initialized = true;
    return true;
}

static bool stage_fusion_config(const FusionConfig &parsedConfig)
{
    fusion_print_config(parsedConfig);

    fs::path gameLibsPath(parsedConfig.gameLibraryDirectory);
    fs::path appDataPath(parsedConfig.appDataDirectory);

    fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";
    fs::path libUnity;

    if (parsedConfig.useOriginalLibUnity)
    {
        libUnity = gameLibsPath / "libunity.so";
    }
    else
    {
        libUnity = appDataPath / "libunity.so";
    }

    std::string libUnityPath = libUnity.string();
    try_hook_libunity(libUnityPath, (gameLibsPath / "libunity.so").string());

    fs::path patchedLibIl2Cpp = appDataPath / "libil2cpp.so";
    allocate_setup_injected(libIl2Cpp.c_str(), patchedLibIl2Cpp.c_str(), 1024 * 1024);

    std::string patchedPath = patchedLibIl2Cpp.string();
    libmain_set_override_il2cpp_path(patchedPath.c_str());
    libmain_set_override_unity_path(libUnityPath.c_str());

    {
        std::lock_guard<std::mutex> guard(stageMutex);
        stagedConfig = parsedConfig;
        stagedPatchedIl2CppPath = patchedPath;
        hasStagedConfig = true;
    }

    log(LogLevel::INFO, TAG, "FusionCore config staged successfully.");
    return true;
}

static std::string resolve_sibling_library_path(const char *libraryFileName)
{
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(&resolve_sibling_library_path), &info) == 0 || !info.dli_fname) {
        return {};
    }

    std::string selfPath(info.dli_fname);
    size_t lastSlash = selfPath.find_last_of('/');
    if (lastSlash == std::string::npos) {
        return {};
    }

    return selfPath.substr(0, lastSlash + 1) + libraryFileName;
}

static bool launch_lemonloader_bootstrap(JNIEnv *env)
{
    // .NET 8 CoreCLR on Android needs W^X disabled and ReadyToRun off, otherwise
    // JIT compilation deadlocks/aborts during the first managed call. Must be set
    // before libcoreclr loads (i.e. before libBootstrap's hostfxr init runs).
    setenv("DOTNET_EnableWriteXorExecute", "0", 1);
    setenv("DOTNET_ReadyToRun", "0", 1);

    const char *bootstrapName = "libBootstrap.so";
    std::string bootstrapPath = resolve_sibling_library_path(bootstrapName);
    if (bootstrapPath.empty()) {
        log_format(LogLevel::ERROR, TAG,
                   "Failed to resolve sibling path for {}", bootstrapName);
        return false;
    }

    dlerror();
    void *handle = dlopen(bootstrapPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char *err = dlerror();
        log_format(LogLevel::ERROR, TAG,
                   "Failed to dlopen {}: {}", bootstrapPath, err ? err : "(no dlerror)");
        return false;
    }

    auto jniOnLoad = reinterpret_cast<BootstrapJniOnLoadFn>(dlsym(handle, "JNI_OnLoad"));
    if (!jniOnLoad) {
        log_format(LogLevel::ERROR, TAG, "{} missing JNI_OnLoad export", bootstrapName);
        return false;
    }

    auto startup = reinterpret_cast<BootstrapStartupFn>(dlsym(handle, "startup"));
    if (!startup) {
        log_format(LogLevel::ERROR, TAG, "{} missing startup export", bootstrapName);
        return false;
    }

    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
        log(LogLevel::ERROR, TAG, "Failed to obtain JavaVM for libBootstrap initialization.");
        return false;
    }

    jint loadResult = jniOnLoad(vm, nullptr);
    if (loadResult < JNI_VERSION_1_6) {
        log_format(LogLevel::ERROR, TAG,
                   "libBootstrap JNI_OnLoad returned unsupported version {}", loadResult);
        return false;
    }

    log(LogLevel::INFO, TAG, "libBootstrap JNI_OnLoad completed; invoking startup...");
    startup();
    log(LogLevel::INFO, TAG, "libBootstrap startup returned.");
    return true;
}

extern "C" bool fusion_stage_from_config_path(const char *configPath)
{
    if (!configPath) {
        log(LogLevel::ERROR, TAG, "fusion_stage_from_config_path called with null path");
        return false;
    }

    log_format(LogLevel::INFO, TAG, "Staging FusionCore config from {}", configPath);
    FusionConfig parsedConfig{};
    if (!parse_fusion_config_from_file(configPath, &parsedConfig)) {
        return false;
    }

    return stage_fusion_config(parsedConfig);
}

extern "C" bool fusion_bootstrap_from_libmain(JNIEnv *env)
{
    FusionConfig configToRun;
    std::string patchedIl2CppPath;
    {
        std::lock_guard<std::mutex> guard(stageMutex);
        if (!hasStagedConfig)
        {
            log(LogLevel::ERROR, TAG, "No staged FusionConfig available; cannot bootstrap from libmain namespace.");
            return false;
        }

        configToRun = stagedConfig;
        patchedIl2CppPath = stagedPatchedIl2CppPath;
    }

    runtimeConfig = configToRun;

    log(LogLevel::INFO, TAG, "Executing Fusion bootstrap from libmain namespace...");

    if (!il2cpp_initialize(patchedIl2CppPath.c_str()))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to initialize il2cpp with path: {}", patchedIl2CppPath);
        return false;
    }

    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_get_library_base(), allocate_injected))
    {
        log(LogLevel::ERROR, TAG, "Failed to initialize SafeHook");
        return false;
    }

    log(LogLevel::INFO, TAG, "Handing control to LemonLoader (libBootstrap.so)...");
    if (!launch_lemonloader_bootstrap(env))
    {
        log(LogLevel::ERROR, TAG, "Failed to launch LemonLoader bootstrap.");
        return false;
    }

    log(LogLevel::INFO, TAG, "FusionCore bootstrap finished successfully.");
    return true;
}
