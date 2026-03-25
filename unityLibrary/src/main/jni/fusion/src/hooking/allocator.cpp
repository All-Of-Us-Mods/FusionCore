// Copyright (c) 2026 XtraCube
#include <hooking/allocator.h>
#include <utilities/library.h>
#include <utilities/asm.h>
#include <logger.h>

#define TAG "Allocator"

static PaddedOpenResult padded_open;

static size_t pool_pointer = 0;

void *allocate_setup_injected(const char *library, const char *output_path, size_t pool_size)
{
    padded_open = padded_dlopen(library, output_path, pool_size);
    return padded_open.handle;
}

void *allocate_injected(void *target, void *library_base, size_t size)
{
    uintptr_t target_ptr = reinterpret_cast<uintptr_t >(target);
    uintptr_t tramp_ptr = reinterpret_cast<uintptr_t>(padded_open.pool_base + pool_pointer);

    if (pool_pointer + size > padded_open.pool_size)
    {
        log(LogLevel::ERROR, TAG, "Trampoline pool is full!");
        return nullptr;
    }

    uintptr_t dist = tramp_ptr - target_ptr;

    if (dist > 0x7FFFFFF)
    {
        log_format(LogLevel::ERROR, TAG, "Target 0x{:x} too far from trampoline space 0x{:x}!",
                   target_ptr, tramp_ptr);
        return nullptr;
    }

    pool_pointer += size;
    return reinterpret_cast<void *>(tramp_ptr);
}