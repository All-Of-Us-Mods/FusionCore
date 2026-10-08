// Copyright (c) 2026 XtraCube
#include <utilities/asm.h>
#include <cstdint>
#include <cstring>
#include <cassert>

#if defined(__aarch64__)

bool emit_absolute_jump(void *trampoline_addr, void *hook_func)
{
    if (reinterpret_cast<uintptr_t>(trampoline_addr) % 4 != 0) {
        return false; // Address must be 4-byte aligned for ARM64 instructions
    }

    // Cannot work with null pointers
    if (!trampoline_addr || !hook_func) return false;

    auto *code = reinterpret_cast<uint32_t *>(trampoline_addr);

    // LDR X16, #8 (opcode: 0x58000050)
    code[0] = 0x58000050;

    // BR X16 (opcode: 0xD61F0200)
    code[1] = 0xD61F0200;

    // Store hook_func address as 64-bit literal
    auto *literal = reinterpret_cast<uint64_t *>(code + 2);
    *literal = reinterpret_cast<uint64_t>(hook_func);
    return true;
}

void *resolve_inner_branch(void *func_addr, int max_depth)
{
    if (func_addr == nullptr || max_depth <= 0) return func_addr;

    auto addr = reinterpret_cast<uintptr_t>(func_addr);
    uint32_t instruction = *(uint32_t *) addr;

    // Check for unconditional B (0b000101)
    if ((instruction >> 26) == 0b000101)
    {
        uint32_t imm26_u = instruction & 0x03FFFFFF;
        int64_t imm26 = (int64_t) (int32_t) (imm26_u << 6) >> 6; // sign-extend 26-bit
        uintptr_t target_addr = addr + (imm26 << 2);

        // Recursively resolve the target address
        return resolve_inner_branch(reinterpret_cast<void *>(target_addr), max_depth - 1);
    }

    // return the original address if no branch instruction is found
    return func_addr;
}

bool is_b(uint32_t instr)
{
    return (instr & 0xFC000000) == 0x14000000; // B
}

bool is_bl(uint32_t instr)
{
    return (instr & 0xFC000000) == 0x94000000; // BL
}

bool is_br(uint32_t instr)
{
    return (instr & 0xFFFFFC1F) == 0xD61F0000; // BR Xn
}

bool is_conditional_branch(uint32_t instr)
{
    // B.cond: 0b01010100 mask
    return (instr & 0xFF000010) == 0x54000000;
}

bool is_unconditional_branch(uint32_t instr)
{
    return is_b(instr); // only plain B
}

bool is_call(uint32_t instr)
{
    return is_bl(instr) || // BL (direct call)
           (instr & 0xFFFFFC1F) == 0xD63F0000; // BLR Xn (indirect call)
}

bool is_compare_branch(uint32_t instr)
{
    // CBZ, CBNZ
    return (instr & 0x7F000000) == 0x34000000;
}

bool is_test_branch(uint32_t instr)
{
    // TBZ, TBNZ
    return (instr & 0x7F000000) == 0x36000000;
}

bool is_ret(uint32_t instr)
{
    return (instr & 0xFFFFFC1F) == 0xD65F0000; // RET
}

bool is_branch(uint32_t instr)
{
    return is_unconditional_branch(instr) || // B
           is_conditional_branch(instr) || // B.cond
           is_br(instr); // BR
           // if we have issues, re-enable the below
           //is_compare_branch(instr) || // CBZ/CBNZ
           //is_test_branch(instr);            // TBZ/TBNZ
}

bool is_trap_or_exit(uint32_t instr)
{
    return (instr & 0xFF000000) == 0xD4000000; // SVC, HVC, SMC, BRK
}

// Function to check if the function at 'address' is small based on its first few instructions
bool is_small_function(void *address, int max_instr)
{
    if (address == nullptr || max_instr <= 0) return false;

    auto *bytes = reinterpret_cast<uint8_t *>(address);
    for (int i = 0; i < max_instr; i++)
    {
        uint32_t instr;
        memcpy(&instr, bytes + i * 4, sizeof(uint32_t));

        if (is_ret(instr)) return true;

        if (is_branch(instr)) return true;

        if (is_trap_or_exit(instr)) return true;
    }

    return false;
}

#elif defined(__arm__)

bool emit_absolute_jump(void* trampoline_addr, void* hook_func) {
    if (reinterpret_cast<uintptr_t>(trampoline_addr) % 4 != 0) {
        return false; // Address must be 4-byte aligned for ARM32 instructions
    }

    // Cannot work with null pointers
    if (!trampoline_addr || !hook_func) return false;

    uint32_t* code = reinterpret_cast<uint32_t*>(trampoline_addr);

    // LDR PC, [PC, #-4]
    code[0] = 0xE51FF004;

    // Literal pool: hook_func address
    code[1] = reinterpret_cast<uint32_t>(hook_func);
    return true;
}

void *resolve_inner_branch(void *func_addr, int max_depth) {
    if (func_addr == nullptr || max_depth <= 0) return func_addr;

    uintptr_t addr = reinterpret_cast<uintptr_t>(func_addr);
    uint32_t instruction = *(uint32_t *)addr;

    // Check for unconditional B (0b1010) or BL (0b1011) in bits 27-24
    uint32_t opcode = (instruction >> 24) & 0xF;
    if (opcode == 0xA || opcode == 0xB) {
        uint32_t imm24_u = instruction & 0x00FFFFFF;
        int32_t imm24 = (int32_t)(imm24_u << 8) >> 8; // sign-extend imm24 directly
        uintptr_t target_addr = addr + 8 + (imm24 << 2); // +8 because ARM32 PC is ahead by 8 bytes
        return resolve_inner_branch(reinterpret_cast<void *>(target_addr), max_depth - 1);
    }

    // return the original address if no branch instruction is found
    return func_addr;
}

bool is_unconditional_branch(uint32_t instr) {
    // Check both the AL condition (0xE0000000) and the B opcode (0x0A000000)
    return (instr & 0xFF000000) == 0xEA000000;
}

bool is_branch_with_link(uint32_t instr) {
    // Check both the AL condition (0xE0000000) and the BL opcode (0x0B000000)
    return (instr & 0xFF000000) == 0xEB000000;
}

bool is_conditional_branch(uint32_t instr) {
    // Check if opcode is B (0x0A), but condition code is NOT AL (0xE)
    return ((instr & 0x0F000000) == 0x0A000000) &&
           ((instr & 0xF0000000) != 0xE0000000);
}

bool is_register_branch(uint32_t instr) {
    // BX or BLX (register): bits[27:4] = 0001 0010 1111 1111 1111 0001 xxxx
    // BX:   0x012FFF10
    // BLX:  0x012FFF30
    return (instr & 0x0FFFFFF0) == 0x012FFF10 ||  // BX
           (instr & 0x0FFFFFF0) == 0x012FFF30;   // BLX (register)
}

bool is_ret(uint32_t instr) {
    // Common return is: MOV PC, LR (0xE1A0F00E)
    // Also BX LR: 0xE12FFF1E
    return instr == 0xE1A0F00E || instr == 0xE12FFF1E;
}

bool is_branch(uint32_t instr) {
    return is_unconditional_branch(instr) ||
           is_branch_with_link(instr) ||
           is_conditional_branch(instr) ||
           is_register_branch(instr);
}

bool is_trap_or_exit(uint32_t instr) {
    // SVC (Supervisor Call): 0xEFxxxxxx (bits[31:24] = 1111 0000)
    return (instr & 0xFF000000) == 0xEF000000;
}

bool is_small_function(void* address, int max_instr = 4) {
    if (address == nullptr || max_instr <= 0) return false;

    auto* bytes = reinterpret_cast<uint8_t*>(address);
    for (int i = 0; i < max_instr; i++) {
        uint32_t instr;
        memcpy(&instr, bytes + i * 4, sizeof(uint32_t));

        if (is_ret(instr)) return true;

        if (is_branch(instr)) return true;

        if (is_trap_or_exit(instr)) return true;
    }

    return false;
}
#endif