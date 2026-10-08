// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_LIBRARY_H
#define FUSIONCORE_LIBRARY_H

#include <elf.h>
#include <unistd.h>
#include <cstdint>

// Result of padded_dlopen. On failure every field is null/zero.
struct PaddedOpenResult
{
    void *handle;        // dlopen handle of the padded library
    void *load_bias;     // loader bias (dlpi_addr) of the loaded object
    uintptr_t pool_base; // absolute address of the trampoline pool
    size_t pool_size;    // usable size of the trampoline pool (bytes)
};

#if defined(__aarch64__)
using Elf_Ehdr = Elf64_Ehdr;
using Elf_Phdr = Elf64_Phdr;
using Elf_Addr = Elf64_Addr;
using Elf_Xword = Elf64_Xword;
#elif defined(__arm__)
using Elf_Ehdr = Elf32_Ehdr;
using Elf_Phdr = Elf32_Phdr;
using Elf_Addr = Elf32_Addr;
using Elf_Xword = Elf32_Xword;
#endif

PaddedOpenResult padded_dlopen(const char *library_name,
                               const char *temp_path,
                               size_t pool_size);

uintptr_t get_module_base(const char* lib_name);

#endif //FUSIONCORE_LIBRARY_H
