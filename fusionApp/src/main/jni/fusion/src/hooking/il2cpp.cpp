// Copyright (c) 2026 XtraCube
#include <hooking/il2cpp.h>
#include <external/dobby.h>
#include <utilities/asm.h>
#include <utilities/tools.h>
#include <logger.h>
#include <dlfcn.h>
#include <exports.h>

#define TAG "FusionIL2CPP"

static uintptr_t library_base = 0;

static void *p_il2cpp_init;

static il2cpp_init_t fun_il2cpp_init = nullptr;
static il2cpp_init_t init_hook = nullptr;

#if defined(__aarch64__)
static void disable_export_caller_check(void *export_function)
{
    auto *code = static_cast<const uint32_t *>(export_function);
    uintptr_t regs[32] = {};
    uintptr_t state = 0;
    bool loads_ranges = false;
    bool compares_lr = false;

    for (int i = 0; i < 48 && !is_ret(code[i]); i++)
    {
        uint32_t ins = code[i];
        auto pc = reinterpret_cast<uintptr_t>(&code[i]);

        if ((ins & 0x9F000000) == 0x90000000) // adrp
        {
            int64_t imm = ((ins >> 29) & 0x3) | ((static_cast<int64_t>(ins >> 5) & 0x7FFFF) << 2);
            imm = (imm << 43) >> 43;
            regs[ins & 0x1F] = (pc & ~static_cast<uintptr_t>(0xFFF)) + (imm << 12);
        }
        else if ((ins & 0xFFC00000) == 0x91000000) // add xd, xn, #imm
        {
            uintptr_t value = regs[(ins >> 5) & 0x1F] + ((ins >> 10) & 0xFFF);
            regs[ins & 0x1F] = value;
            if (state && value == state + 0x8) loads_ranges = true;
        }
        else if ((ins & 0xFFFFFC00) == 0xC8DFFC00) // ldar xt, [xn]
        {
            if (!state) state = regs[(ins >> 5) & 0x1F];
        }
        else if ((ins & 0xFFFFFC1F) == 0xEB1E001F) // cmp xn, x30
        {
            compares_lr = true;
        }
    }

    if (!state || !loads_ranges || !compares_lr)
    {
        log(LogLevel::DEBUG, TAG, "No export caller check found");
        return;
    }

    auto *values = reinterpret_cast<uintptr_t *>(state);
    values[1] = 0;
    values[2] = UINTPTR_MAX;
    values[3] = 0;
    values[4] = UINTPTR_MAX;
    __atomic_store_n(&values[0], ~static_cast<uintptr_t>(0), __ATOMIC_RELEASE);
    log_format(LogLevel::INFO, TAG, "Disabled export caller check at 0x{:X}", state);
}
#endif

bool il2cpp_initialize(const char *library_path)
{
    handle = dlopen(library_path, RTLD_GLOBAL | RTLD_NOW);
    if (!handle)
    {
        log_format(LogLevel::FATAL, TAG, "Failed to open libil2cpp.so: {}", safe_dlerror());
        return false;
    }

    const char *mapped_init_name = get_il2cpp_api("il2cpp_init");
    const char *init_name = mapped_init_name ? mapped_init_name : "il2cpp_init";

    p_il2cpp_init = dlsym(handle, init_name);
    if (!p_il2cpp_init)
    {
        log_format(LogLevel::FATAL, TAG, "Failed to find il2cpp_init: {}", safe_dlerror());
        return false;
    }
    fun_il2cpp_init = reinterpret_cast<il2cpp_init_t>(p_il2cpp_init);

#if defined(__aarch64__)
    if (mapped_init_name)
    {
        disable_export_caller_check(p_il2cpp_init);
    }
#endif

    log(LogLevel::INFO, TAG, "Successfully loaded libil2cpp.so");
    return true;
}

void *il2cpp_get_handle()
{
    if (!handle)
    {
        log(LogLevel::ERROR, TAG, "il2cpp is not initialized!");
        return nullptr;
    }

    return handle;
}

uintptr_t il2cpp_get_library_base()
{
    if (!handle)
    {
        log(LogLevel::ERROR, TAG, "il2cpp is not initialized!");
        return 0;
    }

    if (library_base != 0)
    {
        return library_base;
    }

    Dl_info info;
    if (dladdr(p_il2cpp_init, &info) == 0)
    {
        log(LogLevel::ERROR, TAG, "Failed to get library base!");
        return 0;
    }

    library_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    return library_base;
}

// wrapper for il2cpp_init. if hooked, this will
// call the original function
int il2cpp_init(char *domain_name)
{
    static bool called = false;

    if (!fun_il2cpp_init)
    {
        log(LogLevel::ERROR, TAG, "fun_il2cpp_init is null!");
        return -1;
    }

    if (called)
    {
        log(LogLevel::ERROR, TAG, "il2cpp_init has already been called!");
        return -1;
    }

    called = true;
    return fun_il2cpp_init(domain_name);
}

// installs a hook on il2cpp_init
void il2cpp_install_init_hook(il2cpp_init_t hook)
{
    if (!p_il2cpp_init)
    {
        log(LogLevel::ERROR, TAG, "il2cpp_init is not initialized!");
        return;
    }

    if (!hook)
    {
        log(LogLevel::ERROR, TAG, "Hook function is null!");
        return;
    }

    init_hook = hook;

    dobby_disable_near_branch_trampoline();
    int result = DobbyHook(
            p_il2cpp_init,
            (dobby_dummy_func_t)init_hook,
            (dobby_dummy_func_t *)&fun_il2cpp_init);

    if (result != 0)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to hook il2cpp_init: {:d}", result);
        return;
    }

    log(LogLevel::DEBUG, TAG, "Successfully hooked il2cpp_init");
}

// destroy the il2cpp_init hook if it exists
void il2cpp_destroy_init_hook()
{
    if (!init_hook)
    {
        log(LogLevel::ERROR, TAG, "il2cpp_init hook is not installed!");
        return;
    }

    // restore the original function
    DobbyDestroy(p_il2cpp_init);
    init_hook = nullptr;
    fun_il2cpp_init = reinterpret_cast<il2cpp_init_t>(p_il2cpp_init);

    log(LogLevel::DEBUG, TAG, "Successfully destroyed il2cpp_init hook");
}