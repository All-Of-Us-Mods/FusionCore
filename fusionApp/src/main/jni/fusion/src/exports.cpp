// Copyright (c) 2026 XtraCube
#include <exports.h>
#include <android/log.h>
#include <hooking/safehook.h>
#include <logger.h>
#include <utilities/java.h>
#include <fusion_config.h>

const char *get_il2cpp_api(const char *name)
{
    if (!runtime_config.initialized) return name;

    auto it = runtime_config.il2cppApiMap.find(name);

    if (it == runtime_config.il2cppApiMap.end() || it->second.empty()) {
        return name;
    }

    return it->second.c_str();
}

void init_bridge_helper(const char *libraryPath)
{
    if (!libraryPath) {
        log(LogLevel::ERROR, "BridgeHelper", "Cannot init bridge helper, null lib path!");
    }
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
    if (!text) {
        return;
    }
    setLoadingText(text);
}

void write_log(const char *text)
{
    if (!text) {
        return;
    }
    log(LogLevel::INFO, "Fusion.NET", text);
}

void write_log_level(int level, const char *text)
{
    if (!text) {
        return;
    }
    LogLevel logLevel = static_cast<LogLevel>(level);
    log(logLevel, "Fusion.NET", text);
}

int8_t get_low_memory_mode()
{
    // TODO: add configuration
    return 1;
}