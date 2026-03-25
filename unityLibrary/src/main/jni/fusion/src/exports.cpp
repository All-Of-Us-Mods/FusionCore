// Copyright (c) 2026 XtraCube
#include <android/log.h>
#include <exports.h>
#include <hooking/safehook.h>
#include <logger.h>

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

void write_log(const char *text)
{
    log(LogLevel::INFO, "Fusion.NET", text);
}