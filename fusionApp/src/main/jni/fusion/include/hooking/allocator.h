// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_ALLOCATOR_H
#define FUSIONCORE_ALLOCATOR_H
#include <hooking/safehook.h>

// sets up the injected allocator using a given library and pool size
// returns the dlopen handle to the library
void *allocate_setup_injected(const char *library, const char *output_path, size_t pool_size);

void *allocate_injected(void *target, void *library_base, size_t size);

uintptr_t get_injected_pool_base();

#endif //FUSIONCORE_ALLOCATOR_H
