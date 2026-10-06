// Copyright (c) 2026 XtraCube

#include <utilities/library.h>
#include <utilities/tools.h>
#include <dlfcn.h>
#include <link.h>
#include <unistd.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <vector>
#include <logger.h>

#define TAG "LibraryUtils"

namespace {

    struct BiasLookup
    {
        const char *base_name;
        uintptr_t bias;
        int matches;
    };

// dlpi_addr is the load bias the loader chose for that library.
// Keeps the first match; callers that need uniqueness check `matches`.
    int find_bias(struct dl_phdr_info *info, size_t, void *data)
    {
        auto *r = static_cast<BiasLookup *>(data);
        if (!info->dlpi_name || !*info->dlpi_name) return 0;

        const char *slash = strrchr(info->dlpi_name, '/');
        if (strcmp(slash ? slash + 1 : info->dlpi_name, r->base_name) == 0)
        {
            if (r->matches == 0) r->bias = static_cast<uintptr_t>(info->dlpi_addr);
            r->matches++;
        }
        return 0;
    }

} // namespace

// Returns the load address of an already-loaded library (matched by file name,
// e.g. "libil2cpp.so" or a full path), or 0 if it isn't loaded.
uintptr_t get_module_base(const char *lib_name)
{
    if (!lib_name) return 0;

    const char *slash = strrchr(lib_name, '/');
    BiasLookup lookup{slash ? slash + 1 : lib_name, 0, 0};
    dl_iterate_phdr(find_bias, &lookup);
    return lookup.matches > 0 ? lookup.bias : 0;
}

// Copies library_name to temp_path with its last PT_LOAD segment extended by a
// page-aligned, zero-filled (bss) pool, then dlopens the copy.
PaddedOpenResult padded_dlopen(const char *library_name,
                               const char *temp_path,
                               size_t pool_size)
{
    const PaddedOpenResult failure{nullptr, nullptr, 0, 0};

    const Elf_Addr page = static_cast<Elf_Addr>(sysconf(_SC_PAGESIZE));
    auto align_up = [page](Elf_Addr v) { return (v + page - 1) & ~(page - 1); };

    // read ELF + program headers
    std::ifstream in(library_name, std::ios::binary);
    Elf_Ehdr eh{};
    if (!in.read(reinterpret_cast<char *>(&eh), sizeof(eh)) ||
        memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to read ELF header of {}", library_name);
        return failure;
    }

    std::vector<Elf_Phdr> phdrs(eh.e_phnum);
    in.seekg(static_cast<std::streamoff>(eh.e_phoff));
    if (!in.read(reinterpret_cast<char *>(phdrs.data()), phdrs.size() * sizeof(Elf_Phdr)))
    {
        log_format(LogLevel::ERROR, TAG, "Failed to read program headers of {}", library_name);
        return failure;
    }

    // find the highest-ending PT_LOAD
    Elf_Phdr *last = nullptr;
    for (auto &ph : phdrs)
    {
        if (ph.p_type != PT_LOAD) continue;
        if (!last || ph.p_vaddr + ph.p_memsz > last->p_vaddr + last->p_memsz) last = &ph;
    }
    if (!last || !(last->p_flags & PF_W))
    {
        log_format(LogLevel::ERROR, TAG, "No writable last PT_LOAD in {}", library_name);
        return failure;
    }

    // pool = whole pages right after the segment; grow memsz to cover it
    const Elf_Addr pool_start = align_up(last->p_vaddr + last->p_memsz);
    const Elf_Addr pool_len = align_up(pool_size);
    last->p_memsz = pool_start + pool_len - last->p_vaddr;

    // write the patched copy: whole file, then overwrite the program headers
    in.clear();
    in.seekg(0);
    std::ofstream out(temp_path, std::ios::binary | std::ios::trunc);
    out << in.rdbuf();
    out.seekp(static_cast<std::streamoff>(eh.e_phoff));
    out.write(reinterpret_cast<const char *>(phdrs.data()), phdrs.size() * sizeof(Elf_Phdr));
    out.close();
    if (!in || !out)
    {
        log_format(LogLevel::ERROR, TAG, "Failed to write {}", temp_path);
        return failure;
    }

    void *handle = dlopen(temp_path, RTLD_GLOBAL | RTLD_NOW);
    if (!handle)
    {
        log_format(LogLevel::ERROR, TAG, "dlopen failed for {}: {}", temp_path, safe_dlerror());
        return failure;
    }

    // ask the loader where it put the library (no symbol needed)
    const char *slash = strrchr(temp_path, '/');
    BiasLookup lookup{slash ? slash + 1 : temp_path, 0, 0};
    dl_iterate_phdr(find_bias, &lookup);
    if (lookup.matches != 1)
    {
        log_format(LogLevel::ERROR, TAG, "Expected one loaded copy of {}, found {}",
                   temp_path, lookup.matches);
        dlclose(handle);
        return failure;
    }
    const uintptr_t load_bias = lookup.bias;
    const uintptr_t pool_addr = load_bias + pool_start;

    log_format(LogLevel::INFO, TAG, "Trampoline pool [0x{:X}, 0x{:X}) ready in {}",
               pool_addr, pool_addr + pool_len, temp_path);
    return {handle, reinterpret_cast<void *>(load_bias), pool_addr, static_cast<size_t>(pool_len)};
}