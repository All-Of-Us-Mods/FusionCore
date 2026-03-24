// Copyright (c) 2026 XtraCube
#ifndef FUSION_FUSION_CONFIG_H
#define FUSION_FUSION_CONFIG_H
#include <string>
#include <jni.h>

struct FusionConfig {
    std::string gameLibraryDirectory;
    std::string appLibraryDirectory;
    bool useOriginalLibUnity;
};

FusionConfig parseFusionConfig(JNIEnv *env, jobject jFusionConfig);

#endif //FUSION_FUSION_CONFIG_H
