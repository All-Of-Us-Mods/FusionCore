// Copyright (c) 2026 XtraCube. All rights reserved.
#include <utilities/java.h>
#include <logger.h>
#include <jni.h>

static JavaVM* g_vm = nullptr;
jclass jniBridge = nullptr;
jmethodID setLoadingStateID = nullptr;
jmethodID setLoadingTextID = nullptr;

#define TAG "Fusion.JNI"

jclass find_class_in_app_classloader(JNIEnv *env, const char *className) {
    jclass activityThreadClass = env->FindClass("android/app/ActivityThread");
    jmethodID currentActivityThreadMethod = env->GetStaticMethodID(activityThreadClass, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject activityThread = env->CallStaticObjectMethod(activityThreadClass, currentActivityThreadMethod);

    jmethodID getApplicationMethod = env->GetMethodID(activityThreadClass, "getApplication", "()Landroid/app/Application;");
    jobject application = env->CallObjectMethod(activityThread, getApplicationMethod);

    jclass applicationClass = env->GetObjectClass(application);
    jmethodID getClassLoaderMethod = env->GetMethodID(applicationClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject classLoader = env->CallObjectMethod(application, getClassLoaderMethod);

    jclass classLoaderClass = env->GetObjectClass(classLoader);
    jmethodID loadClassMethod = env->GetMethodID(classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");

    jstring classNameUtf = env->NewStringUTF(className);
    jclass clazz = (jclass) env->CallObjectMethod(classLoader, loadClassMethod, classNameUtf);

    if (!clazz) {
        log(LogLevel::ERROR, TAG, "Failed to find JniBridge class via explicit ClassLoader!");
        env->ExceptionClear();
        return nullptr;
    }

    return clazz;
}


extern "C" void init_java(JavaVM *vm) {
    if (g_vm != nullptr) {
        log(LogLevel::INFO, TAG, "Already loaded JNI...");
        return;
    }

    g_vm = vm;
    JNIEnv *env = getJNIEnv();

    {
        jclass jniBridgeClass = find_class_in_app_classloader(env, "dev/allofus/fusioncore/tools/JniBridge");
        if (!jniBridgeClass)
        {
            log(LogLevel::ERROR, TAG, "Failed to find JniBridge class!");
            env->ExceptionClear();
            return;
        }

        jniBridge = (jclass) env->NewGlobalRef(jniBridgeClass);
        env->DeleteLocalRef(jniBridgeClass);
    }

    setLoadingStateID = env->GetStaticMethodID(jniBridge, "SetLoadingState",
                                               "(Landroid/app/Activity;Z)V");
    setLoadingTextID = env->GetStaticMethodID(jniBridge, "SetLoadingText",
                                              "(Landroid/app/Activity;Ljava/lang/String;)V");

    if (!setLoadingStateID || !setLoadingTextID)
    {
        log(LogLevel::ERROR, TAG, "Failed to map JniBridge static methods!");
        return;
    }

    log(LogLevel::INFO, TAG, "Successfully loaded libfusion!");
}


void setLoadingState(bool enabled) {
    if (!setLoadingStateID) {
        log(LogLevel::ERROR, TAG, "Cannot set loading state, method ID null!");
        return;
    }

    JNIEnv *env = getJNIEnv();
    if (!env) {
        log(LogLevel::ERROR, TAG, "Cannot set loading state, JNI Env null!");
        return;
    }

    jobject activity = getUnityActivity(env);
    if (!activity) {
        log(LogLevel::ERROR, TAG, "Cannot set loading state, activity was null!");
        return;
    }

    jboolean enableState = enabled ? JNI_TRUE : JNI_FALSE;
    env->CallStaticVoidMethod(jniBridge, setLoadingStateID, activity, enableState);
    env->DeleteLocalRef(activity);
}

void setLoadingText(const char *text) {
    if (!setLoadingTextID) {
        log(LogLevel::ERROR, TAG, "Cannot set loading text, method ID null!");
        return;
    }

    if (!text) {
        log(LogLevel::ERROR, TAG, "Cannot set loading text, input text was null!");
        return;
    }

    JNIEnv *env = getJNIEnv();
    if (!env) {
        log(LogLevel::ERROR, TAG, "Cannot set loading text, JNI Env null!");
        return;
    }

    jobject activity = getUnityActivity(env);
    if (!activity) {
        log(LogLevel::ERROR, TAG, "Cannot set loading text, activity was null!");
        return;
    }

    jstring jText = env->NewStringUTF(text);
    if (!jText) {
        env->DeleteLocalRef(activity);
        log(LogLevel::ERROR, TAG, "Cannot set loading text, java string was null!");
        return;
    }

    env->CallStaticVoidMethod(jniBridge, setLoadingTextID, activity, jText);
    env->DeleteLocalRef(jText);
    env->DeleteLocalRef(activity);
}

JNIEnv* getJNIEnv() {
    if (!g_vm) return nullptr;

    JNIEnv* env = nullptr;
    jint getEnvResult = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvResult == JNI_EDETACHED) {
        jint attachResult = g_vm->AttachCurrentThreadAsDaemon(&env, nullptr);
        if (attachResult != JNI_OK) {
            return nullptr;
        }
    } else if (getEnvResult == JNI_EVERSION) {
        return nullptr;
    }

    return env;
}

jobject getUnityActivity(JNIEnv* env)
{
    constexpr const char *unityClasses[]{
            "com/unity3d/player/UnityPlayer",
            "com/unity3d/player/UnityPlayerForGameActivity",
            "com/unity3d/player/UnityPlayerForActivityOrService",
    };

    jclass unityPlayerClass = nullptr;
    for (auto unityClass : unityClasses) {
        unityPlayerClass = env->FindClass(unityClass);
        if (unityPlayerClass) {
            break;
        }
        env->ExceptionClear();
    }

    if (!unityPlayerClass) {
        log(LogLevel::ERROR, TAG, "Could not find UnityPlayer class!");
        return nullptr;
    }

    jfieldID activityField = env->GetStaticFieldID(
            unityPlayerClass,
            "currentActivity",
            "Landroid/app/Activity;"
    );

    if (!activityField) {
        env->DeleteLocalRef(unityPlayerClass);
        return nullptr;
    }

    jobject activityObj = env->GetStaticObjectField(unityPlayerClass, activityField);
    env->DeleteLocalRef(unityPlayerClass);
    return activityObj;
}