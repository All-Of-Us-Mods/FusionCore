// Copyright (c) 2026 XtraCube
#ifndef FUSIONCORE_LIBRARY_H
#define FUSIONCORE_LIBRARY_H

#include <elf.h>
#include <unistd.h>

struct PaddedOpenResult
{
    void *handle;
    void *base;
    size_t pool_base;
    size_t pool_size;
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

uintptr_t get_module_base(const char* lib_name, const char* known_export_symbol);

#endif //FUSIONCORE_LIBRARY_H
