// The camera's OpenCL engine seen through its own dispatch table (libOPAlgoCamHybridRaw / the framework's CLEngine).
#pragma once

#include <android/log.h>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include "capture_dump.h"

namespace clapi {

using Arg = CaptureKernelArg;
using Args = CaptureKernelArgs;

template <typename T> T field(const void *base, size_t offset) {
    T result;
    memcpy(&result, static_cast<const char *>(base) + offset, sizeof(result));
    return result;
}
template <typename T> T scalar(const Arg &arg) { return field<T>(arg.value, 0); }

// Offsets are from this PMA110 library's exported CLApi dispatch wrappers.
struct Api {
    int (*queueInfo)(void *, unsigned, size_t, void *, size_t *);
    void *(*createProgram)(void *, unsigned, const char **, const size_t *, int *);
    int (*buildProgram)(void *, unsigned, const void **, const char *, void *, void *);
    int (*buildInfo)(void *, void *, unsigned, size_t, void *, size_t *);
    void *(*createKernel)(void *, const char *, int *);
    void *(*createBuffer)(void *, uint64_t, size_t, void *, int *);
    int (*setArg)(void *, unsigned, size_t, const void *);
    int (*enqueue)(void *, void *, unsigned, const size_t *, const size_t *, const size_t *, unsigned, const void *, void *);
    int (*read)(void *, void *, unsigned, size_t, size_t, void *, unsigned, const void *, void *);
    int (*finish)(void *);
    int (*releaseMem)(void *);
    int (*releaseKernel)(void *);
    int (*releaseProgram)(void *);

    explicit Api(void *engine) {
        const void *table = field<void *>(field<void *>(engine, 0x38), 8);
        queueInfo = field<decltype(queueInfo)>(table, 0x58);
        createProgram = field<decltype(createProgram)>(table, 0xa8);
        buildProgram = field<decltype(buildProgram)>(table, 0xc8);
        buildInfo = field<decltype(buildInfo)>(table, 0xd8);
        createKernel = field<decltype(createKernel)>(table, 0xe0);
        createBuffer = field<decltype(createBuffer)>(table, 0x60);
        setArg = field<decltype(setArg)>(table, 0x100);
        enqueue = field<decltype(enqueue)>(table, 0x1a8);
        read = field<decltype(read)>(table, 0x150);
        finish = field<decltype(finish)>(table, 0x148);
        releaseMem = field<decltype(releaseMem)>(table, 0x70);
        releaseKernel = field<decltype(releaseKernel)>(table, 0xf8);
        releaseProgram = field<decltype(releaseProgram)>(table, 0xc0);
    }
};

inline bool clOk(int code, const char *operation) {
    if (code == 0) return true;
    __android_log_print(ANDROID_LOG_ERROR, "ClApi", "%s: OpenCL %d", operation, code);
    return false;
}


}  // namespace clapi
