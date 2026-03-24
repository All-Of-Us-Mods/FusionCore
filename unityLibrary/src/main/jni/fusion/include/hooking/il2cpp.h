// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_IL2CPP_H
#define FUSIONCORE_IL2CPP_H

using il2cpp_init_t = int(*)(char *domain_name);
using il2cpp_runtime_invoke_t = void *(*)(void *method, void *obj, void **params, void **exc);
using il2cpp_method_get_name_t = const char *(*)(void *method);

// loads libil2cpp.so and sets up function pointers
bool il2cpp_initialize(const char *library_path);

// wrapper for il2cpp_method_get_name
const char *il2cpp_method_get_name(void *method);

// wrapper for il2cpp_init. if hooked, this will
// call the original function
int il2cpp_init(char *domain_name);

// wrapper for il2cpp_runtime_invoke. if hooked, this
// will call the original function
void *il2cpp_runtime_invoke(void *method, void *obj, void **params, void **exc);

// installs a hook on il2cpp_init
void install_init_hook(il2cpp_init_t hook);

// destroy the il2cpp_init hook if it exists
void destroy_init_hook();

// installs a hook on il2cpp_runtime_invoke
void install_runtime_invoke_hook(il2cpp_runtime_invoke_t hook);

// destroy the runtime_invoke hook if it exists
void destroy_runtime_invoke_hook();

#endif //FUSIONCORE_IL2CPP_H
