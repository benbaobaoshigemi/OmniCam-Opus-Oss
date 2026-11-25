// Large-allocation trace for the camera process (native heap growth diagnosis).
//
// While the marker file files/jiege/bigalloc exists in the camera's private files, every heap block of
// kMinBytes or more is logged when it is allocated (tag BigAlloc, "A <ptr> <size> <tid> <return addresses>")
// and when it is freed ("F <ptr> <size>"). Blocks that never log an "F" are the ones the process still holds;
// the return addresses are resolved to a library and offset against /proc/<pid>/maps afterwards, so nothing
// is looked up (dladdr takes the loader lock) inside the allocator.
//
// Smaller blocks take the original function after one comparison. The hook calls nothing that allocates.
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <fcntl.h>
#include <cstddef>
#include <cstdint>
#include <algorithm>
#include <cstring>
#include <dlfcn.h>
#include <malloc.h>
#include <unistd.h>

#include "native_hooks.h"

namespace {

constexpr char kMarker[] = "/data/user/0/com.oplus.camera/files/jiege/bigalloc";
constexpr size_t kMinBytes = 512u << 10;
constexpr int kFrames = 7;
constexpr size_t kSlots = 32768;  // power of two
constexpr uintptr_t kFree = 0, kDead = 1;

// The trace goes to the marker file itself (append): nothing here allocates or takes the logger's locks.
int outFd = -1;

__attribute__((format(printf, 1, 2))) void note(const char *format, ...) {
    if (outFd < 0) return;
    char line[384];
    va_list args;
    va_start(args, format);
    int n = vsnprintf(line, sizeof line - 1, format, args);
    va_end(args);
    if (n <= 0) return;
    if (n > int(sizeof line) - 2) n = sizeof line - 2;
    line[n++] = 10;
    (void)!write(outFd, line, size_t(n));
}

struct Entry {
    std::atomic<uintptr_t> ptr{kFree};
    size_t size = 0;
};
Entry table[kSlots];
std::atomic<int> live{0};

size_t slotOf(uintptr_t p) { return (((p >> 4) * 0x9E3779B97F4A7C15ull) >> 40) & (kSlots - 1); }

bool track(void *p, size_t size) {
    uintptr_t key = reinterpret_cast<uintptr_t>(p);
    for (size_t i = 0, s = slotOf(key); i < 128; i++, s = (s + 1) & (kSlots - 1)) {
        uintptr_t seen = table[s].ptr.load(std::memory_order_relaxed);
        if (seen == kFree || seen == kDead) {
            table[s].size = size;
            if (table[s].ptr.compare_exchange_strong(seen, key)) {
                live.fetch_add(1, std::memory_order_relaxed);
                return true;
            }
        }
    }
    return false;
}

// Size of the tracked block at p (0 when p is not tracked), which is forgotten.
size_t untrack(void *p) {
    uintptr_t key = reinterpret_cast<uintptr_t>(p);
    for (size_t i = 0, s = slotOf(key); i < 128; i++, s = (s + 1) & (kSlots - 1)) {
        uintptr_t seen = table[s].ptr.load(std::memory_order_relaxed);
        if (seen == kFree) return 0;
        if (seen == key) {
            size_t size = table[s].size;
            if (table[s].ptr.compare_exchange_strong(seen, kDead)) {
                live.fetch_sub(1, std::memory_order_relaxed);
                return size;
            }
        }
    }
    return 0;
}

inline uintptr_t stripPac(uintptr_t v) {
    asm volatile("mov x30, %0\n\thint #7\n\tmov %0, x30" : "+r"(v) : : "x30");
    return v;
}

// Frame-pointer walk from the caller of the hooked function; stops at the first implausible frame.
__attribute__((noinline)) int callers(uintptr_t *out, int max) {
    auto *fp = static_cast<uintptr_t *>(__builtin_frame_address(0));
    int n = 0;
    while (n < max && fp && (reinterpret_cast<uintptr_t>(fp) & 15) == 0) {
        out[n++] = stripPac(fp[1]);
        auto *next = reinterpret_cast<uintptr_t *>(fp[0]);
        if (next <= fp || reinterpret_cast<uintptr_t>(next) - reinterpret_cast<uintptr_t>(fp) > (1u << 20)) break;
        fp = next;
    }
    return n;
}

void logAlloc(void *p, size_t size) {
    uintptr_t ra[kFrames] = {};
    callers(ra, kFrames);
    if (!track(p, size)) note("table full, %p not tracked", p);
    note("A %p %zu t%d %lx %lx %lx %lx %lx %lx %lx", p, size, gettid(), ra[0], ra[1], ra[2],
                        ra[3], ra[4], ra[5], ra[6]);
}

void logFree(void *p) {
    size_t size = untrack(p);
    if (size) note("F %p %zu", p, size);
}

// Calls that arrive between the patch and the moment the original's address is stored (the hook library
// allocates there too) are served from a static arena that is never reused; its blocks carry their size.
alignas(64) uint8_t arena[256 << 10];
std::atomic<size_t> arenaUsed{0};

bool inArena(const void *p) {
    auto a = reinterpret_cast<uintptr_t>(arena), v = reinterpret_cast<uintptr_t>(p);
    return v >= a && v < a + sizeof arena;
}

void *arenaAlloc(size_t size, size_t alignment = 16) {
    if (alignment < 16) alignment = 16;
    size_t need = ((size + 63) & ~size_t(63)) + 64 + alignment;
    size_t at = arenaUsed.fetch_add(need);
    if (at + need > sizeof arena) return nullptr;
    uintptr_t user = (reinterpret_cast<uintptr_t>(arena) + at + 64 + alignment - 1) & ~(alignment - 1);
    *reinterpret_cast<size_t *>(user - sizeof(size_t)) = size;
    return reinterpret_cast<void *>(user);
}

size_t arenaSize(const void *p) { return *reinterpret_cast<const size_t *>(reinterpret_cast<uintptr_t>(p) - sizeof(size_t)); }

void *(*originalMalloc)(size_t);
void *(*originalCalloc)(size_t, size_t);
void *(*originalRealloc)(void *, size_t);
void *(*originalMemalign)(size_t, size_t);
void *(*originalAlignedAlloc)(size_t, size_t);
int (*originalPosixMemalign)(void **, size_t, size_t);
void (*originalFree)(void *);

void *hookedMalloc(size_t size) {
    if (!originalMalloc) return arenaAlloc(size);
    static std::atomic<bool> first{false};
    if (!first.exchange(true)) note("first malloc through the hook");
    void *p = originalMalloc(size);
    if (size >= kMinBytes && p) logAlloc(p, size);
    return p;
}

void *hookedCalloc(size_t count, size_t size) {
    if (!originalCalloc) return arenaAlloc(count * size);
    void *p = originalCalloc(count, size);
    if (p && size && count <= SIZE_MAX / size && count * size >= kMinBytes) logAlloc(p, count * size);
    return p;
}

void *hookedRealloc(void *old, size_t size) {
    if (old && inArena(old)) {
        void *p = hookedMalloc(size);
        if (p) std::memcpy(p, old, std::min(size, arenaSize(old)));
        return p;
    }
    if (!originalRealloc) {
        void *p = arenaAlloc(size);
        if (p && old) std::memcpy(p, old, std::min(size, malloc_usable_size(old)));
        return p;
    }
    bool tracked = old && live.load(std::memory_order_relaxed) > 0;
    void *p = originalRealloc(old, size);
    if (tracked && (p || size == 0)) logFree(old);
    if (size >= kMinBytes && p) logAlloc(p, size);
    return p;
}

void *hookedMemalign(size_t alignment, size_t size) {
    if (!originalMemalign) return arenaAlloc(size, alignment);
    void *p = originalMemalign(alignment, size);
    if (size >= kMinBytes && p) logAlloc(p, size);
    return p;
}

void *hookedAlignedAlloc(size_t alignment, size_t size) {
    if (!originalAlignedAlloc) return arenaAlloc(size, alignment);
    void *p = originalAlignedAlloc(alignment, size);
    if (size >= kMinBytes && p) logAlloc(p, size);
    return p;
}

int hookedPosixMemalign(void **out, size_t alignment, size_t size) {
    if (!originalPosixMemalign) {
        *out = arenaAlloc(size, alignment);
        return *out ? 0 : 12;
    }
    int rc = originalPosixMemalign(out, alignment, size);
    if (rc == 0 && size >= kMinBytes && out && *out) logAlloc(*out, size);
    return rc;
}

void hookedFree(void *p) {
    if (!p || inArena(p) || !originalFree) return;
    if (live.load(std::memory_order_relaxed) > 0) logFree(p);
    originalFree(p);
}

bool hookSymbol(HookFunction hook, void *libc, const char *symbol, void *replacement, void **original) {
    void *address = dlsym(libc, symbol);
    if (address && hook(address, replacement, original) == 0) return true;
    note("hook failed: %s", symbol);
    return false;
}

}  // namespace

void installBigAllocTrace(HookFunction hook) {
    outFd = open(kMarker, O_WRONLY | O_APPEND | O_CLOEXEC);
    if (outFd < 0) return;
    void *libc = dlopen("libc.so", RTLD_NOW);
    if (!libc) return;
    // free first: a block allocated through the hooks is never freed past an unhooked free.
    if (!hookSymbol(hook, libc, "free", reinterpret_cast<void *>(hookedFree), reinterpret_cast<void **>(&originalFree))) return;
    hookSymbol(hook, libc, "malloc", reinterpret_cast<void *>(hookedMalloc), reinterpret_cast<void **>(&originalMalloc));
    hookSymbol(hook, libc, "calloc", reinterpret_cast<void *>(hookedCalloc), reinterpret_cast<void **>(&originalCalloc));
    hookSymbol(hook, libc, "realloc", reinterpret_cast<void *>(hookedRealloc), reinterpret_cast<void **>(&originalRealloc));
    hookSymbol(hook, libc, "memalign", reinterpret_cast<void *>(hookedMemalign), reinterpret_cast<void **>(&originalMemalign));
    hookSymbol(hook, libc, "aligned_alloc", reinterpret_cast<void *>(hookedAlignedAlloc), reinterpret_cast<void **>(&originalAlignedAlloc));
    hookSymbol(hook, libc, "posix_memalign", reinterpret_cast<void *>(hookedPosixMemalign), reinterpret_cast<void **>(&originalPosixMemalign));
    note("tracing blocks of %zu bytes and more", kMinBytes);
}
