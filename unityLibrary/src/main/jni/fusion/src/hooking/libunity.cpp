// Copyright (c) 2026 XtraCube
#include <hooking/libunity.h>
#include <dlfcn.h>
#include <external/xdl.h>
#include <external/dobby.h>
#include <logger.h>

#define TAG "LibUnityHook"

using scripting_method_invoke_fn = void* (*)(void* method, void* obj, void* args, void* exc, bool);
scripting_method_invoke_fn original_scripting_method_invoke = nullptr;

// this hook prevents crashes from unstripped libunity failing to resolve scripting methods
void* scripting_method_invoke_hook(void* method, void* obj, void* args, void* exc, bool something) {
    if (!method) {
        return nullptr;
    }

    return original_scripting_method_invoke(method, obj, args, exc, something);
}

void try_hook_libunity(std::string &libUnityPath, const std::string &fallbackLibUnityPath) {

    void *handle = dlopen(libUnityPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Failed to load libunity for hooking: {}. Error: {}",
                   libUnityPath.c_str(), dlerror());
    }
    struct dl_phdr_info unity_info = {0};
    dl_iterate_phdr(
            [](struct dl_phdr_info *info, size_t size, void *data) -> int {
                std::string_view libName(info->dlpi_name);
                if (libName.find("libunity.so") != std::string_view::npos)
                {
                    struct dl_phdr_info *unity_info = static_cast<struct dl_phdr_info *>(data);
                    *unity_info = *info;
                    return 1; // stop iteration
                }
                return 0; // continue iteration
            },
            &unity_info
    );

    void *libunity_handle = xdl_open2(&unity_info);
    if (!libunity_handle)
    {
        log_format(LogLevel::ERROR, TAG,
                   "Failed to xdl_open libunity for hooking: {}",
                   libUnityPath.c_str());
    }
    void *target = xdl_dsym(libunity_handle,
                            "_Z23scripting_method_invoke18ScriptingMethodPtr18ScriptingObjectPtrR18ScriptingArgumentsP21ScriptingExceptionPtrb",
                            nullptr
    );
    if (!target)
    {
        log(LogLevel::ERROR, TAG,
            "Failed to find target function for scripting_method_invoke_hook!");

        // reset libunity path
        libUnityPath = fallbackLibUnityPath;
    }
    else
    {
        if (
                DobbyHook(target,
                          reinterpret_cast<dobby_dummy_func_t>(scripting_method_invoke_hook),
                          reinterpret_cast<dobby_dummy_func_t *>(&original_scripting_method_invoke))
                == 0)
        {
            log(LogLevel::INFO, TAG, "Successfully hooked scripting_method_invoke");
        }
        else
        {
            log(LogLevel::ERROR, TAG, "Failed to hook scripting_method_invoke");
            // reset libunity path
            libUnityPath = fallbackLibUnityPath;
        }
    }
}