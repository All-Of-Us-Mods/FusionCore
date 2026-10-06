// Copyright (c) 2026 XtraCube
#include <hooking/allocator.h>
#include <utilities/library.h>
#include <utilities/asm.h>
#include <logger.h>

#define TAG "Allocator"

static PaddedOpenResult padded_open;

static size_t pool_pointer = 0;

uintptr_t get_injected_pool_base()
{
    return padded_open.pool_base;
}

void *allocate_setup_injected(const char *library, const char *output_path, size_t pool_size)
{
    padded_open = padded_dlopen(library, output_path, pool_size);
    return padded_open.handle;
}

void *allocate_injected(void *target, void *library_base, size_t size)
{
    (void) library_base;

    if (!padded_open.handle || padded_open.pool_base == 0 || padded_open.pool_size == 0)
    {
        log(LogLevel::ERROR, TAG, "Injected trampoline pool is not initialized!");
        return nullptr;
    }

    if (size == 0)
    {
        log(LogLevel::ERROR, TAG, "Trampoline allocation size is zero!");
        return nullptr;
    }

    // keep trampolines naturally 4-byte aligned (instruction alignment on ARM/ARM64)
    const size_t offset = (pool_pointer + 3) & ~static_cast<size_t>(3);

    // overflow-safe bounds check: never write past the end of the pool
    if (offset > padded_open.pool_size || size > padded_open.pool_size - offset)
    {
        log(LogLevel::ERROR, TAG, "Trampoline pool is full!");
        return nullptr;
    }

    const uintptr_t target_ptr = reinterpret_cast<uintptr_t>(target);
    const uintptr_t tramp_ptr = padded_open.pool_base + offset;

    // signed distance check: a near branch only reaches +/-128MB in either direction
    const auto dist = static_cast<int64_t>(tramp_ptr) - static_cast<int64_t>(target_ptr);
    if (dist < -static_cast<int64_t>(0x8000000) || dist > static_cast<int64_t>(0x7FFFFFF))
    {
        log_format(LogLevel::ERROR, TAG, "Target 0x{:x} too far from trampoline space 0x{:x}!",
                   target_ptr, tramp_ptr);
        return nullptr;
    }

    pool_pointer = offset + size;
    return reinterpret_cast<void *>(tramp_ptr);
}