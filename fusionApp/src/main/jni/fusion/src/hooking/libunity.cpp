// Copyright (c) 2026 XtraCube
#include <hooking/libunity.h>
#include <dlfcn.h>
#include <external/dobby.h>
#include <logger.h>
#include <utilities/elf.h>
#include <utilities/library.h>
#include <filesystem>
#include <fstream>

#define TAG "LibUnityHook"

namespace fs = std::filesystem;

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
        return;
    }

    fs::path libunity_path(libUnityPath);
    fs::path sym_path = libunity_path.replace_extension("sym.so");
    if (!fs::exists(libunity_path) || !fs::exists(sym_path))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to find libunity or libunity.sym.so at {}", libUnityPath.c_str());
        return;
    }

    log_format(LogLevel::INFO, TAG, "Found libunity at {}", libUnityPath.c_str());
    log_format(LogLevel::INFO, TAG, "Found libunity.sym.so at {}", sym_path.c_str());

    uintptr_t rva = get_rva_from_sym_file(sym_path.c_str(),
                                          "_Z23scripting_method_invoke18ScriptingMethodPtr18ScriptingObjectPtrR18ScriptingArgumentsP21ScriptingExceptionPtrb");
    if (rva == 0)
    {
        log(LogLevel::ERROR, TAG, "Failed to find scripting_method_invoke in libunity.sym.so");
        return;
    }

    uintptr_t base = get_module_base(libUnityPath.c_str(), "JNI_OnLoad");
    if (base == 0) {
        log(LogLevel::ERROR, TAG, "Failed to find base address of libunity");
        return;
    }

    void *target = reinterpret_cast<void *>(base + rva);
    if (!target)
    {
        log_format(LogLevel::ERROR, TAG,
            "Failed to find target function for scripting_method_invoke_hook: ", dlerror());

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