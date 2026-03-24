// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_LOGGER_H
#define FUSIONCORE_LOGGER_H
#include <string>
#include <format>

enum class LogLevel : int
{
    UNKNOWN = 0,
    DEBUG = 3,
    INFO = 4,
    WARN = 5,
    ERROR = 6,
    FATAL = 7,
};


void log(LogLevel level, const char *tag, const char *message);

template<typename... Args>
void log_format(LogLevel level, const char *tag, std::format_string<Args...> fmt, Args &&... args)
{
    log(level, tag, std::format(fmt, std::forward<Args>(args)...).c_str());
}

#endif //FUSIONCORE_LOGGER_H
