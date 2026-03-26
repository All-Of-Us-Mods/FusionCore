// Copyright (c) 2026 XtraCube
#include <fusion_config.h>
#include <utilities/java.h>
#include <logger.h>

#define TAG "FusionConfig"

#define GET_JSTRING_FIELD(fieldName) \
    jstring fieldName##JString = (jstring) env->GetObjectField(jFusionConfig, \
                                                            env->GetFieldID(configClass, \
                                                                            #fieldName, \
                                                                            "Ljava/lang/String;")); \
    GET_JAVA_STRING(env, fieldName##JString, config.fieldName)

#define GET_JBOOLEAN_FIELD(fieldName) \
    jboolean fieldName##JBoolean = env->GetBooleanField(jFusionConfig, \
                                                        env->GetFieldID(configClass, \
                                                                        #fieldName, \
                                                                        "Z")); \
    config.fieldName = (fieldName##JBoolean == JNI_TRUE)

FusionConfig fusion_parse_config(JNIEnv *env, jobject jFusionConfig)
{
    FusionConfig config;

    jclass configClass = env->GetObjectClass(jFusionConfig);

    GET_JBOOLEAN_FIELD(useOriginalLibUnity);

    GET_JSTRING_FIELD(gameLibraryDirectory);
    GET_JSTRING_FIELD(appLibraryDirectory);
    GET_JSTRING_FIELD(appDataDirectory);
    GET_JSTRING_FIELD(appInternalDataDirectory);
    GET_JSTRING_FIELD(bepInExDirectory);
    GET_JSTRING_FIELD(dotnetDirectory);
    GET_JSTRING_FIELD(unityDataDirectory);
    GET_JSTRING_FIELD(unityVersion);

    config.initialized = true;

    return config;
}

void fusion_print_config(const FusionConfig &config)
{
    log_format(LogLevel::DEBUG, TAG, "Use Original libunity.so: {}", config.useOriginalLibUnity);
    log_format(LogLevel::DEBUG, TAG, "Game Library Directory: {}", config.gameLibraryDirectory);
    log_format(LogLevel::DEBUG, TAG, "App Library Directory: {}", config.appLibraryDirectory);
    log_format(LogLevel::DEBUG, TAG, "App Data Directory: {}", config.appDataDirectory);
    log_format(LogLevel::DEBUG, TAG, "App Internal Data Directory: {}", config.appInternalDataDirectory);
    log_format(LogLevel::DEBUG, TAG, "BepInEx Path: {}", config.bepInExDirectory);
        log_format(LogLevel::DEBUG, TAG, "Dotnet Path: {}", config.dotnetDirectory);
}