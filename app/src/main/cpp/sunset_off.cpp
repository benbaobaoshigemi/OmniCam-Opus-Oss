// Natural photo mode, "关闭落日特调" (settings and flag file: NaturalMode.java).
//
//  * TmcSunsetDetection::CheckSunset in libOPAlgoCamHybridRaw.so ends with
//    "isSunset = netScore > 0.6" (mov / movk of 0x3f19999a, fmov, fcmp, cset wN, gt). The cset is
//    rewritten to "mov wN, wzr", so the AI sunset classifier never reports a sunset: no sunset
//    AITM, no CvTmc sunset mode (the >=10x red-sun clip, the sunset upsample and feature flags).
//  * persist.camera.hybridraw.SunsetEnhance reads "1", which skips the orange saturation boost.
//
// Both follow files/jiege/natural/sunset_off: present = on. The instruction is found, not assumed
// (exported function, then the one 0.6 comparison inside it); anything else leaves it untouched.
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <link.h>
#include <mutex>
#include <pthread.h>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kTag[] = "SunsetOff";
constexpr char kFlag[] = "/data/user/0/com.oplus.camera/files/jiege/natural/sunset_off";
constexpr char kLibraryName[] = "libOPAlgoCamHybridRaw.so";
constexpr char kFunctionPrefix[] = "_ZN18TmcSunsetDetection11CheckSunset";
constexpr char kProperty[] = "persist.camera.hybridraw.SunsetEnhance";

// 0.6f = 0x3f19999a, built with "mov wN,#0x999a; movk wN,#0x3f19,lsl #16".
constexpr uint32_t kMov = 0x52800000 | (0x999a << 5);
constexpr uint32_t kMovk = 0x72a00000 | (0x3f19 << 5);
constexpr uint32_t kCsetGt = 0x1a9fd7e0;   // cset wN, gt  (csinc wN, wzr, wzr, le)
constexpr uint32_t kMovZero = 0x2a1f03e0;  // mov  wN, wzr (orr wN, wzr, wzr)
constexpr size_t kWindow = 12;

struct Library {
    uintptr_t base = 0;
    std::string path;
};

Library hybridRaw;
uint32_t *site;
uint32_t siteReg;
bool searched;
std::mutex patchMutex;

int (*originalPropertyGet)(const char *, char *);

int findLibrary(dl_phdr_info *info, size_t, void *result) {
    if (info->dlpi_name && std::strstr(info->dlpi_name, kLibraryName)) {
        auto *library = static_cast<Library *>(result);
        library->base = info->dlpi_addr;
        library->path = info->dlpi_name;
    }
    return 0;
}

bool readAt(FILE *file, uint64_t offset, void *out, size_t size) {
    return std::fseek(file, static_cast<long>(offset), SEEK_SET) == 0 && std::fread(out, 1, size, file) == size;
}

bool findFunction(const std::string &path, uint64_t &start, uint64_t &size) {
    FILE *file = std::fopen(path.c_str(), "rbe");
    if (!file) return false;
    bool found = false;
    Elf64_Ehdr header;
    if (readAt(file, 0, &header, sizeof(header)) && header.e_shentsize == sizeof(Elf64_Shdr)) {
        for (int i = 0; i < header.e_shnum && !found; i++) {
            Elf64_Shdr dynsym, strtab;
            if (!readAt(file, header.e_shoff + i * sizeof(Elf64_Shdr), &dynsym, sizeof(dynsym)) || dynsym.sh_type != SHT_DYNSYM) continue;
            if (!readAt(file, header.e_shoff + dynsym.sh_link * sizeof(Elf64_Shdr), &strtab, sizeof(strtab))) break;
            std::string names(strtab.sh_size, '\0');
            if (!readAt(file, strtab.sh_offset, names.data(), names.size())) break;
            for (uint64_t at = 0; at + sizeof(Elf64_Sym) <= dynsym.sh_size; at += sizeof(Elf64_Sym)) {
                Elf64_Sym symbol;
                if (!readAt(file, dynsym.sh_offset + at, &symbol, sizeof(symbol))) break;
                if (ELF64_ST_TYPE(symbol.st_info) != STT_FUNC || symbol.st_size == 0 || symbol.st_name >= names.size()) continue;
                if (names.compare(symbol.st_name, sizeof(kFunctionPrefix) - 1, kFunctionPrefix) == 0) {
                    start = symbol.st_value;
                    size = symbol.st_size;
                    found = true;
                    break;
                }
            }
            break;
        }
    }
    std::fclose(file);
    return found;
}

// The one cset after "mov / movk 0.6 ... fcmp" inside CheckSunset, stock or already rewritten.
bool locate() {
    if (site) return true;
    if (searched) return false;
    if (!hybridRaw.base) dl_iterate_phdr(findLibrary, &hybridRaw);
    if (!hybridRaw.base) return false;  // not loaded yet
    searched = true;
    uint64_t start = 0, size = 0;
    if (!findFunction(hybridRaw.path, start, size)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "CheckSunset not found, library left as is");
        return false;
    }
    auto *code = reinterpret_cast<uint32_t *>(hybridRaw.base + start);
    const size_t count = size / sizeof(uint32_t);
    int matches = 0;
    for (size_t i = 0; i < count; i++) {
        if ((code[i] & ~0x1fu) != kMov) continue;
        const uint32_t constReg = code[i] & 0x1f;
        for (size_t j = i + 1; j < count && j <= i + kWindow; j++) {
            if (code[j] != (kMovk | constReg)) continue;
            for (size_t k = j + 1; k < count && k <= j + kWindow; k++) {
                const uint32_t op = code[k] & ~0x1fu;
                if (op == kCsetGt || op == kMovZero) {
                    site = &code[k];
                    siteReg = code[k] & 0x1f;
                    matches++;
                    break;
                }
            }
            break;
        }
    }
    if (matches != 1) {
        site = nullptr;
        __android_log_print(ANDROID_LOG_ERROR, kTag, "sunset comparison: %d matches, library left as is", matches);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "sunset comparison at +0x%lx (w%u)",
                        static_cast<unsigned long>(reinterpret_cast<uintptr_t>(site) - hybridRaw.base), siteReg);
    return true;
}

bool flagOn() { return access(kFlag, F_OK) == 0; }

void apply() {
    std::lock_guard<std::mutex> lock(patchMutex);
    if (!locate()) return;
    const uint32_t wanted = (flagOn() ? kMovZero : kCsetGt) | siteReg;
    if (*site == wanted) return;
    const uintptr_t page = reinterpret_cast<uintptr_t>(site) & ~uintptr_t{4095};
    if (mprotect(reinterpret_cast<void *>(page), 4096, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "mprotect failed");
        return;
    }
    *site = wanted;
    __builtin___clear_cache(reinterpret_cast<char *>(site), reinterpret_cast<char *>(site) + 4);
    mprotect(reinterpret_cast<void *>(page), 4096, PROT_READ | PROT_EXEC);
    __android_log_print(ANDROID_LOG_INFO, kTag, "sunset classifier %s", wanted == (kMovZero | siteReg) ? "off" : "stock");
}

// Read once per photo by CvTmcMain::process, before the sunset stages run.
int hookedPropertyGet(const char *name, char *value) {
    if (name && std::strcmp(name, kProperty) == 0) {
        apply();
        if (flagOn()) {
            value[0] = '1';
            value[1] = '\0';
            return 1;
        }
    }
    return originalPropertyGet(name, value);
}

// LSPosed calls the load callback while dlopen holds the linker lock; patch after it returns.
void *applyAfterLoad(void *) {
    apply();
    return nullptr;
}

}  // namespace

void installSunsetOff(HookFunction hook) {
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (libc) {
        hookExport(hook, libc, "__system_property_get", reinterpret_cast<void *>(hookedPropertyGet),
                   reinterpret_cast<void **>(&originalPropertyGet), kTag);
    }
}

void onLibraryLoadedForSunsetOff(const char *name) {
    if (!name || !std::strstr(name, kLibraryName)) return;
    pthread_t worker;
    if (pthread_create(&worker, nullptr, applyAfterLoad, nullptr) == 0) pthread_detach(worker);
}
