// Copyright (c) 2026 XtraCube
#include <exports.h>
#include <android/log.h>
#include <hooking/safehook.h>
#include <logger.h>
#include <utilities/java.h>

void init_bridge_helper(const char *libraryPath)
{
    safehook_setup_bridge_helper(libraryPath);
}

dobby_dummy_func_t hook(void *address, dobby_dummy_func_t replace_delegate, bool specialReturnBuffer)
{
    return safehook_create_hook(address, replace_delegate, specialReturnBuffer);
}

void unhook(void *target)
{
    safehook_destroy_hook(target);
}

void create_alert(const char *title, const char *message)
{

}

void set_loader_stage(uint8_t stage)
{
    setLoadingState(stage < 2);
}

void set_loader_message(const char *text)
{
    setLoadingText(text);
}

void write_log(const char *text)
{
    log(LogLevel::INFO, "Fusion.NET", text);
}

void write_log_level(int level, const char *text)
{
    LogLevel logLevel = static_cast<LogLevel>(level);
    log(logLevel, "Fusion.NET", text);
}