// Copyright (c) 2026 XtraCube
#include <hooking/safehook.h>
#include <external/dobby.h>
#include <utilities/asm.h>
#include <logger.h>
#include <dlfcn.h>
#include <sys/mman.h>

#define TAG "SafeHook"

bool safehook_initialize(void *lib_handle, void *lib_base, allocate_func allocator_func)
{
    if (!lib_handle)
    {
        log(LogLevel::FATAL, TAG, "Failed to initialize SafeHook, lib_handle is null!");
        return false;
    }

    if (!lib_base)
    {
        log(LogLevel::FATAL, TAG, "Failed to initialize SafeHook, lib_base is null!");
        return false;
    }

    library_handle = lib_handle;
    library_base = lib_base;
    allocator = allocator_func;

    log_format(LogLevel::INFO, TAG, "SafeHook initialized using base 0x{:X}",
               reinterpret_cast<uintptr_t>(library_base));
    return true;
}

bool safehook_setup_bridge_helper(const char *bridge_library_path)
{
    if (!bridge_library_path)
    {
        log(LogLevel::ERROR, TAG, "Failed to setup bridge helper, null path!");
        return false;
    }

    void *bridge_handle = dlopen(bridge_library_path, RTLD_GLOBAL | RTLD_NOW);
    if (!bridge_handle)
    {
        char *error = dlerror();
        log_format(LogLevel::ERROR, TAG, "Failed to setup bridge helper, dlopen failed: {}", error);
        return false;
    }

    bridge_function = dlsym(bridge_handle, "ReturnBufferBridge");
    if (!bridge_function)
    {
        char *error = dlerror();
        log_format(LogLevel::ERROR, TAG, "Failed to find bridge function, dlsym failed: {}", error);
        return false;
    }

    log_format(LogLevel::INFO, TAG, "Initialized bridge helper with bridge at 0x{:X}",
               reinterpret_cast<uintptr_t>(bridge_function));
    return true;
}

void *dobby_hook(void *target, void *hook, bool use_near_branch = false)
{
    if (!target || !hook)
    {
        log_format(LogLevel::ERROR, TAG, "Invalid parameters! Target: 0x{:X} Hook: 0x{:X}",
                   reinterpret_cast<uintptr_t>(target), reinterpret_cast<uintptr_t>(hook)
        );
        return nullptr;
    }

    void *original = nullptr;

    if (use_near_branch) dobby_enable_near_branch_trampoline();
    else dobby_disable_near_branch_trampoline();

    int error = DobbyHook(target, hook, &original);
    if (error != 0)
    {
        dobby_disable_near_branch_trampoline();
        void *offset = reinterpret_cast<void *>(
                reinterpret_cast<uintptr_t>(target) -
                reinterpret_cast<uintptr_t>(library_base)
        );
        log_format(LogLevel::ERROR, TAG, "Failed to hook target at offset 0x{:X}, error {}",
                   reinterpret_cast<uintptr_t>(offset), error);
        return nullptr;
    }

    dobby_disable_near_branch_trampoline();
    return original;
}


bool protect_trampoline(void *trampoline, size_t size, int protection)
{
    auto start = reinterpret_cast<uintptr_t>(trampoline);
    auto start_page = align_down(start, page_size);
    auto end = start + size - 1;
    auto last_page = align_down(end, page_size);

    if (mprotect(reinterpret_cast<void *>(start_page), page_size, protection) != 0)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to set memory protection for writing: {}",
                                      strerror(errno));
        return false;
    }

    if (start_page != last_page)
    {
        log(LogLevel::DEBUG, TAG, "Trampoline spans multiple pages, adjusting memory protection for second page.");
        if (mprotect(reinterpret_cast<void *>(last_page), page_size, protection) != 0)
        {
            log_format(LogLevel::ERROR, TAG, "Failed to set memory protection for writing: {}", strerror(errno));
            return false;
        }
    }

    return true;
}

void *safehook_create_hook(void *target_function, void *hook_function, bool use_bridge)
{
    if (!target_function || !hook_function)
    {
        log_format(LogLevel::ERROR, TAG, "Invalid parameters! Target: 0x{:X} Hook: 0x{:X}",
                   reinterpret_cast<uintptr_t>(target_function),
                   reinterpret_cast<uintptr_t>(hook_function)
        );
        return nullptr;
    }

    // offset within libil2cpp.so
    uintptr_t rva = reinterpret_cast<uintptr_t>(target_function) -
                    reinterpret_cast<uintptr_t>(library_base);

    // distance from hook function
    uintptr_t distance = reinterpret_cast<uintptr_t>(hook_function) -
                         reinterpret_cast<uintptr_t>(target_function);

    // check if hook is close enough for a near branch.
    uintptr_t limit = 0x7FFFFFFF;
    bool withinLimits = (distance > 0 && distance < limit) ||
                        (distance < 0 && distance > -limit);

    // TODO: add game patcher here

    void *actual_hook = hook_function;

    if (use_bridge)
    {
        // TODO: implement bridge hooking
    }

    // TODO: better check if function is actually inside libil2cpp.so
    if (rva < 0)
    {
        // function does not belong to libil2cpp.so
        return dobby_hook(target_function, actual_hook, withinLimits);
    }

    if (withinLimits)
    {
        // hook is close enough for a near branch.
        return dobby_hook(target_function, actual_hook, true);
    }

    if (!is_small_function(target_function))
    {
        log_format(LogLevel::DEBUG, TAG,
                          "Target at offset 0x{:X} is long enough, using Dobby directly.", rva);
        return dobby_hook(target_function, actual_hook);
    }
    else
    {
        log_format(LogLevel::DEBUG, TAG,
                   "Target at offset 0x{:X} is too short for a near branch.", rva);

        if (!allocator)
        {
            log(LogLevel::DEBUG, TAG, "Allocator is null, using Dobby with auto near branch.");
            return dobby_hook(target_function, actual_hook, true);
        }

        void *trampoline = allocator(target_function, trampoline_size);
        if (!trampoline)
        {
            log(LogLevel::ERROR, TAG, "Failed to allocate trampoline. Using Dobby as a fallback.");
            return dobby_hook(target_function, actual_hook, true);
        }

        if (!protect_trampoline(trampoline, trampoline_size, PROT_READ | PROT_WRITE))
        {
            log(LogLevel::ERROR, TAG, "Failed to set memory protection for trampoline! Using Dobby as a fallback.");
            return dobby_hook(target_function, actual_hook, true);
        }

        log_format(LogLevel::DEBUG, TAG,
                          "Emitting absolute jump from trampoline at 0x{:X} to hook at 0x{:X}",
                          reinterpret_cast<uintptr_t>(trampoline), reinterpret_cast<uintptr_t>(actual_hook));

        log_format(LogLevel::DEBUG, TAG, "Trampoline offset from library base: 0x{:X}",
                          reinterpret_cast<uintptr_t>(trampoline) -
                          reinterpret_cast<uintptr_t>(library_base));

        bool success = emit_absolute_jump(trampoline, actual_hook);
        if (!success)
        {
            log(LogLevel::ERROR, TAG, "Failed to write trampoline! Using Dobby as a fallback.");
            return dobby_hook(target_function, actual_hook, true);
        }

        // we need to clear instruction cache to make sure our hook works.
        auto start = reinterpret_cast<uintptr_t>(trampoline);
        __builtin___clear_cache(reinterpret_cast<char *>(start),
                                reinterpret_cast<char *>(start + trampoline_size));

        if (!protect_trampoline(trampoline, trampoline_size, PROT_READ | PROT_EXEC))
        {
            log(LogLevel::ERROR, TAG, "Failed to set memory protection for trampoline! Using Dobby as a fallback.");
            return dobby_hook(target_function, actual_hook, true);
        }

        // Hook the target
        auto result = dobby_hook(target_function, trampoline, true);
        return result;
    }
}


void safehook_destroy_hook(void *target)
{
    void *offset = reinterpret_cast<void *>(
            reinterpret_cast<uintptr_t>(target) -
            reinterpret_cast<uintptr_t>(library_base)
    );

    if (DobbyDestroy(target) != 0)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to unhook target at offset 0x{:X}",
                          reinterpret_cast<uintptr_t>(offset));
    } else
    {
        log_format(LogLevel::INFO, TAG, "Successfully unhooked target at offset 0x{:X}",
                          reinterpret_cast<uintptr_t>(offset));
    }
}