// MonoPan50 grain helper: runs the original Xiaomi Monopan FilmNoise in a process of its own, with the direct capture's
// configuration (MonoPan50) or the gallery M3 edit's (MonoPan50 Pro).
//
// The vendor library carries its own OpenCL runtime and a packed loader that finds itself by file name in /proc/<pid>/maps;
// inside the camera process its OpenCL images cannot be created (MonopanInit then writes through a null buffer), in a
// plain process it works. The module starts this helper from service.sh (as root, from a copy of payload/monopan in
// /data/local/tmp, where the linker lets the library load the vendor OpenCL); the camera process hands it jobs through files (monopan_grain.cpp):
//   <job>/in.y   w * h bytes, full-range 8-bit luma
//   <job>/req    "w h variant" (written last; variant 1 MonoPan50, 2 MonoPan50 Pro)
//   <job>/out.y  the grained luma, then <job>/done (the request is removed first)
// usage: monopan_helper <library dir> <job dir>
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char kTag[] = "MonoPanHelper";

struct Config {
    uint32_t density;
    float grainSize;
    float mix;
    uint32_t kernelSize;
    float sigma;
    uint32_t mode;
    float curve[32];
    float curveEnd;
    uint32_t reserved;
    const char *openclBinaryPath;
};
struct Image {
    uint32_t format, width, height, planeCount;
    uint32_t stride[4], scanlines[4], planeBytes[4];
    uint8_t *planes[4];
    int32_t fds[4];
};
static_assert(sizeof(Image) == 112, "vendor image layout");
static_assert(sizeof(Config) == 168, "vendor config layout");

using Init = void *(*)(const Config *);
using Process = int (*)(void *, const Image *, Image *);
using Deinit = void (*)(void **);

// Stock 17U FilmNoise provider (direct capture).
Config directConfig(const char *clPath) {
    return {20, 0.8f, 0.3f, 3, 0.2f, 0,
            {0.098f, 0.114f, 0.129f, 0.153f, 0.176f, 0.204f, 0.231f, 0.255f, 0.282f, 0.306f, 0.337f,
             0.369f, 0.400f, 0.431f, 0.463f, 0.494f, 0.525f, 0.557f, 0.588f, 0.620f, 0.651f, 0.682f,
             0.710f, 0.741f, 0.769f, 0.800f, 0.827f, 0.855f, 0.882f, 0.914f, 0.941f, 0.969f},
            1.0f, 0, clPath};
}

// The gallery's M3 edit (LegendRefine init configuration, whose Monopan configuration starts at +8): coarser, heavier
// grain with an S-shaped tone.
Config galleryConfig(const char *clPath) {
    return {4, 1.8f, 0.3f, 7, 0.5f, 1,
            {0.0f, 0.0118f, 0.0251f, 0.0408f, 0.0601f, 0.0841f, 0.1132f, 0.1451f, 0.1789f, 0.2143f, 0.2511f,
             0.2891f, 0.3281f, 0.3677f, 0.4077f, 0.4483f, 0.4897f, 0.5317f, 0.5739f, 0.6163f, 0.6585f, 0.7004f,
             0.7417f, 0.7822f, 0.8213f, 0.8581f, 0.8917f, 0.9213f, 0.946f, 0.9654f, 0.9803f, 0.9916f},
            1.0f, 0, clPath};
}

bool readAll(const std::string &path, std::vector<uint8_t> &data, size_t size) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    data.resize(size);
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, data.data() + done, size - done);
        if (n <= 0) break;
        done += size_t(n);
    }
    close(fd);
    return done == size;
}

bool writeAll(const std::string &path, const uint8_t *data, size_t size, uid_t owner) {
    std::string temp = path + ".tmp";
    int fd = open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
    if (fd < 0) return false;
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, data + done, size - done);
        if (n <= 0) break;
        done += size_t(n);
    }
    fchown(fd, owner, owner);
    close(fd);
    if (done != size) return false;
    return rename(temp.c_str(), path.c_str()) == 0;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: monopan_helper <library dir> <job dir>\n");
        return 2;
    }
    const std::string libDir = argv[1], job = argv[2];
    // The vendor library finds itself in /proc/self/maps by its own file name: it must be loaded from a real file.
    dlopen((libDir + "/libmpancxx_sh.so").c_str(), RTLD_NOW | RTLD_GLOBAL);
    void *lib = dlopen((libDir + "/libmialgo_monopan.so").c_str(), RTLD_NOW);
    if (!lib) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "monopan not loadable: %s", dlerror());
        return 3;
    }
    auto init = reinterpret_cast<Init>(dlsym(lib, "MonopanInit"));
    auto process = reinterpret_cast<Process>(dlsym(lib, "MonopanProcess"));
    auto deinit = reinterpret_cast<Deinit>(dlsym(lib, "MonopanDeinit"));
    if (!init || !process || !deinit) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "monopan entry points missing");
        return 3;
    }
    struct stat jobStat {};
    if (stat(job.c_str(), &jobStat) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "job directory missing: %s", job.c_str());
        return 4;
    }
    const uid_t owner = jobStat.st_uid;  // the camera app: it reads the results
    const std::string clPath = libDir + "/mialgo_monopan_cl.bin";
    __android_log_print(ANDROID_LOG_INFO, kTag, "ready, job directory %s, owner %d", job.c_str(), int(owner));

    for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        FILE *request = std::fopen((job + "/req").c_str(), "re");
        if (!request) continue;
        unsigned w = 0, h = 0, variant = 1;
        int fields = std::fscanf(request, "%u %u %u", &w, &h, &variant);
        std::fclose(request);
        unlink((job + "/req").c_str());
        unlink((job + "/done").c_str());
        const auto began = std::chrono::steady_clock::now();
        std::vector<uint8_t> in;
        bool ok = fields >= 2 && w >= 2 && h >= 2 && readAll(job + "/in.y", in, size_t(w) * h);
        std::vector<uint8_t> out;
        int status = -1;
        if (ok) {
            out.assign(size_t(w) * h, 0);
            std::vector<uint8_t> uv(size_t(w) * h, 128), uvOut(size_t(w) * h);
            Config config = variant == 2 ? galleryConfig(clPath.c_str()) : directConfig(clPath.c_str());
            void *handle = init(&config);
            if (handle) {
                Image a{}, b{};
                for (Image *image : {&a, &b}) {
                    image->format = 2000;
                    image->width = w;
                    image->height = h;
                    image->planeCount = 2;
                    for (int p = 0; p < 2; ++p) {
                        image->stride[p] = w;
                        image->scanlines[p] = h;
                        image->planeBytes[p] = w * h;
                        image->fds[p] = -1;
                    }
                }
                a.planes[0] = in.data();
                a.planes[1] = uv.data();
                b.planes[0] = out.data();
                b.planes[1] = uvOut.data();
                status = process(handle, &a, &b);
                deinit(&handle);
            }
            if (status == 0 && !writeAll(job + "/out.y", out.data(), out.size(), owner)) status = -2;
        }
        const long ms = static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - began).count());
        __android_log_print(status == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag, "%ux%u variant %u: request %s, status %d, %ld ms", w, h, variant,
                            ok ? "read" : "invalid", status, ms);
        // The result marker says whether the grain was made: "ok" or "fail".
        const char *verdict = status == 0 ? "ok" : "fail";
        writeAll(job + "/done", reinterpret_cast<const uint8_t *>(verdict), std::strlen(verdict), owner);
    }
}
