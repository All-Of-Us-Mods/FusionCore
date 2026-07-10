// Copyright (c) 2026 XtraCube
#include <jni.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
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

static std::atomic<bool> bootHeartbeatRunning{false};

static long mem_available_mb()
{
    std::ifstream meminfo("/proc/meminfo");
    std::string line;
    while (std::getline(meminfo, line))
    {
        if (line.rfind("MemAvailable:", 0) == 0)
        {
            return std::strtol(line.c_str() + 13, nullptr, 10) / 1024;
        }
    }
    return -1;
}

// Android discards an app's stdout/stderr, but that is where il2cpp prints its asserts and
// abort reasons (and CoreCLR/Cpp2IL their native output). Mirror both into logcat so a
// controlled abort inside il2cpp_init is no longer invisible.
static void start_stdio_redirect()
{
    int fds[2];
    if (pipe(fds) != 0)
    {
        log(LogLevel::WARN, TAG, "Failed to create stdio redirect pipe");
        return;
    }

    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);

    int readFd = fds[0];
    std::thread([readFd]
    {
        char buffer[1024];
        std::string pending;
        ssize_t count;
        while ((count = read(readFd, buffer, sizeof(buffer))) > 0)
        {
            pending.append(buffer, count);
            size_t newline;
            while ((newline = pending.find('\n')) != std::string::npos)
            {
                if (newline > 0)
                {
                    log(LogLevel::INFO, "Fusion.Stdio", pending.substr(0, newline).c_str());
                }
                pending.erase(0, newline + 1);
            }
        }
    }).detach();

    log(LogLevel::INFO, TAG, "stdout/stderr redirected to logcat (tag Fusion.Stdio)");
}

// The whole boot (il2cpp init, CoreCLR, BepInEx preloader, first-run interop generation) runs
// as one silent native call stack; if the process gets OOM-killed mid-way, the last heartbeat
// shows how far it got and how memory developed.
static void start_boot_heartbeat()
{
    bootHeartbeatRunning = true;
    std::thread([]
    {
        while (bootHeartbeatRunning)
        {
            std::this_thread::sleep_for(std::chrono::seconds(5));
            if (!bootHeartbeatRunning)
            {
                break;
            }
            log_format(LogLevel::INFO, TAG, "[boot-heartbeat] mem available: {} MB", mem_available_mb());
        }
    }).detach();
}

int il2cpp_init_hook(char *domain_name)
{
    log_format(LogLevel::INFO, TAG, "il2cpp_init called with domain: {}", domain_name);
    il2cpp_destroy_init_hook();

    start_boot_heartbeat();
    log_format(LogLevel::INFO, TAG, "Calling original il2cpp_init (unity runtime bootstrap), mem available: {} MB", mem_available_mb());

    // call the original il2cpp_init function
    int result = il2cpp_init(domain_name);

    log_format(LogLevel::INFO, TAG, "Original il2cpp_init finished, mem available: {} MB", mem_available_mb());

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

        // trade some GC CPU time for a smaller managed heap; phones are RAM-bound,
        // especially during first-run interop generation
        setenv("DOTNET_GCConserveMemory", "9", 1);

        log_format(LogLevel::INFO, TAG, "Starting .NET runtime + BepInEx (first run generates interop assemblies, this can take minutes), mem available: {} MB", mem_available_mb());

        // execute the managed assembly
        dotnet_execute_assembly(dotNetConfig);
    }
    else
    {
        log(LogLevel::WARN, TAG, "FusionConfig not initialized. Skipping modloader initialization.");
    }

    bootHeartbeatRunning = false;

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

    start_stdio_redirect();

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
