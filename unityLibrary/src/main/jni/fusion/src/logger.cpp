// Copyright (c) XtraCube 2026

#include <logger.h>
#include <android/log.h>

void log(LogLevel level, const char *tag, const char *message)
{
    __android_log_write(static_cast<int>(level), tag, message);
}
