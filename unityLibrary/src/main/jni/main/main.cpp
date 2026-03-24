#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <unordered_map>
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

static FILE *logFile = nullptr;
static std::string log_path;
static std::string override_unity_path;
static std::string override_il2cpp_path;

static void *unityLibHandle = nullptr;
static void *il2cppLibHandle = nullptr;

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

void set_override_unity_path(const char *path)
{
    override_unity_path = path;
    LOGI("set_override_unity_path: %s", path ? path : "(null)");
}

void set_override_il2cpp_path(const char *path)
{
    override_il2cpp_path = path;
    LOGI("set_override_il2cpp_path: %s", path ? path : "(null)");
}

void set_log_path(const char *path)
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
    // we ignore the given path and use our overridden paths instead
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
        LOGE("JNI_OnLoad: RegisterNatives failed with code %d", ret);
        return ret; // Failed to register natives
    }

    LOGI("JNI_OnLoad: successfully registered natives and initialized");
    return JNI_VERSION_1_6; // Successful initialization
}
} // extern "C"

