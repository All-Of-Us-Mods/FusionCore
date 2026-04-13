#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "LibMain"
#define LOGI(fmt, ...) \
__android_log_print(ANDROID_LOG_INFO, LOG_TAG, fmt, ##__VA_ARGS__); \
if (logFile) { \
    fprintf(logFile, fmt "\n", ##__VA_ARGS__); \
    fflush(logFile); \
}

#define LOGE(fmt, ...) \
__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__); \
if (logFile) { \
    fprintf(logFile, fmt "\n", ##__VA_ARGS__); \
    fflush(logFile); \
}

using JNI_OnLoad_t = jint (*)(JavaVM *vm, void *reserved);
using JNI_Unload_t = void (*)(JavaVM *vm, void *reserved);
using FusionStageFromConfigPath_t = bool (*)(const char *configPath);
using FusionBootstrapFromLibMain_t = bool (*)(JNIEnv *env);

static FILE *logFile = nullptr;
static std::string log_path;
static std::string override_unity_path;
static std::string override_il2cpp_path;

static void *unityLibHandle = nullptr;
static void *il2cppLibHandle = nullptr;
static void *cryptoLibHandle = nullptr;

static std::string build_sibling_library_path(const char *libraryFileName)
{
    Dl_info info{};
    if (dladdr(reinterpret_cast<void *>(&build_sibling_library_path), &info) == 0 || !info.dli_fname) {
        return {};
    }

    std::string selfPath(info.dli_fname);
    size_t lastSlash = selfPath.find_last_of('/');
    if (lastSlash == std::string::npos) {
        return {};
    }

    return selfPath.substr(0, lastSlash + 1) + libraryFileName;
}

static bool preload_sibling_library(const char *libraryFileName)
{
    const std::string siblingPath = build_sibling_library_path(libraryFileName);
    if (siblingPath.empty()) {
        LOGE("preload_sibling_library: failed to build sibling path for %s", libraryFileName);
        return false;
    }

    dlerror();
    void *handle = dlopen(siblingPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char *err = dlerror();
        LOGE("preload_sibling_library: dlopen failed for %s at %s: %s",
             libraryFileName, siblingPath.c_str(), err ? err : "(no dlerror)");
        return false;
    }

    LOGI("preload_sibling_library: loaded %s from %s", libraryFileName, siblingPath.c_str());
    return true;
}

static bool preload_dotnet_runtime_libraries()
{
    const char *required[] = {
            "libcrypto.so",
            "libssl.so",
            "libcoreclr.so",
            "libclrjit.so",
            "libSystem.Native.so",
            "libSystem.Globalization.Native.so",
            "libSystem.IO.Compression.Native.so",
            "libSystem.Security.Cryptography.Native.OpenSsl.so"
    };

    for (const char *libraryName : required) {
        if (!preload_sibling_library(libraryName)) {
            LOGE("preload_dotnet_runtime_libraries: failed to load required runtime library %s", libraryName);
            return false;
        }
    }

    // Optional diagnostics/profiling helpers used by some runtimes.
    preload_sibling_library("libmscordbi.so");
    preload_sibling_library("libmscordaccore.so");
    return true;
}

static std::string jstring_to_string(JNIEnv *env, jstring value)
{
    if (!value) {
        return std::string();
    }

    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        return std::string();
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static std::string resolve_files_dir_path(JNIEnv *env)
{
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    if (!activityThreadClass) {
        LOGE("resolve_files_dir_path: failed to find ActivityThread class");
        return {};
    }

    jmethodID currentApplicationMethod = env->GetStaticMethodID(
            activityThreadClass,
            "currentApplication",
            "()Landroid/app/Application;");
    if (!currentApplicationMethod) {
        LOGE("resolve_files_dir_path: failed to find ActivityThread.currentApplication");
        return {};
    }

    jobject applicationObject = env->CallStaticObjectMethod(activityThreadClass, currentApplicationMethod);
    if (!applicationObject) {
        LOGE("resolve_files_dir_path: ActivityThread.currentApplication returned null");
        return {};
    }

    jclass contextClass = env->FindClass("android/content/Context");
    jmethodID getFilesDirMethod = env->GetMethodID(contextClass, "getFilesDir", "()Ljava/io/File;");
    jobject filesDirObject = env->CallObjectMethod(applicationObject, getFilesDirMethod);

    if (!filesDirObject) {
        LOGE("resolve_files_dir_path: getFilesDir returned null");
        return {};
    }

    jclass fileClass = env->FindClass("java/io/File");
    jmethodID getAbsolutePathMethod = env->GetMethodID(fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring filesDirPath = (jstring) env->CallObjectMethod(filesDirObject, getAbsolutePathMethod);
    return jstring_to_string(env, filesDirPath);
}

static std::string resolve_staged_config_path(JNIEnv *env, jobject activityObject)
{
    (void) activityObject;
    std::string filesDir = resolve_files_dir_path(env);
    if (filesDir.empty()) {
        return {};
    }

    return filesDir + "/bootstrap/active.cfg";
}

static void *resolve_or_load_fusion_handle()
{
    dlerror();
    void *fusionHandle = dlopen("libfusion.so", RTLD_NOW | RTLD_NOLOAD);
    if (fusionHandle) {
        return fusionHandle;
    }

    const char *defaultScopeError = dlerror();
    if (defaultScopeError) {
        LOGI("resolve_or_load_fusion_handle: libfusion.so missing from namespace: %s", defaultScopeError);
    }

    if (!preload_sibling_library("libxdl.so")) {
        return nullptr;
    }

    preload_sibling_library("libdobby.so");
    if (!preload_dotnet_runtime_libraries()) {
        return nullptr;
    }

    if (!preload_sibling_library("libfusion.so")) {
        return nullptr;
    }

    dlerror();
    fusionHandle = dlopen("libfusion.so", RTLD_NOW | RTLD_NOLOAD);
    if (!fusionHandle) {
        const char *noLoadErr = dlerror();
        LOGE("resolve_or_load_fusion_handle: libfusion.so still not visible after preload: %s",
             noLoadErr ? noLoadErr : "(no dlerror)");
        return nullptr;
    }

    LOGI("resolve_or_load_fusion_handle: loaded libfusion.so into current namespace via sibling preload");
    return fusionHandle;
}

static bool resolve_fusion_symbols(FusionStageFromConfigPath_t *stageFromConfig,
                                   FusionBootstrapFromLibMain_t *bootstrap)
{
    void *fusionHandle = resolve_or_load_fusion_handle();
    if (!fusionHandle) {
        return false;
    }

    dlerror();
    *stageFromConfig = reinterpret_cast<FusionStageFromConfigPath_t>(dlsym(fusionHandle, "fusion_stage_from_config_path"));
    if (!*stageFromConfig) {
        const char *symErr = dlerror();
        LOGE("resolve_fusion_symbols: dlsym failed for fusion_stage_from_config_path: %s",
             symErr ? symErr : "(no dlerror)");
        return false;
    }

    dlerror();
    *bootstrap = reinterpret_cast<FusionBootstrapFromLibMain_t>(dlsym(fusionHandle, "fusion_bootstrap_from_libmain"));
    if (!*bootstrap) {
        const char *symErr = dlerror();
        LOGE("resolve_fusion_symbols: dlsym failed for fusion_bootstrap_from_libmain: %s",
             symErr ? symErr : "(no dlerror)");
        return false;
    }

    return true;
}

jboolean internal_load(JNIEnv *env, const char *libraryPath, void **libHandle)
{
    if (!libraryPath) {
        LOGE("internal_load: libraryPath is null");
        return JNI_FALSE;
    }

    LOGI("internal_load: attempting to load '%s'", libraryPath);

    void *handle = dlopen(libraryPath, RTLD_LAZY | RTLD_LOCAL);

    if (!handle)
    {
        const char *err = dlerror();
        LOGE("dlopen failed for '%s': %s", libraryPath, err ? err : "(no dlerror)");
        return JNI_FALSE; // Failed to load Unity library
    }

    JNI_OnLoad_t jniOnLoad = reinterpret_cast<JNI_OnLoad_t>(dlsym(handle, "JNI_OnLoad"));
    if (!jniOnLoad)
    {
        const char *err = dlerror();
        LOGE("dlsym JNI_OnLoad not found in '%s': %s", libraryPath, err ? err : "(no dlerror)");
        dlclose(handle);
        handle = nullptr;
        return JNI_FALSE; // JNI_OnLoad symbol not found
    }

    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK)
    {
        LOGE("internal_load: GetJavaVM failed for '%s'", libraryPath);
        dlclose(handle);
        handle = nullptr;
        return JNI_FALSE; // Failed to obtain Java VM
    }

    jint result = jniOnLoad(vm, nullptr);
    if (result < JNI_VERSION_1_6)
    {
        LOGE("JNI_OnLoad in '%s' returned version %d (expected >= %d)", libraryPath, result, JNI_VERSION_1_6);
        dlclose(handle);
        handle = nullptr;
        return JNI_FALSE; // JNI version mismatch
    }

    *libHandle = handle;
    LOGI("internal_load: successfully loaded '%s'", libraryPath);
    return JNI_TRUE; // Successfully loaded Unity library
}

jboolean internal_unload(JNIEnv *env, void **libHandle)
{
    if (!*libHandle)
    {
        LOGI("internal_unload: libHandle is null, nothing to unload");
        return JNI_FALSE; // Library not loaded
    }

    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK)
    {
        LOGE("internal_unload: GetJavaVM failed");
        return JNI_FALSE; // Failed to obtain Java VM
    }

    void *handle = *libHandle;
    *libHandle = nullptr;

    void *unload = dlsym(handle, "JNI_Unload");
    if (unload)
    {
        JNI_Unload_t jniUnload = reinterpret_cast<JNI_Unload_t>(unload);
        LOGI("internal_unload: calling JNI_Unload");
        jniUnload(vm, nullptr);
    }
    else
    {
        const char *err = dlerror();
        LOGI("internal_unload: JNI_Unload not found: %s", err ? err : "(no dlerror)");
    }

    dlclose(handle);
    LOGI("internal_unload: successfully unloaded library");
    return JNI_TRUE; // Successfully unloaded library
}

extern "C" {

void libmain_set_override_unity_path(const char *path)
{
    override_unity_path = path;
    LOGI("set_override_unity_path: %s", path ? path : "(null)");
}

void libmain_set_override_il2cpp_path(const char *path)
{
    override_il2cpp_path = path;
    LOGI("set_override_il2cpp_path: %s", path ? path : "(null)");
}

const char *libmain_get_override_unity_path()
{
    return override_unity_path.c_str();
}

const char *libmain_get_override_il2cpp_path()
{
    return override_il2cpp_path.c_str();
}

void libmain_set_log_path(const char *path)
{
    log_path = path;
    if (logFile)
    {
        fclose(logFile);
        logFile = nullptr;
    }

    if (!log_path.empty())
    {
        logFile = fopen(log_path.c_str(), "w");
        if (logFile)
        {
            LOGI("Logging initialized at %s", log_path.c_str());
        }
        else
        {
            LOGE("Failed to open log file at %s", log_path.c_str());
        }
    }
}

JNIEXPORT jboolean JNICALL
load(JNIEnv *env, jobject activityObject, jstring path)
{
    (void) path;

    FusionStageFromConfigPath_t stageFromConfig = nullptr;
    FusionBootstrapFromLibMain_t bootstrap = nullptr;
    if (!resolve_fusion_symbols(&stageFromConfig, &bootstrap)) {
        return JNI_FALSE;
    }

    std::string configPath = resolve_staged_config_path(env, activityObject);
    if (configPath.empty()) {
        LOGE("load: failed to resolve staged Fusion config path");
        return JNI_FALSE;
    }

    LOGI("load: resolved staged config path=%s", configPath.c_str());
    if (!stageFromConfig(configPath.c_str())) {
        LOGE("load: fusion_stage_from_config_path failed");
        return JNI_FALSE;
    }

    // stageFromConfig sets libmain overrides for unity and il2cpp paths.
    const char *unityPath = override_unity_path.c_str();
    const char *il2cppPath = override_il2cpp_path.c_str();
    LOGI("load: unityPath=%s, il2cppPath=%s", unityPath ? unityPath : "(null)", il2cppPath ? il2cppPath : "(null)");


    if (!unityPath || !il2cppPath)
    {
        LOGE("load: paths not set");
        return JNI_FALSE; // Paths not set
    }

    if (!internal_load(env, unityPath, &unityLibHandle))
    {
        LOGE("load: failed to load Unity library from %s", unityPath);
        return JNI_FALSE; // Failed to load Unity library
    }

    if (!internal_load(env, il2cppPath, &il2cppLibHandle))
    {
        // Unload previously loaded Unity library
        if (unityLibHandle)
        {
            LOGI("load: unloading previously loaded Unity library due to IL2CPP load failure");
            internal_unload(env, &unityLibHandle);
        }

        LOGE("load: failed to load IL2CPP library from %s", il2cppPath);
        return JNI_FALSE; // Failed to load IL2CPP library
    }

    if (!bootstrap(env))
    {
        LOGE("load: fusion_bootstrap_from_libmain failed");
        return JNI_FALSE;
    }

    LOGI("load: successfully loaded Unity and IL2CPP libraries");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
unload(JNIEnv *env, jclass activityObject)
{
    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK)
    {
        LOGE("unload: GetJavaVM failed");
        return JNI_FALSE; // Failed to obtain Java VM
    }

    if (cryptoLibHandle && !internal_unload(env, &cryptoLibHandle))
    {
        LOGE("unload: failed to unload crypto JNI library");
        return JNI_FALSE;
    }

    if (unityLibHandle && !internal_unload(env, &unityLibHandle))
    {
        LOGE("unload: failed to unload Unity library");
        return JNI_FALSE; // Failed to unload Unity library
    }

    if (il2cppLibHandle && !internal_unload(env, &il2cppLibHandle))
    {
        LOGE("unload: failed to unload IL2CPP library");
        return JNI_FALSE; // Failed to unload IL2CPP library
    }

    if (logFile)
    {
        fclose(logFile);
        logFile = nullptr;
    }

    LOGI("unload: successfully unloaded all libraries");
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *globalEnv;
    if (vm->GetEnv(reinterpret_cast<void **>(&globalEnv), JNI_VERSION_1_6) != JNI_OK)
    {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR; // Failed to obtain JNIEnv
    }

    jclass clazz = globalEnv->FindClass("com/unity3d/player/NativeLoader");
    if (!clazz)
    {
        LOGE("JNI_OnLoad: FindClass com/unity3d/player/NativeLoader failed");
        return JNI_ERR; // Class not found
    }

    static const JNINativeMethod methods[] = {
            {"load",   "(Ljava/lang/String;)Z", reinterpret_cast<void *>(load)},
            {"unload", "()Z",                   reinterpret_cast<void *>(unload)}
    };

    jint ret = globalEnv->RegisterNatives(clazz, methods,
                                          sizeof(methods) / sizeof(JNINativeMethod));
    if (ret != JNI_OK)
    {
        LOGE("JNI_OnLoad: RegisterNatives failed with code %d", ret)
        return ret; // Failed to register natives
    }

    LOGI("JNI_OnLoad: successfully registered natives and initialized")
    return JNI_VERSION_1_6; // Successful initialization
}
} // extern "C"

