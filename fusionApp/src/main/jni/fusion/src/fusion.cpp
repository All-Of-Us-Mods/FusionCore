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
#include <hooking/Assetmanager.h>
#include <dotnet.h>
#include <external/dobby.h>
#include <utilities/java.h>
#include <dlfcn.h>

#define TAG "FusionCore"

namespace fs = std::filesystem;

static FusionConfig runtimeConfig;

static bool execute_fusion_config(const FusionConfig &config)
{
    fusion_print_config(config);

    fs::path gameLibsPath(config.gameLibraryDirectory);
    fs::path codeCache(config.codeCacheDirectory);
    fs::path gamePath = fs::path(config.bepInExDirectory).parent_path();

    fs::path libUnity;
    fs::path patchedLibIl2Cpp;
    if(config.isIl2Cpp2Mono){
        fs::path MonoPath(config.dotnetDirectory);

        fs::path monoLib = MonoPath / "libmonosgen-2.0.so";
        void* handle = dlopen(monoLib.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            log_format(LogLevel::ERROR, TAG, "Failed to dlopen libmonosgen-2.0.so: {}", dlerror());
            return false;
        }
        patchedLibIl2Cpp = MonoPath / "libil2cpp.so";
        handle = dlopen(patchedLibIl2Cpp.c_str(), RTLD_GLOBAL | RTLD_NOW);
        if (!handle) {
            log_format(LogLevel::ERROR, TAG, "Failed to dlopen {}: {}", patchedLibIl2Cpp.string(), dlerror());
            return false;
        }
        using SetOverrideDirs = void (*)(const char*, const char*);
        auto set_override_dirs =
                reinterpret_cast<SetOverrideDirs>(
                        dlsym(handle, "il2cpp2mono_set_override_dirs")
                );
        fs::path dllpath = gamePath / "PersistentData/mono";
        fs::path monopath = MonoPath / "mono";
        log_format(LogLevel::INFO, TAG, "setting il2cpp2mono paths: {}, {}", dllpath.c_str(), monopath.c_str());
        set_override_dirs(dllpath.c_str(), monopath.c_str());
    }
    else{
        patchedLibIl2Cpp = codeCache / "libil2cpp.so";
        fs::path libIl2Cpp = gameLibsPath / "libil2cpp.so";
        allocate_setup_injected(libIl2Cpp.c_str(), patchedLibIl2Cpp.c_str(), 1024 * 1024);
    }
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

    std::string patchedPath = patchedLibIl2Cpp.string();
    libmain_set_override_il2cpp_path(patchedPath.c_str());
    libmain_set_override_unity_path(libUnityPath.c_str());

    log(LogLevel::INFO, TAG, "FusionCore bootstrap finished successfully.");
    return true;
}

int il2cpp_init_hook(char *domain_name)
{
    const char *d_name = domain_name ? domain_name : "Unknown Domain Name";
    log_format(LogLevel::INFO, TAG, "il2cpp_init called with domain: {}", d_name);
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
        const char *ssl_path = getenv("SSL_CERT_DIR");
        const char *safe_ssl_path = ssl_path ? ssl_path : "(null)";
        log_format(LogLevel::INFO, TAG, "Using {} for SSL certificates", safe_ssl_path);

        fs::path bepInExCoreDirectory = fs::path(runtimeConfig.bepInExDirectory) / "core";

        DotNetConfig dotNetConfig;
        dotNetConfig.runtimeDir = runtimeConfig.dotnetDirectory;
        dotNetConfig.managedLibsDir = bepInExCoreDirectory.string();
        dotNetConfig.entryPointAssembly = "BepInEx.Unity.IL2CPP";
        dotNetConfig.entryPointType = "BepInEx.Unity.IL2CPP.FusionCoreEntrypoint";
        dotNetConfig.entryPointMethod = "Start";

        // set TMPDIR for MonoMod lib drops
        setenv("TMPDIR", runtimeConfig.codeCacheDirectory.c_str(), 1);

        // create AuxFolderPluginList
        auto aux = runtimeConfig.auxiliaryPluginFolders;
        int size = static_cast<int>(aux.size());
        const char** pointerArray = new const char*[size];

        for (int i = 0; i < size; i++) {
            char* strCopy = new char[aux[i].length() + 1];
            std::copy(aux[i].begin(), aux[i].end(), strCopy);
            strCopy[aux[i].length()] = '\0';

            pointerArray[i] = strCopy;
        }

        AuxPluginFolderList list{};
        list.count = size;
        list.folders = pointerArray;

        // change working directory to fusion's scoped data directory
        chdir(runtimeConfig.appDataDirectory.c_str());
        if(!runtimeConfig.isIl2Cpp2Mono) {
            // execute the managed assembly
            dotnet_execute_assembly(dotNetConfig, &list);
        }//we wont be using this, for now.
        else{
            setLoadingState(false);
        }
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

    // Pool placement and library bounds must be trustworthy before any hooking.
    const uintptr_t pool_base = get_injected_pool_base();
    const uintptr_t il2cpp_base = il2cpp_get_library_base();
    if (pool_base == 0 || il2cpp_base == 0 || pool_base <= il2cpp_base)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Invalid pool/library base relationship! pool=0x{:X}, il2cpp=0x{:X}",
                   pool_base, il2cpp_base);
        return false;
    }

    // plain integer subtraction (pool sits directly above the library image)
    const size_t library_size = pool_base - il2cpp_base;
    constexpr size_t kMaxPlausibleLibrarySize = 512ull * 1024 * 1024;
    if (library_size > kMaxPlausibleLibrarySize)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Implausible library size 0x{:X}; refusing to initialize SafeHook",
                   library_size);
        return false;
    }

    if (!safehook_initialize(il2cpp_get_handle(), il2cpp_base, library_size, allocate_injected))
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

