// Copyright (c) 2026 XtraCube. All rights reserved.

#include <utilities/elf.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <string.h>
#include <android/log.h>

#if defined(__aarch64__)
typedef Elf64_Ehdr Elf_Ehdr;
typedef Elf64_Shdr Elf_Shdr;
typedef Elf64_Sym Elf_Sym;
#elif defined(__arm__)
typedef Elf32_Ehdr Elf_Ehdr;
typedef Elf32_Shdr Elf_Shdr;
typedef Elf32_Sym Elf_Sym;
#endif

uintptr_t get_rva_from_sym_file(const char* filepath, const char* target_symbol) {
    int fd = open(filepath, O_RDONLY);
    if (fd < 0) return 0;

    off_t size = lseek(fd, 0, SEEK_END);
    uint8_t* map = (uint8_t*)mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);

    if (map == MAP_FAILED) return 0;

    uintptr_t rva = 0;

    Elf_Ehdr* ehdr = (Elf_Ehdr*)map;
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) == 0) {
        Elf_Shdr* shdr = (Elf_Shdr*)(map + ehdr->e_shoff);

        for (int i = 0; i < ehdr->e_shnum; i++) {
            if (shdr[i].sh_type == SHT_SYMTAB || shdr[i].sh_type == SHT_DYNSYM) {
                Elf_Sym* syms = (Elf_Sym*)(map + shdr[i].sh_offset);
                int count = shdr[i].sh_size / sizeof(Elf_Sym);
                const char* strtab = (const char*)(map + shdr[shdr[i].sh_link].sh_offset);

                for (int j = 0; j < count; j++) {
                    if (strcmp(&strtab[syms[j].st_name], target_symbol) == 0) {
                        rva = (uintptr_t)syms[j].st_value;
                        break;
                    }
                }
            }
            if (rva != 0) break;
        }
    }

    munmap(map, size);
    return rva;
}
