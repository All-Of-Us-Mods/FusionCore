// Copyright (c) 2026 XtraCube
#include <hooking/il2cpp.h>
#include <external/dobby.h>
#include <utilities/asm.h>
#include <logger.h>
#include <dlfcn.h>

#define TAG "FusionIL2CPP"

static void *handle = nullptr;

static void *p_il2cpp_init;
static void *p_il2cpp_runtime_invoke;

static il2cpp_method_get_name_t     fun_il2cpp_method_get_name = nullptr;
static il2cpp_init_t                fun_il2cpp_init = nullptr;
static il2cpp_runtime_invoke_t      fun_il2cpp_runtime_invoke = nullptr;

static il2cpp_init_t            init_hook = nullptr;
static il2cpp_runtime_invoke_t  runtime_invoke_hook = nullptr;

bool il2cpp_initialize(const char *library_path)
{
    handle = dlopen(library_path, RTLD_GLOBAL | RTLD_NOW);
    if (!handle)
    {
        char *err = dlerror();
        log_format(LogLevel::FATAL, TAG, "Failed to open libil2cpp.so: {}", err);
        return false;
    }

    p_il2cpp_init = dlsym(handle, "il2cpp_init");
    if (!p_il2cpp_init)
    {
        char *err = dlerror();
        log_format(LogLevel::FATAL, TAG, "Failed to find il2cpp_init: {}", err);
        return false;
    }
    fun_il2cpp_init = reinterpret_cast<il2cpp_init_t>(p_il2cpp_init);

    p_il2cpp_runtime_invoke = dlsym(handle, "il2cpp_runtime_invoke");
    if (!p_il2cpp_runtime_invoke)
    {
        char *err = dlerror();
        log_format(LogLevel::FATAL, TAG, "Failed to find il2cpp_runtime_invoke: {}", err);
        return false;
    }
    fun_il2cpp_runtime_invoke = reinterpret_cast<il2cpp_runtime_invoke_t>(p_il2cpp_runtime_invoke);

    fun_il2cpp_method_get_name = reinterpret_cast<il2cpp_method_get_name_t>(dlsym(handle, "il2cpp_method_get_name"));
    if (!fun_il2cpp_method_get_name)
    {
        char *err = dlerror();
        log_format(LogLevel::FATAL, TAG, "Failed to find il2cpp_method_get_name: {}", err);
        return false;
    }

    log(LogLevel::INFO, TAG, "Successfully loaded libil2cpp.so");
    return true;
}

const char *il2cpp_method_get_name(void *method)
{
    if (!fun_il2cpp_method_get_name)
    {
        log(LogLevel::ERROR, TAG, "func_il2cpp_method_get_name is null!");
        return "";
    }

    return fun_il2cpp_method_get_name(method);
}

// wrapper for il2cpp_init. if hooked, this will
// call the original function
int il2cpp_init(char *domain_name)
{
    if (!fun_il2cpp_init)
    {
        log(LogLevel::ERROR, TAG, "fun_il2cpp_init is null!");
        return -1;
    }

    return fun_il2cpp_init(domain_name);
}

// wrapper for il2cpp_runtime_invoke. if hooked, this
// will call the original function
void *il2cpp_runtime_invoke(void *method, void *obj, void **params, void **exc)
{
    if (!fun_il2cpp_runtime_invoke)
    {
        log(LogLevel::ERROR, TAG, "fun_il2cpp_runtime_invoke is null!");
        return nullptr;
    }

    return fun_il2cpp_runtime_invoke(method, obj, params, exc);
}

// installs a hook on il2cpp_init
void install_init_hook(il2cpp_init_t hook)
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

    int result = DobbyHook(
            p_il2cpp_init,
            (dobby_dummy_func_t)init_hook,
            (dobby_dummy_func_t *)&fun_il2cpp_init);

    if (result != 0)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to hook il2cpp_init: {:d}", result);
        return;
    }

    log(LogLevel::INFO, TAG, "Successfully hooked il2cpp_init");
}

// destroy the il2cpp_init hook if it exists
void destroy_init_hook();

// installs a hook on il2cpp_runtime_invoke
void install_runtime_invoke_hook(il2cpp_runtime_invoke_t hook);

// destroy the runtime_invoke hook if it exists
void destroy_runtime_invoke_hook();