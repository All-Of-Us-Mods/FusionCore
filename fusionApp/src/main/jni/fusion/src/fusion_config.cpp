// Copyright (c) 2026 XtraCube
#include <fusion_config.h>
#include <utilities/java.h>
#include <logger.h>

#define TAG "FusionConfig"

#define GET_JSTRING_FIELD(fieldName) \
do {\
    jstring fieldName##JString = (jstring) env->GetObjectField( \
        jFusionConfig, \
        env->GetFieldID(configClass, \
        #fieldName, \
        "Ljava/lang/String;") \
    ); \
    if (!fieldName##JString) { \
        env->ExceptionClear(); \
        log_format(LogLevel::WARN, TAG, "Field {} is null", #fieldName); \
    } \
    GET_JAVA_STRING(env, fieldName##JString, config.fieldName); \
} while (0); \

#define GET_JBOOLEAN_FIELD(fieldName) \
do { \
    jboolean fieldName##JBoolean = env->GetBooleanField( \
        jFusionConfig, \
        env->GetFieldID(configClass, #fieldName, "Z") \
    ); \
    if (!fieldName##JBoolean) { \
        env->ExceptionClear(); \
        log_format(LogLevel::WARN, TAG, "Field {} is null", #fieldName); \
    } \
    config.fieldName = (fieldName##JBoolean == JNI_TRUE); \
} while (0); \

std::vector<std::string> get_string_array_field(
        JNIEnv *env,
        jobject object,
        const char *fieldName)
{
    jclass clazz = env->GetObjectClass(object);
    jfieldID fieldId = env->GetFieldID(clazz, fieldName, "[Ljava/lang/String;");

    auto jArray = (jobjectArray) env->GetObjectField(object, fieldId);
    if (!jArray)
    {
        env->ExceptionClear();
        log_format(LogLevel::WARN, TAG, "String array field {} is null", fieldName);
        return {};
    }

    jsize arrayLength = env->GetArrayLength(jArray);

    std::vector<std::string> vector;
    vector.reserve(arrayLength);

    for (int i = 0; i < arrayLength; i++)
    {
        auto jStr = (jstring) env->GetObjectArrayElement(jArray, i);
        if (!jStr)
        {
            log_format(LogLevel::WARN, TAG, "String array element {} is null", i);
            continue;
        }
        const char *str = env->GetStringUTFChars(jStr, nullptr);
        vector.push_back(str);
        env->ReleaseStringUTFChars(jStr, str);
    }

    env->DeleteLocalRef(clazz);
    env->DeleteLocalRef(jArray);

    return vector;
}

std::unordered_map<std::string, std::string> get_string_map_field(
        JNIEnv *env,
        jobject object,
        const char *fieldName)
{
    std::unordered_map<std::string, std::string> result;

    jclass clazz = env->GetObjectClass(object);
    jfieldID fieldId = env->GetFieldID(clazz, fieldName, "Ljava/util/Map;");

    auto jMap = (jobject) env->GetObjectField(object, fieldId);
    if (!jMap)
    {
        env->ExceptionClear();
        log_format(LogLevel::WARN, TAG, "Map field {} is null", fieldName);
        return result;
    }

    jclass mapClass = env->GetObjectClass(jMap);
    jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    jobject entrySet = env->CallObjectMethod(jMap, entrySetMethod);

    // Get Iterator from the Set
    jclass setClass = env->GetObjectClass(entrySet);
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);

    // Iterate through entries
    jclass iteratorClass = env->GetObjectClass(iterator);
    jmethodID hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKeyMethod = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValueMethod = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

    while (env->CallBooleanMethod(iterator, hasNextMethod))
    {
        jobject entry = env->CallObjectMethod(iterator, nextMethod);

        // Extract key and value
        jstring keyStr = (jstring) env->CallObjectMethod(entry, getKeyMethod);
        jstring valueStr = (jstring) env->CallObjectMethod(entry, getValueMethod);

        // Convert to std::string
        const char *keyChars = env->GetStringUTFChars(keyStr, nullptr);
        const char *valueChars = env->GetStringUTFChars(valueStr, nullptr);

        result[std::string(keyChars)] = std::string(valueChars);

        // Release resources
        env->ReleaseStringUTFChars(keyStr, keyChars);
        env->ReleaseStringUTFChars(valueStr, valueChars);
        env->DeleteLocalRef(entry);
        env->DeleteLocalRef(keyStr);
        env->DeleteLocalRef(valueStr);
    }

    // Clean up
    env->DeleteLocalRef(iterator);
    env->DeleteLocalRef(iteratorClass);
    env->DeleteLocalRef(entrySet);
    env->DeleteLocalRef(setClass);
    env->DeleteLocalRef(mapClass);
    env->DeleteLocalRef(entryClass);
    env->DeleteLocalRef(clazz);
    env->DeleteLocalRef(jMap);
    return result;
}

FusionConfig fusion_parse_config(JNIEnv *env, jobject jFusionConfig)
{
    FusionConfig config{};

    jclass configClass = find_class_in_app_classloader(env,
                                                       "dev/allofus/fusioncore/tools/FusionConfig");
    if (!configClass)
    {
        log(LogLevel::ERROR, TAG, "Failed to find FusionConfig class!");
        config.initialized = false;
        config.useOriginalLibUnity = true;
        return config;
    }
    GET_JBOOLEAN_FIELD(isIl2Cpp2Mono);
    GET_JBOOLEAN_FIELD(useOriginalLibUnity);
    GET_JSTRING_FIELD(gameLibraryDirectory);
    GET_JSTRING_FIELD(appLibraryDirectory);
    GET_JSTRING_FIELD(appDataDirectory);
    GET_JSTRING_FIELD(codeCacheDirectory);
    GET_JSTRING_FIELD(bepInExDirectory);
    GET_JSTRING_FIELD(dotnetDirectory);
    GET_JSTRING_FIELD(unityDataDirectory);
    GET_JSTRING_FIELD(unityVersion);
    config.fusionVariables = get_string_array_field(env, jFusionConfig, "fusionVariables");
    config.auxiliaryPluginFolders = get_string_array_field(env, jFusionConfig,
                                                           "auxiliaryPluginFolders");
    config.il2cppApiMap = get_string_map_field(env, jFusionConfig, "il2cppApiMap");
    config.initialized = true;

    return config;
}

void fusion_print_config(const FusionConfig &config)
{
    log_format(LogLevel::DEBUG, TAG, "Use Original libunity.so: {}", config.useOriginalLibUnity);
    log_format(LogLevel::DEBUG, TAG, "Game Library Directory: {}", config.gameLibraryDirectory);
    log_format(LogLevel::DEBUG, TAG, "App Library Directory: {}", config.appLibraryDirectory);
    log_format(LogLevel::DEBUG, TAG, "App Data Directory: {}", config.appDataDirectory);
    log_format(LogLevel::DEBUG, TAG, "Code Cache Directory: {}", config.codeCacheDirectory);
    log_format(LogLevel::DEBUG, TAG, "BepInEx Path: {}", config.bepInExDirectory);
    log_format(LogLevel::DEBUG, TAG, "Dotnet Path: {}", config.dotnetDirectory);
    log_format(LogLevel::DEBUG, TAG, "Unity Data Directory: {}", config.unityDataDirectory);
    log_format(LogLevel::DEBUG, TAG, "Unity Version: {}", config.unityVersion);
}