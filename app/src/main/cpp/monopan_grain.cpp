// MonoPan50 on a master capture: the M3 vignette and the original Xiaomi Monopan FilmNoise grain, applied to the
// luma of the filter node's output (a P010 HLG signal of a grey image).
//
// Order and domain are the M3 chain's: the luma goes through the master display curve (the tone the M3 chain
// works on), gets the vignette, then the grain, and the result goes back to the HLG signal. Pixels are changed
// in 10-bit codes; the grain's 8-bit change is added to the vignetted value, not to a requantised one.
//
// Vignette: libM9PhotoFilter.so's CvStyleEffect `colorDark` (GLSL, ported as is). Its eight parameters come from
// leica_filter_param_m3.bin by scene lux and zoom (the vendor's own table, `m3_parameters_shading` of the M3 offline
// processor). The shader's x scale w / h becomes max(w, h) as the reference side, so the vignette does not depend on
// the buffer's storage orientation.
// Grain: the original libmialgo_monopan.so (plus the NDK C++ runtime it links, renamed libmpancxx_sh.so in both files)
// runs in the helper process (monopan_helper.cpp), because inside the camera process its OpenCL images cannot be
// created. The camera side hands it the 8-bit luma and reads the grained luma back through files.
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "monopan_tone.h"
#include "soft_light_curve.h"
#include "native_hooks.h"

namespace {

constexpr char kTag[] = "MonoPanGrain";
constexpr char kDir[] = "/data/user/0/com.oplus.camera/files/jiege/monopan";
constexpr int kDefaultLux = 184;
constexpr float kMainFocalMm = 23.0f;  // 35 mm equivalent of the main camera: zoom 1

std::atomic<int> gLux{kDefaultLux};
std::atomic<bool> gVignetteOn{true};  // the master 暗角 switch, as the capture reports it

// ---- the vendor's shading table ----------------------------------------------------------------------

struct Shading {
    std::vector<uint8_t> data;
    uint32_t zoomTables = 0, shadingData = 0;
    uint16_t zoomCount = 0;

    uint16_t u16(size_t at) const { return uint16_t(data[at] | data[at + 1] << 8); }
    float f32(size_t at) const {
        float v;
        std::memcpy(&v, &data[at], 4);
        return v;
    }

    bool load(const std::string &path) {
        FILE *file = std::fopen(path.c_str(), "rbe");
        if (!file) return false;
        std::fseek(file, 0, SEEK_END);
        long size = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        if (size < 16) { std::fclose(file); return false; }
        data.resize(size_t(size));
        bool ok = std::fread(data.data(), 1, data.size(), file) == data.size();
        std::fclose(file);
        if (!ok) return false;
        const unsigned scenes = u16(0), header = 2 + scenes * 2;
        const unsigned tables = u16(header), lut = u16(header + 2), zooms = u16(header + 6);
        unsigned cursor = tables, lutCount = 0;
        for (unsigned i = 0; i < scenes; ++i) {
            lutCount = std::max<unsigned>(lutCount, u16(cursor + 64 + 12) + 1u);
            cursor += u16(2 + i * 2);
        }
        zoomTables = cursor;
        zoomCount = uint16_t(zooms);
        shadingData = lut + lutCount * 17 * 17 * 17 * 3;
        return zooms > 0 && shadingData + 32 <= data.size();
    }

    // `output` = SmoothStartValue, SmoothEndValue, SmoothCoordScale, SmoothValueScale,
    // LightDarkPreserveK, B, V, T.
    void at(int lux, float zoom, float output[8]) const {
        constexpr unsigned kRow = 12;
        size_t table = zoomTables;
        for (unsigned i = 1; i < zoomCount; ++i) {
            size_t next = table + 6 + size_t(u16(table + 4)) * kRow;
            if (zoom < f32(next)) break;
            table = next;
        }
        const unsigned count = u16(table + 4);
        const size_t rows = table + 6;
        unsigned index = 0;
        while (index + 1 < count && lux > int(u16(rows + index * kRow + 2))) ++index;
        size_t high = rows + index * kRow, low = high;
        float weight = 0;
        if (index && lux < int(u16(high))) {
            low -= kRow;
            weight = float(lux - int(u16(low + 2))) / float(int(u16(high)) - int(u16(low + 2)));
        }
        size_t a = shadingData + size_t(u16(low + 10)) * 32, b = shadingData + size_t(u16(high + 10)) * 32;
        for (int i = 0; i < 8; ++i) output[i] = f32(a + i * 4) * (1.0f - weight) + f32(b + i * 4) * weight;
    }
};

bool shadingFor(int lux, float zoom, float out[8]) {
    static std::mutex lock;
    static Shading shading;
    static bool tried = false, ok = false;
    std::lock_guard<std::mutex> guard(lock);
    if (!tried) {
        tried = true;
        ok = shading.load(std::string(kDir) + "/leica_filter_param_m3.bin");
        if (!ok) __android_log_print(ANDROID_LOG_ERROR, kTag, "vignette table missing under %s", kDir);
    }
    if (ok) shading.at(lux, zoom, out);
    return ok;
}

// ---- the vignette (CvStyleEffect colorDark) ----------------------------------------------------------

float smoothstepAny(float e0, float e1, float x) {  // GLSL: the edges may be in either order
    float t = std::clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

struct Vignette {
    float start, end, coordScale, valueScale, k, b, v, t;
    int w, h;
    float side;
    float time;  // the shader's t = CurrentTime * (30 / 60) / 100

    // The luma `y` (0..1, display code) of the pixel (x, y) after the vignette.
    float apply(int px, int py, float luma) const {
        float ux = (float(px) + 0.5f) / float(w) - 0.5f, uy = (float(py) + 0.5f) / float(h) - 0.5f;
        float cx = ux * float(w) / side, cy = uy * float(h) / side;
        // n6rand(uv - 0.5): the mean of six sin hashes at time offsets 0.07 t .. 0.23 t. With t = 0 the six are one hash,
        // whose field is quasi-periodic and shows as twisted bands; with the shader's running time they decorrelate.
        float random = 0;
        for (float offset : {0.07f, 0.11f, 0.13f, 0.17f, 0.19f, 0.23f}) {
            float angle = (cx - 0.5f + offset * time) * 12.9898f + (cy - 0.5f + offset * time) * 78.233f;
            float s = std::sin(angle) * 43758.5453f;
            random += s - std::floor(s);
        }
        random /= 6.0f;
        float dist = std::sqrt(cx * cx + cy * cy) + 0.004f * random * 10.0f * coordScale;
        float vignette = smoothstepAny(start, end, dist);
        float preserve = v / (1.0f + std::exp(k * (-luma + b))) + t;  // L = Y for a grey pixel
        vignette = (1.0f - preserve) * vignette + preserve;
        vignette *= valueScale;
        return luma + luma * (vignette - 1.0f);
    }
};

// ---- the grain: the original Monopan library, in the helper process ---------------------------------

// The library cannot run inside the camera process (monopan_helper.cpp says why). The luma goes to the helper through
// files in the job directory and comes back the same way; without the helper (or on any failure) the frame keeps
// the vignette only.
constexpr char kJob[] = "/data/user/0/com.oplus.camera/files/jiege/monopan/job";

bool writeFile(const std::string &path, const uint8_t *data, size_t size) {
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0660);
    if (fd < 0) return false;
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, data + done, size - done);
        if (n <= 0) break;
        done += size_t(n);
    }
    close(fd);
    return done == size;
}

bool readFile(const std::string &path, uint8_t *data, size_t size) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, data + done, size - done);
        if (n <= 0) break;
        done += size_t(n);
    }
    close(fd);
    return done == size;
}

// Grains `in` (w * h bytes) into `out`; 0 on success.
int grainInHelper(const std::vector<uint8_t> &in, std::vector<uint8_t> &out, int w, int h, int variant) {
    static std::mutex lock;
    std::lock_guard<std::mutex> guard(lock);
    const std::string job = kJob;
    if (access(job.c_str(), W_OK) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "no job directory %s (errno %d)", kJob, errno);
        return -1;
    }
    unlink((job + "/done").c_str());
    if (!writeFile(job + "/in.y", in.data(), in.size())) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "job input not writable (errno %d)", errno);
        return -2;
    }
    char request[64];
    int length = std::snprintf(request, sizeof request, "%d %d %d", w, h, variant);
    if (!writeFile(job + "/req.tmp", reinterpret_cast<const uint8_t *>(request), size_t(length)) ||
        rename((job + "/req.tmp").c_str(), (job + "/req").c_str()) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "job request not writable (errno %d)", errno);
        return -3;
    }
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    char verdict[8] = {};
    for (;;) {
        FILE *done = std::fopen((job + "/done").c_str(), "re");
        if (done) {
            std::fread(verdict, 1, sizeof verdict - 1, done);
            std::fclose(done);
            break;
        }
        if (std::chrono::steady_clock::now() > deadline) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "the helper did not answer in 30 s (is monopan_helper running?)");
            unlink((job + "/req").c_str());
            return -4;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (std::strcmp(verdict, "ok") != 0) return -5;
    out.resize(in.size());
    return readFile(job + "/out.y", out.data(), out.size()) ? 0 : -6;
}

// ---- frame helpers ------------------------------------------------------------------------------------

float interpolate(const float *table, int count, float position) {
    position = std::clamp(position, 0.0f, float(count - 1));
    int i = std::min(static_cast<int>(position), count - 2);
    return table[i] + (table[i + 1] - table[i]) * (position - i);
}

template <typename Body>
void parallelRows(int rows, Body body) {
    unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    std::atomic<int> next{0};
    for (unsigned i = 0; i < threads; ++i)
        pool.emplace_back([&] {
            for (int r; (r = next.fetch_add(1)) < rows;) body(r);
        });
    for (auto &thread : pool) thread.join();
}

}  // namespace

void monoPanSetFrameLux(int lux) { gLux = std::clamp(lux, 0, 999); }

void monoPanSetVignette(bool on) { gVignetteOn = on; }

bool monoPanFrame(AHardwareBuffer *buffer, bool fullRange) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    const int w = int(desc.width) & ~1, h = int(desc.height) & ~1;
    const float focal = softLightFocalMm();
    const float zoom = focal > 0 ? focal / kMainFocalMm : 1.0f;
    const int lux = gLux.load();
    float shading[8] = {};
    const bool haveVignette = gVignetteOn.load() && shadingFor(lux, zoom, shading);
    const bool haveGrain = access(kJob, W_OK) == 0;

    using LockPlanes = int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *);
    static auto lockPlanes = reinterpret_cast<LockPlanes>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_lockPlanes"));
    AHardwareBuffer_Planes planes{};
    if (!lockPlanes || lockPlanes(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                                  nullptr, &planes) != 0 || planes.planeCount < 3) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "frame not lockable (format 0x%x)", desc.format);
        return false;
    }
    auto *yBase = static_cast<uint8_t *>(planes.planes[0].data);
    const size_t stride = planes.planes[0].rowStride;  // the luma plane reports pixel stride 1 but holds 16-bit words
    auto word = [&](int x, int y) { return reinterpret_cast<uint16_t *>(yBase + size_t(y) * stride + size_t(x) * 2); };
    bool lowBits = false;  // 10-bit codes at the top of the word leave the low 6 bits empty
    for (int y = 0; y < h && !lowBits; y += 97)
        for (int x = 0; x < w; x += 13) lowBits |= (*word(x, y) & 0x3f) != 0;
    const bool msb = !lowBits;
    // White noise in [-0.5, 0.5) per pixel (integer hash): dithers the 10-bit input before the tone and the output before rounding.
    auto dither = [](int x, int y, uint32_t salt) {
        uint32_t n = uint32_t(x) * 73856093u ^ uint32_t(y) * 19349663u ^ salt;
        n ^= n >> 16; n *= 0x7feb352du; n ^= n >> 15; n *= 0x846ca68bu; n ^= n >> 16;
        return float(n >> 8) * (1.0f / 16777216.0f) - 0.5f;
    };
    auto fromHlg = [&](float signal, int x, int y) {
        float code = (fullRange ? signal * 1023.0f : 64.0f + signal * 876.0f) + dither(x, y, 0x9e3779b9u);
        int q = int(std::lround(std::clamp(code, 0.0f, 1023.0f)));
        return uint16_t(msb ? q << 6 : q);
    };
    // The grey HLG frame -> scene light (master neutral curve) -> the M3 display code (Gamma1774), all in float.
    auto displayOf = [&](uint16_t raw, int x, int y) {
        float code = float(msb ? raw >> 6 : raw & 0x3ff) + dither(x, y, 0x85ebca6bu);
        float signal = std::clamp(fullRange ? code / 1023.0f : (code - 64.0f) / 876.0f, 0.0f, 1.0f);
        float scene = interpolate(kSoftHlgToScene, kSoftCurveN, signal * (kSoftCurveN - 1));
        return interpolate(kM3Gamma, kM3GammaN, scene * (kM3GammaN - 1));
    };

    Vignette vignette{shading[0], shading[1], shading[2], shading[3], shading[4], shading[5], shading[6], shading[7],
                      w, h, float(std::max(w, h)), 1.0f + float(std::fmod(std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count(), 7.0))};
    auto vignetted = [&](int x, int y, uint16_t raw) {
        float display = std::clamp(displayOf(raw, x, y), 0.0f, 1.0f);
        return haveVignette ? std::clamp(vignette.apply(x, y, display), 0.0f, 1.0f) : display;
    };

    const auto began = std::chrono::steady_clock::now();
    std::vector<uint8_t> in(size_t(w) * h);
    parallelRows(h, [&](int y) {
        for (int x = 0; x < w; ++x) in[size_t(y) * w + x] = uint8_t(std::lround(vignetted(x, y, *word(x, y)) * 255.0f));
    });

    std::vector<uint8_t> out;
    int status = haveGrain ? grainInHelper(in, out, w, h, monoPanVariant()) : 0;
    const bool grained = haveGrain && status == 0;
    // The grain moves the mean of the 8-bit levels it is given (the film's own tone shift), and that mean jumps from level
    // to level; added per level it becomes steps of a few codes across every smooth gradient, which the HDR highlights
    // show as bands. The per-level mean is taken out of the grain's change and a smoothed copy of it, read at the
    // unquantised value, is put back.
    float levelMean[256] = {}, toneShift[256] = {};
    if (grained) {
        double sum[256] = {}, count[256] = {};
        for (size_t i = 0; i < in.size(); i += 5) {
            sum[in[i]] += int(out[i]) - int(in[i]);
            count[in[i]] += 1;
        }
        for (int level = 0; level < 256; ++level) levelMean[level] = count[level] > 0 ? float(sum[level] / count[level]) : 0.0f;
        for (int level = 0; level < 256; ++level) {
            double weighted = 0, weights = 0;
            for (int k = std::max(0, level - 8); k <= std::min(255, level + 8); ++k) {
                double weight = count[k] * std::exp(-0.5 * double((k - level) * (k - level)) / 9.0);
                weighted += weight * levelMean[k];
                weights += weight;
            }
            toneShift[level] = weights > 0 ? float(weighted / weights) : 0.0f;
        }
    }
    parallelRows(h, [&](int y) {
        for (int x = 0; x < w; ++x) {
            uint16_t *p = word(x, y);
            float display = vignetted(x, y, *p);
            if (grained) {
                const int level = in[size_t(y) * w + x];
                const float shift = interpolate(toneShift, 256, std::clamp(display, 0.0f, 1.0f) * 255.0f);
                display += (float(int(out[size_t(y) * w + x]) - level) - levelMean[level] + shift) / 255.0f;
            }
            float signal = interpolate(kM3HlgOfDisplay, kM3DisplayN, std::clamp(display, 0.0f, 1.0f) * (kM3DisplayN - 1));
            *p = fromHlg(signal, x, y);
        }
    });
    AHardwareBuffer_unlock(buffer, nullptr);
    __android_log_print(haveGrain && !grained ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, kTag,
                        "%dx%d lux %d zoom %.2f: vignette %s (start %.2f end %.2f scale %.2f/%.2f), grain %s (status %d), %lld ms", w, h,
                        lux, double(zoom), haveVignette ? "on" : "missing", double(shading[0]), double(shading[1]), double(shading[2]),
                        double(shading[3]), grained ? "on" : "off", status,
                        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - began).count());
    return status == 0;
}
