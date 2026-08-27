// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_JAVA_H
#define FUSIONCORE_JAVA_H
#include <jni.h>

#define GET_JAVA_STRING(env, javaString, assignment) \
    do { \
        const char *chars = (env)->GetStringUTFChars(javaString, nullptr); \
        assignment = std::string(chars); \
        (env)->ReleaseStringUTFChars(javaString, chars); \
    } while(0)


JNIEnv* getJNIEnv();

jint getStaticResourceId(JNIEnv* env, const char *name);

jobject getUnityActivity(JNIEnv* env);

void setLoadingState(bool enabled);

void setLoadingText(const char *text);

#endif //FUSIONCORE_JAVA_H
