// Copyright (c) 2026 XtraCube
#include <fusion_config.h>
#include <utilities/java.h>

FusionConfig parseFusionConfig(JNIEnv *env, jobject jFusionConfig)
{
    FusionConfig config;

    jclass configClass = env->GetObjectClass(jFusionConfig);

    jstring gameLibsJString = (jstring) env->GetObjectField(jFusionConfig,
                                                            env->GetFieldID(configClass,
                                                                            "gameLibraryDirectory",
                                                                            "Ljava/lang/String;"));
    jstring appLibsJString = (jstring) env->GetObjectField(jFusionConfig,
                                                           env->GetFieldID(configClass,
                                                                           "appLibraryDirectory",
                                                                           "Ljava/lang/String;"));

    jboolean useOriginalLibUnityJBoolean = env->GetBooleanField(jFusionConfig,
                                                            env->GetFieldID(configClass,
                                                                            "useOriginalLibUnity",
                                                                            "Z"));

    config.useOriginalLibUnity = useOriginalLibUnityJBoolean == JNI_TRUE;
    GET_JAVA_STRING(gameLibsJString, config.gameLibraryDirectory)
    GET_JAVA_STRING(appLibsJString, config.appLibraryDirectory)

    return config;
}