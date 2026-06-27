// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_EXPORTS_H
#define FUSIONCORE_EXPORTS_H
#include <external/dobby.h>

#ifdef __cplusplus
extern "C"
{
#endif

void init_bridge_helper(const char *libraryPath);

dobby_dummy_func_t hook(void *address, dobby_dummy_func_t replace_delegate, bool specialReturnBuffer);

void unhook(void *target);

void create_alert(const char *title, const char *message);

void write_log(const char *text);

void write_log_level(int level, const char *text);

#ifdef __cplusplus
}
#endif

#endif //FUSIONCORE_EXPORTS_H
