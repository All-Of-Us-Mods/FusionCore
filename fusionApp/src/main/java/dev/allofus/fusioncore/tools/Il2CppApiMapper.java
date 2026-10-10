package dev.allofus.fusioncore.tools;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Il2CppApiMapper {
    private static final String TAG = "Il2CppApiMapper";
    private static final String STOCK_BACKUP_NAME = "libunity.stock.so";
    private static final String MARKER = "il2cpp_init";
    private static final int CLUSTER_GAP = 4096;

    private Il2CppApiMapper() {}

    private record CString(int offset, String value) {}

    public static boolean hasObfuscatedExports(File gameLibIl2Cpp) throws IOException {
        return !readDynamicSymbols(gameLibIl2Cpp).contains(MARKER);
    }

    public static Map<String, String> prepare(File gameLibUnity, File gameLibIl2Cpp, File cachedLibUnity)
            throws IOException {
        byte[] game = readAll(gameLibUnity);
        File backup = new File(cachedLibUnity.getParentFile(), STOCK_BACKUP_NAME);
        byte[] cached = readAll(cachedLibUnity);

        if (containsCString(game, MARKER)) {
            if (!containsCString(cached, MARKER) && backup.isFile()) {
                Log.i(TAG, "Game is not obfuscated, restoring stock libunity");
                writeAll(cachedLibUnity, readAll(backup));
            }
            return new HashMap<>();
        }

        byte[] stock = readStock(cachedLibUnity, cached);
        Set<String> exports = readDynamicSymbols(gameLibIl2Cpp);

        List<CString> real = new ArrayList<>();
        Set<String> stockExports = new HashSet<>();
        for (CString s : scanIdentifiers(stock)) {
            if (s.value.startsWith("il2cpp_")) real.add(s);
            else if (exports.contains(s.value)) stockExports.add(s.value);
        }

        List<CString> obfuscated = new ArrayList<>();
        for (CString s : scanIdentifiers(game)) {
            if (exports.contains(s.value) && !stockExports.contains(s.value)) obfuscated.add(s);
        }

        validate(real, obfuscated);

        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < real.size(); i++) {
            map.put(real.get(i).value, obfuscated.get(i).value);
        }
        writeSlots(cachedLibUnity, cached, real, map);

        Log.i(TAG, "Mapped " + map.size() + " obfuscated il2cpp exports, il2cpp_init -> " + map.get(MARKER));
        return map;
    }

    public static void patchLibUnity(File cachedLibUnity, Map<String, String> map) throws IOException {
        byte[] cached = readAll(cachedLibUnity);
        byte[] stock = readStock(cachedLibUnity, cached);

        List<CString> real = new ArrayList<>();
        for (CString s : scanIdentifiers(stock)) {
            if (s.value.startsWith("il2cpp_")) real.add(s);
        }
        writeSlots(cachedLibUnity, cached, real, map);
        Log.i(TAG, "Applied il2cpp api map to libunity, il2cpp_init = " + map.get(MARKER));
    }

    private static byte[] readStock(File cachedLibUnity, byte[] cached) throws IOException {
        File backup = new File(cachedLibUnity.getParentFile(), STOCK_BACKUP_NAME);
        if (containsCString(cached, MARKER)) {
            writeAll(backup, cached);
            return cached;
        }
        if (backup.isFile() && backup.length() == cached.length) {
            return readAll(backup);
        }
        throw new IOException("Cached libunity is patched but no stock backup exists");
    }

    private static void writeSlots(File cachedLibUnity, byte[] cached, List<CString> real, Map<String, String> map)
            throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(cachedLibUnity, "rw")) {
            for (CString r : real) {
                String name = map.get(r.value);
                if (name == null || name.length() > r.value.length()) {
                    if (name != null) Log.w(TAG, "Mapped name too long for " + r.value + ": " + name);
                    name = r.value;
                }
                byte[] slot = new byte[r.value.length()];
                byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(bytes, 0, slot, 0, bytes.length);
                if (!regionEquals(cached, r.offset, slot)) {
                    out.seek(r.offset);
                    out.write(slot);
                }
            }
        }
    }

    private static void validate(List<CString> real, List<CString> obfuscated) throws IOException {
        if (real.isEmpty() || real.size() != obfuscated.size()) {
            throw new IOException("Name count mismatch: stock=" + real.size() + " game=" + obfuscated.size());
        }
        for (int i = 0; i < real.size(); i++) {
            if (obfuscated.get(i).value.length() > real.get(i).value.length()) {
                throw new IOException("Obfuscated name longer than original at " + real.get(i).value);
            }
            if (i == 0) continue;
            int realGap = real.get(i).offset - real.get(i - 1).offset;
            int gameGap = obfuscated.get(i).offset - obfuscated.get(i - 1).offset;
            if (realGap < CLUSTER_GAP && realGap != gameGap) {
                throw new IOException("String layout mismatch at " + real.get(i).value);
            }
        }
    }

    private static boolean regionEquals(byte[] data, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) return false;
        }
        return data[offset + expected.length] == 0;
    }

    private static boolean containsCString(byte[] data, String value) {
        for (CString s : scanIdentifiers(data)) {
            if (s.value.equals(value)) return true;
        }
        return false;
    }

    private static List<CString> scanIdentifiers(byte[] data) {
        List<CString> result = new ArrayList<>();
        int start = 0;
        boolean valid = true;
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == 0) {
                int length = i - start;
                if (valid && length >= 4 && length <= 128) {
                    result.add(new CString(start, new String(data, start, length, StandardCharsets.US_ASCII)));
                }
                start = i + 1;
                valid = true;
            } else if (valid && !((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                    || (b >= '0' && b <= '9') || b == '_')) {
                valid = false;
            }
        }
        return result;
    }

    private static Set<String> readDynamicSymbols(File elf) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(elf, "r")) {
            byte[] ident = new byte[16];
            file.readFully(ident);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
                throw new IOException("Not an ELF file: " + elf);
            }
            boolean is64 = ident[4] == 2;
            ByteBuffer header = read(file, 0, is64 ? 64 : 52);
            long shoff = is64 ? header.getLong(0x28) : header.getInt(0x20) & 0xffffffffL;
            int shentsize = header.getShort(is64 ? 0x3A : 0x2E) & 0xffff;
            int shnum = header.getShort(is64 ? 0x3C : 0x30) & 0xffff;
            ByteBuffer sections = read(file, shoff, shentsize * shnum);

            for (int i = 0; i < shnum; i++) {
                int base = i * shentsize;
                if (sections.getInt(base + 4) != 11 /* SHT_DYNSYM */) continue;
                long symOffset = sectionOffset(sections, base, is64);
                long symSize = sectionSize(sections, base, is64);
                int link = sections.getInt(base + (is64 ? 0x28 : 0x18));
                int strBase = link * shentsize;
                ByteBuffer symbols = read(file, symOffset, (int) symSize);
                byte[] strings = read(file, sectionOffset(sections, strBase, is64),
                        (int) sectionSize(sections, strBase, is64)).array();

                Set<String> names = new HashSet<>();
                int entSize = is64 ? 24 : 16;
                for (int pos = 0; pos + entSize <= symSize; pos += entSize) {
                    int name = symbols.getInt(pos);
                    int shndx = symbols.getShort(pos + (is64 ? 6 : 14)) & 0xffff;
                    if (name == 0 || shndx == 0) continue;
                    int end = name;
                    while (end < strings.length && strings[end] != 0) end++;
                    names.add(new String(strings, name, end - name, StandardCharsets.US_ASCII));
                }
                return names;
            }
            throw new IOException("No .dynsym section in " + elf);
        }
    }

    private static long sectionOffset(ByteBuffer sections, int base, boolean is64) {
        return is64 ? sections.getLong(base + 0x18) : sections.getInt(base + 0x10) & 0xffffffffL;
    }

    private static long sectionSize(ByteBuffer sections, int base, boolean is64) {
        return is64 ? sections.getLong(base + 0x20) : sections.getInt(base + 0x14) & 0xffffffffL;
    }

    private static byte[] readAll(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] data = new byte[(int) in.length()];
            in.readFully(data);
            return data;
        }
    }

    private static void writeAll(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(data);
        }
    }

    private static ByteBuffer read(RandomAccessFile file, long offset, int length) throws IOException {
        byte[] data = new byte[length];
        file.seek(offset);
        file.readFully(data);
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }
}