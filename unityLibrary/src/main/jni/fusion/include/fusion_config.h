// Copyright (c) 2026 XtraCube
#ifndef FUSION_FUSION_CONFIG_H
#define FUSION_FUSION_CONFIG_H
#include <string>
#include <jni.h>

struct FusionConfig {
    bool initialized;
    bool useOriginalLibUnity;
    std::string gameLibraryDirectory;
    std::string appLibraryDirectory;
    std::string gameDataDirectory;
    std::string appDataDirectory;
    std::string bepInExDirectory;
    std::string dotnetDirectory;
};

FusionConfig fusion_parse_config(JNIEnv *env, jobject jFusionConfig);

void fusion_print_config(const FusionConfig &config);

#endif //FUSION_FUSION_CONFIG_H
