// Soft light replacement: the weak level (柔美, Meishe ProMist type 1) renders as a Ricoh GR HDF,
// the medium level (朦胧, type 0) as a 1/4 black mist, the strong level (梦幻, type 2) as a white
// mist.
//
// Every soft light runs in libmsnativefilter on the camera's HLG frame: preview through
// MsRenderHDRTexture (an RGBA8 HLG texture), capture through MsRenderHardwareBuffer (a P010
// hardware buffer). For the replaced levels the hooks switch the stock ProMist off, scatter the
// filter's input frame, and let Meishe render the filter (look, palette, film) on the scattered
// frame, so the halo is made of light before any tone shaping:
//
//   out_c = (core_c * I_c + sum_i w_c,i * L_i,c) / transmission_c      in scene light E (HLG)
//
// L_i is a blur pyramid (13-tap 2x downsample per level, back up through a chain of 2x tent
// upsamples, U_i = w_i * L_i + tent_up(U_i+1)); the weights per level and channel distil a
// physical-optics simulation of each filter (soft_light_weights.h, tools/softlight). The point
// spread is an angle in front of the lens, so the table row follows the focal length in pixels:
//   f_px = focal_35mm / 43.267 * diagonal_px
// The input HLG signal is the master LMT output, so it is decoded to scene light through the master
// pipeline's neutral tone curve (soft_light_curve.h, tools/softlight/gen_curve.py: builtin gamma
// then the stock LMT grey axis, per channel) and encoded back through the same curve after the
// scatter; light above the sensor clip goes through a soft shoulder on the peak channel, channels
// scaled together so a halo keeps the hue of its source. A source clipped in the Hybrid Raw is
// brighter than recorded (a desk lamp in RAWMax: at least 1.8 times the white level, from its
// least clipped channel), and its true light both scatters and passes the filter unscattered. For
// the HDF that light is kSourceGain times the recorded value at the clip: the pyramid sees pixels
// scaled up by a gain rising linearly in log2 of the peak channel from 1 at boostStops below the
// clip, and the unscattered core is scaled up where the surroundings within kSourceDeg sit within
// kSourceStops of the clip (the local mean, not the pixel, so texture is never expanded), so the
// shoulder flattens the source into one even glow as a single exposure clips it. With the source's
// true light carrying the halo, the HDF's scatter fraction is about 20 % and the rest of the frame
// stays clear. Black and white mist scatter clipped pixels at their recorded light: on the master
// frame lit white surfaces reach the clip too, and boosting them through a wide halo makes them
// glow like lights.
// Preview runs the passes in Meishe's GL context into a scratch texture; capture runs the same
// passes on the GPU in a context of its own on a copy of the input planes (P010 or NV21), and on the
// CPU when that is not possible.
#include <EGL/egl.h>
#include <dlfcn.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <jni.h>
#include <sys/system_properties.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <vector>

#include "hncs_grain.h"
#include "x100vi_grain.h"
#include "native_hooks.h"
#include "soft_light_curve.h"
#include "soft_light_weights.h"

namespace {

constexpr const char *TAG = "SoftLight";
constexpr float kFullFrameDiagonalMm = 43.267f;
constexpr float kHdfFocalMm = 28.0f;  // the GR lens the HDF belongs to
constexpr float kShoulder = 0.8f;   // of the top: the sensor clip (HLG) or display white (sRGB)
constexpr float kSourceDeg = 0.1f;    // neighbourhood that decides whether a pixel lies in a light source
constexpr float kSourceStops = 0.3f;  // the core gate opens over this many stops below the clip
constexpr float kSourceGain = 2.6f;   // HDF: true light of a clipped source over its recorded value

std::atomic<float> gFocalMm{0.0f};  // 0 until the zoom bar reports
std::atomic<bool> gMasterMode{false};  // the camera is in 大师模式; every other mode keeps the stock path
std::atomic<bool> gNoLocalContrast{false};  // setting: captures with a replaced soft light skip the fusion
std::atomic<bool> gCaptureSoft{false};  // the capture HDRTransform is working on carries a replaced soft light
std::atomic<int> gLevels{0};  // replaced levels, bit = ProMist type; a clear bit leaves the stock soft light

// SMsEffectParam: the ProMist switch and type.
constexpr size_t kSoftEnable = 0x48;
constexpr size_t kSoftType = 0x4c;

const SoftFilter *filterFor(const void *param) {
    auto *p = static_cast<const uint8_t *>(param);
    if (!p || !p[kSoftEnable] || !gMasterMode.load()) return nullptr;
    int32_t type;
    std::memcpy(&type, p + kSoftType, sizeof type);
    if (type < 0 || type > 2 || !(gLevels.load() & (1 << type))) return nullptr;
    if (type == 1) return &kHdf;
    if (type == 0) return &kBlackMist;
    if (type == 2) return &kWhiteMist;
    return nullptr;
}

// Stock ProMist off for one render call.
class StockSoftOff {
public:
    explicit StockSoftOff(const void *param) : flag_(const_cast<uint8_t *>(static_cast<const uint8_t *>(param)) + kSoftEnable) {
        *flag_ = 0;
    }
    ~StockSoftOff() { *flag_ = 1; }
private:
    uint8_t *flag_;
};

enum Transfer { kSrgb, kHlg };

// Per-level, per-channel weights for an image of this diagonal. Beyond the end of the table the
// pyramid base moves up one level per doubling of the focal length.
struct LevelWeights {
    float core[3];
    float transmission[3];
    float w[3][kSoftLevels];
};

LevelWeights levelWeights(const SoftFilter &f, float diagonalPx, int builtLevels) {
    // The HDF sits in the GR's own 28 mm lens: its halo spans the same share of the frame at every
    // zoom. Black and white mist are filters in front of the lens in use and follow its focal length.
    float focal = &f == &kHdf ? kHdfFocalMm : gFocalMm.load();
    if (focal <= 0) focal = 23.0f;  // main camera at 1x until the zoom bar reports
    float l2 = std::log2(focal / kFullFrameDiagonalMm * diagonalPx);
    float last = kSoftLog2First + kSoftLog2Step * (kSoftRows - 1);
    int shift = 0;
    while (l2 - shift > last) shift++;
    float pos = std::clamp((l2 - shift - kSoftLog2First) / kSoftLog2Step, 0.0f, float(kSoftRows - 1));
    int r0 = int(pos);
    int r1 = std::min(r0 + 1, kSoftRows - 1);
    float t = pos - r0;
    LevelWeights out{};
    for (int c = 0; c < 3; c++) {
        out.core[c] = f.core[c];
        out.transmission[c] = f.transmission[c];
        for (int i = 0; i < kSoftLevels; i++) {
            int level = std::min(i + shift, builtLevels);  // levels the image is too small for go to the coarsest
            out.w[c][level] += f.weights[r0][c][i] * (1 - t) + f.weights[r1][c][i] * t;
        }
    }
    return out;
}

// Light of a clipped source over its recorded value: the HDF only, 1 elsewhere.
float sourceGain(const SoftFilter &f) { return &f == &kHdf ? kSourceGain : 1.0f; }

// Pyramid level (1 = half size) whose texels average about kSourceDeg, for this image diagonal.
int sourceLevel(const SoftFilter &f, float diagonalPx, int builtLevels) {
    float focal = &f == &kHdf ? kHdfFocalMm : gFocalMm.load();
    if (focal <= 0) focal = 23.0f;
    float px = focal / kFullFrameDiagonalMm * diagonalPx * kSourceDeg * float(M_PI) / 180.0f;
    return std::clamp(int(std::lround(std::log2(std::max(px, 1.0f)))), 1, builtLevels);
}

// ---- transfer functions: signal <-> scene light ---------------------------------------------

inline float tableAt(const float *t, float pos) {
    pos = std::clamp(pos, 0.0f, float(kSoftCurveN - 1));
    int i = std::min(int(pos), kSoftCurveN - 2);
    float f = pos - i;
    return t[i] * (1 - f) + t[i + 1] * f;
}

inline float decode(float v, Transfer tr) {
    v = std::clamp(v, 0.0f, 1.0f);
    if (tr == kHlg) return tableAt(kSoftHlgToScene, v * (kSoftCurveN - 1));
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

inline float encode(float e, Transfer tr) {
    if (tr == kHlg) {
        float lo = std::exp2(kSoftLog2SceneMin);
        if (e <= lo) return std::max(e, 0.0f) / lo * kSoftSceneToHlg[0];
        float pos = (std::log2(e) - kSoftLog2SceneMin) / (kSoftLog2SceneMax - kSoftLog2SceneMin) * (kSoftCurveN - 1);
        return tableAt(kSoftSceneToHlg, pos);
    }
    e = std::clamp(e, 0.0f, 1.0f);
    return e <= 0.0031308f ? 12.92f * e : 1.055f * std::pow(e, 1.0f / 2.4f) - 0.055f;
}

inline float topOf(Transfer tr) { return tr == kHlg ? kSoftSceneMax : 1.0f; }

inline void shoulder(float rgb[3], float top) {
    float peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    float knee = kShoulder * top;
    if (peak <= knee) return;
    float comp = knee + (top - knee) * std::tanh((peak - knee) / (top - knee));
    for (int c = 0; c < 3; c++) rgb[c] *= comp / peak;
}

// Stops below the clip where the boost starts: the top half stop of the frame, the shoulder knee on display white.
inline float boostStops(Transfer tr) { return tr == kHlg ? 0.5f : -std::log2(kShoulder); }

// Scatter source: pixels near the clip scaled up, gain times at the clip.
inline void boostClipped(float rgb[3], float log2Top, float stops, float gain) {
    float peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    if (peak <= 0.0f) return;
    float t = (std::log2(peak) - (log2Top - stops)) / stops;
    if (t <= 0.0f) return;
    float s = 1.0f + (gain - 1.0f) * std::min(t, 1.0f);
    for (int c = 0; c < 3; c++) rgb[c] *= s;
}

// Core gain for a neighbourhood whose peak channel averages `peak`.
inline float coreGain(float peak, float log2Top, float gain) {
    if (gain <= 1.0f || peak <= 0.0f) return 1.0f;
    float t = std::clamp((std::log2(peak) - (log2Top - kSourceStops)) / kSourceStops, 0.0f, 1.0f);
    return 1.0f + (gain - 1.0f) * t;
}

// ---- capture: CPU pyramid on the planes (the GPU path's fallback) --------------------------------------------------

template <typename F>
void parallelRows(int rows, F &&body) {
    int n = std::clamp(int(std::thread::hardware_concurrency()), 1, 8);
    std::vector<std::thread> pool;
    for (int k = 0; k < n; k++) {
        pool.emplace_back([&, k] {
            for (int y = k; y < rows; y += n) body(y);
        });
    }
    for (auto &t : pool) t.join();
}

struct Image {
    int w = 0, h = 0;
    std::vector<float> px;  // RGB
    float *at(int x, int y) { return &px[(size_t(y) * w + x) * 3]; }
    const float *at(int x, int y) const { return &px[(size_t(y) * w + x) * 3]; }
};

inline int reflect(int i, int n) {  // cv2.BORDER_REFLECT, as the weights were fitted with
    while (i < 0 || i >= n) i = i < 0 ? -i - 1 : 2 * n - i - 1;
    return i;
}

// PASS_DOWN: every tap sits between four texels, i.e. a 2x2 mean at an integer offset.
template <typename Fetch>
Image downsample(int sw, int sh, Fetch fetch) {
    Image d;
    d.w = sw / 2;
    d.h = sh / 2;
    d.px.assign(size_t(d.w) * d.h * 3, 0.0f);
    static const struct { int dx, dy; float w; } taps[] = {
        {0, 0, 0.125f}, {-1, -1, 0.125f}, {1, -1, 0.125f}, {-1, 1, 0.125f}, {1, 1, 0.125f},
        {-2, -2, 0.03125f}, {2, -2, 0.03125f}, {-2, 2, 0.03125f}, {2, 2, 0.03125f},
        {-2, 0, 0.0625f}, {2, 0, 0.0625f}, {0, -2, 0.0625f}, {0, 2, 0.0625f}};
    parallelRows(d.h, [&](int y) {
        for (int x = 0; x < d.w; x++) {
            float acc[3] = {0, 0, 0};
            for (auto &tap : taps) {
                int x0 = 2 * x + tap.dx, y0 = 2 * y + tap.dy;
                float box[3] = {0, 0, 0};
                for (int j = 0; j < 2; j++)
                    for (int i = 0; i < 2; i++) {
                        float v[3];
                        fetch(reflect(x0 + i, sw), reflect(y0 + j, sh), v);
                        for (int c = 0; c < 3; c++) box[c] += v[c];
                    }
                for (int c = 0; c < 3; c++) acc[c] += tap.w * 0.25f * box[c];
            }
            std::memcpy(d.at(x, y), acc, sizeof acc);
        }
    });
    return d;
}

// 2x tent upsample ([1 2 1]^2 / 16 of bilinear fetches one coarse texel apart) as a separable
// 4-tap kernel: even output 2k reads k-2..k+1, odd output 2k+1 reads k-1..k+2.
constexpr float kTentEven[4] = {0.0625f, 0.3125f, 0.4375f, 0.1875f};
constexpr float kTentOdd[4] = {0.1875f, 0.4375f, 0.3125f, 0.0625f};

inline void tentAt(const Image &s, int x, int y, float out[3]) {
    int bx = (x >> 1) - ((x & 1) ? 1 : 2), by = (y >> 1) - ((y & 1) ? 1 : 2);
    const float *wx = (x & 1) ? kTentOdd : kTentEven;
    const float *wy = (y & 1) ? kTentOdd : kTentEven;
    out[0] = out[1] = out[2] = 0;
    for (int j = 0; j < 4; j++) {
        int sy = reflect(by + j, s.h);
        for (int i = 0; i < 4; i++) {
            const float *v = s.at(reflect(bx + i, s.w), sy);
            float w = wx[i] * wy[j];
            for (int c = 0; c < 3; c++) out[c] += w * v[c];
        }
    }
}

// Level texel (x, y) covers full-resolution pixels around ((x + 0.5) * 2^k - 0.5): bilinear, clamped.
inline void levelAt(const Image &s, int k, int x, int y, float out[3]) {
    float scale = 1.0f / float(1 << k);
    float fx = std::clamp((x + 0.5f) * scale - 0.5f, 0.0f, float(s.w - 1));
    float fy = std::clamp((y + 0.5f) * scale - 0.5f, 0.0f, float(s.h - 1));
    int x0 = int(fx), y0 = int(fy), x1 = std::min(x0 + 1, s.w - 1), y1 = std::min(y0 + 1, s.h - 1);
    float ax = fx - x0, ay = fy - y0;
    const float *a = s.at(x0, y0), *b = s.at(x1, y0), *c = s.at(x0, y1), *d = s.at(x1, y1);
    for (int i = 0; i < 3; i++) out[i] = (a[i] * (1 - ax) + b[i] * ax) * (1 - ay) + (c[i] * (1 - ax) + d[i] * ax) * ay;
}

struct YuvCoding {
    float kr, kb;
    bool full;
    bool msb;   // 10 significant bits at the top of each 16-bit word
};

YuvCoding yuvCoding(int matrix, int range) {
    // EMsYuvColorMatrix 0 / 1 / 2 = BT.601 / BT.709 / BT.2020; EMsYuvRange 1 = full.
    YuvCoding y{0.299f, 0.114f, range == 1, false};
    if (matrix == 1) { y.kr = 0.2126f; y.kb = 0.0722f; }
    if (matrix == 2) { y.kr = 0.2627f; y.kb = 0.0593f; }
    return y;
}

inline float codeToUnit(uint16_t raw, const YuvCoding &c) { return float(c.msb ? raw >> 6 : raw & 0x3ff); }
// Triangular dither of +-1 code from the pixel position: the scattered change is smooth, and
// rounding it to 10 bits un-dithered leaves steps that the filter's tone curve stretches into bands.
inline float dither(int x, int y, int salt) {
    auto h = [](uint32_t v) { v ^= v >> 16; v *= 0x7feb352dU; v ^= v >> 15; v *= 0x846ca68bU; v ^= v >> 16; return float(v & 0xffffff) / float(0x1000000); };
    uint32_t k = uint32_t(x) * 73856093U ^ uint32_t(y) * 19349663U ^ uint32_t(salt) * 83492791U;
    return h(k) + h(k + 0x9e3779b9U) - 1.0f;
}

inline uint16_t unitToCode(float v, const YuvCoding &c) {
    int q = int(std::lround(std::clamp(v, 0.0f, 1023.0f)));
    return uint16_t(c.msb ? q << 6 : q);
}

// The scatter works on a frame's samples in 10-bit code units, whatever the storage: P010 words
// (10 bits at the top or the bottom of each 16-bit word; the AHardwareBuffer planes and the host
// buffers share the layout) and 8-bit NV21 (codes scaled up to the same units).
struct P010Image {
    uint8_t *yBase = nullptr, *uBase = nullptr, *vBase = nullptr;
    size_t yStride = 0, uStride = 0, vStride = 0;
    uint32_t uPixel = 4, vPixel = 4;
    int w = 0, h = 0;
    YuvCoding c{};
    uint16_t *pY(int x, int r) { return reinterpret_cast<uint16_t *>(yBase + size_t(r) * yStride) + x; }
    uint16_t *pU(int x, int r) { return reinterpret_cast<uint16_t *>(uBase + size_t(r) * uStride + size_t(x) * uPixel); }
    uint16_t *pV(int x, int r) { return reinterpret_cast<uint16_t *>(vBase + size_t(r) * vStride + size_t(x) * vPixel); }
    void detectJustification() {
        // 10-bit codes at the top of the word leave the low 6 bits empty.
        bool lowBits = false;
        for (int r = 0; r < h && !lowBits; r += 97)
            for (int x = 0; x < w; x += 13) lowBits |= (*pY(x, r) & 0x3f) != 0;
        c.msb = !lowBits;
    }
    const char *name() const { return c.msb ? "MSB P010" : "LSB P010"; }
    float getY(int x, int r) { return codeToUnit(*pY(x, r), c); }
    float getU(int x, int r) { return codeToUnit(*pU(x, r), c); }
    float getV(int x, int r) { return codeToUnit(*pV(x, r), c); }
    void addY(int x, int r, float d, float dith) { uint16_t *p = pY(x, r); *p = unitToCode(codeToUnit(*p, c) + d + dith, c); }
    void addU(int x, int r, float d, float dith) { uint16_t *p = pU(x, r); *p = unitToCode(codeToUnit(*p, c) + d + dith, c); }
    void addV(int x, int r, float d, float dith) { uint16_t *p = pV(x, r); *p = unitToCode(codeToUnit(*p, c) + d + dith, c); }
};

struct Nv21Image {
    uint8_t *yBase = nullptr, *vuBase = nullptr;
    size_t yStride = 0, vuStride = 0;
    int w = 0, h = 0;
    YuvCoding c{};
    float scale = 4.0f;  // 10-bit units per 8-bit code: 64..940 over 16..235, 0..1023 over 0..255
    Nv21Image(uint8_t *y, size_t ys, uint8_t *vu, size_t vus, int width, int height, YuvCoding coding)
        : yBase(y), vuBase(vu), yStride(ys), vuStride(vus), w(width), h(height), c(coding), scale(coding.full ? 1023.0f / 255.0f : 4.0f) {}
    const char *name() const { return "NV21"; }
    float getY(int x, int r) { return float(yBase[size_t(r) * yStride + x]) * scale; }
    float getU(int x, int r) { return float(vuBase[size_t(r) * vuStride + 2 * size_t(x) + 1]) * scale; }
    float getV(int x, int r) { return float(vuBase[size_t(r) * vuStride + 2 * size_t(x)]) * scale; }
    static void add(uint8_t *p, float units, float scale, float dith) {
        *p = uint8_t(std::clamp(int(std::lround(float(*p) + units / scale + dith)), 0, 255));
    }
    void addY(int x, int r, float d, float dith) { add(&yBase[size_t(r) * yStride + x], d, scale, dith); }
    void addU(int x, int r, float d, float dith) { add(&vuBase[size_t(r) * vuStride + 2 * size_t(x) + 1], d, scale, dith); }
    void addV(int x, int r, float d, float dith) { add(&vuBase[size_t(r) * vuStride + 2 * size_t(x)], d, scale, dith); }
};

// Encoded (non-linear) RGB of one pixel from its YUV codes.
inline void yuvToRgb(float Y, float U, float V, const YuvCoding &c, float rgb[3]) {
    float y = c.full ? Y / 1023.0f : (Y - 64.0f) / 876.0f;
    float cb = c.full ? (U - 512.0f) / 1023.0f : (U - 512.0f) / 896.0f;
    float cr = c.full ? (V - 512.0f) / 1023.0f : (V - 512.0f) / 896.0f;
    float kg = 1 - c.kr - c.kb;
    rgb[0] = y + 2 * (1 - c.kr) * cr;
    rgb[2] = y + 2 * (1 - c.kb) * cb;
    rgb[1] = (y - c.kr * rgb[0] - c.kb * rgb[2]) / kg;
}

inline void rgbToYuvDelta(const float d[3], const YuvCoding &c, float &dy, float &du, float &dv) {
    float kg = 1 - c.kr - c.kb;
    float y = c.kr * d[0] + kg * d[1] + c.kb * d[2];
    float cb = (d[2] - y) / (2 * (1 - c.kb));
    float cr = (d[0] - y) / (2 * (1 - c.kr));
    dy = y * (c.full ? 1023.0f : 876.0f);
    du = cb * (c.full ? 1023.0f : 896.0f);
    dv = cr * (c.full ? 1023.0f : 896.0f);
}

// A frame's planes in host memory: P010 (U, V interleaved as 16-bit words) or NV21 (V, U bytes).
struct YuvPlanes {
    bool p010;
    uint8_t *y, *uv;
    size_t yStride, uvStride;  // bytes
    int w, h;
    YuvCoding coding;
};
// Scatters the planes in place on the GPU; false leaves them untouched for the CPU path.
bool scatterYuvGpu(const YuvPlanes &frame, const SoftFilter &filter, Transfer tr);

// A copy of the filter's input frame, so the scattered frame never reaches the pipeline's own buffer.
AHardwareBuffer *copyP010(AHardwareBuffer *src) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(src, &desc);
    AHardwareBuffer *dst = nullptr;
    if (AHardwareBuffer_allocate(&desc, &dst) != 0 || !dst) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "capture: input copy not allocatable (format 0x%x)", desc.format);
        return nullptr;
    }
    using LockPlanes = int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *);
    static auto lockPlanes = reinterpret_cast<LockPlanes>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_lockPlanes"));
    AHardwareBuffer_Planes ps{}, pd{};
    if (!lockPlanes || lockPlanes(src, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &ps) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "capture: input not readable (format 0x%x)", desc.format);
        AHardwareBuffer_release(dst);
        return nullptr;
    }
    if (lockPlanes(dst, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pd) != 0 || ps.planeCount < 3 || pd.planeCount < 3) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "capture: input copy not writable (format 0x%x)", desc.format);
        AHardwareBuffer_unlock(src, nullptr);
        AHardwareBuffer_release(dst);
        return nullptr;
    }
    int w = int(desc.width) & ~1, h = int(desc.height) & ~1;
    for (int pl = 0; pl < 3; pl++) {
        int pw = pl ? w / 2 : w, ph = pl ? h / 2 : h;
        auto *s = static_cast<const uint8_t *>(ps.planes[pl].data);
        auto *d = static_cast<uint8_t *>(pd.planes[pl].data);
        // The luma plane reports a pixel stride of 1 but holds 16-bit samples.
        uint32_t sp = pl ? ps.planes[pl].pixelStride : 2, dp = pl ? pd.planes[pl].pixelStride : 2, sr = ps.planes[pl].rowStride, dr = pd.planes[pl].rowStride;
        parallelRows(ph, [&](int r) {
            if (sp == 2 && dp == 2) {
                std::memcpy(d + size_t(r) * dr, s + size_t(r) * sr, size_t(pw) * 2);
            } else {
                for (int x = 0; x < pw; x++)
                    std::memcpy(d + size_t(r) * dr + size_t(x) * dp, s + size_t(r) * sr + size_t(x) * sp, 2);
            }
        });
    }
    AHardwareBuffer_unlock(dst, nullptr);
    AHardwareBuffer_unlock(src, nullptr);
    return dst;
}

// `Img` is a P010Image or Nv21Image over the (private copy of the) filter's input frame; `coding` is
// its YUV matrix and range, `tr` the transfer its codes carry.
template <typename Img>
void scatterImage(Img &img, const SoftFilter &filter, Transfer tr, const YuvCoding &coding) {
    // Level 0: scene light of every pixel (chroma shared by its 2x2 block), half precision.
    struct {
        int w, h;
        std::vector<__fp16> px;
        __fp16 *at(int x, int y) { return &px[(size_t(y) * w + x) * 3]; }
        void get(int x, int y, float v[3]) { const __fp16 *p = at(x, y); v[0] = p[0]; v[1] = p[1]; v[2] = p[2]; }
    } l0{img.w, img.h, {}};
    l0.px.resize(size_t(l0.w) * l0.h * 3);
    parallelRows(img.h / 2, [&](int by) {
        for (int bx = 0; bx < img.w / 2; bx++) {
            float U = img.getU(bx, by), V = img.getV(bx, by);
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    int x = 2 * bx + i, y = 2 * by + j;
                    float rgb[3];
                    yuvToRgb(img.getY(x, y), U, V, coding, rgb);
                    __fp16 *e = l0.at(x, y);
                    for (int c = 0; c < 3; c++) e[c] = __fp16(decode(rgb[c], tr));
                }
        }
    });

    std::vector<Image> levels;
    {
        int w = l0.w, h = l0.h;
        float log2Top = std::log2(topOf(tr)), stops = boostStops(tr), boost = sourceGain(filter);
        auto fetch0 = [&](int x, int y, float v[3]) {
            l0.get(x, y, v);
            boostClipped(v, log2Top, stops, boost);
        };
        levels.push_back(downsample(w, h, fetch0));
        while (int(levels.size()) < kSoftLevels - 1 && std::min(levels.back().w, levels.back().h) >= 4) {
            const Image &s = levels.back();
            levels.push_back(downsample(s.w, s.h, [&](int x, int y, float v[3]) { std::memcpy(v, s.at(x, y), 3 * sizeof(float)); }));
        }
    }
    int built = int(levels.size());
    LevelWeights lw = levelWeights(filter, std::hypot(float(l0.w), float(l0.h)), built);
    float gain = sourceGain(filter), log2Top = std::log2(topOf(tr));
    int gateLevel = sourceLevel(filter, std::hypot(float(l0.w), float(l0.h)), built);
    Image gate;
    if (gain > 1.0f) gate = levels[gateLevel - 1];

    // Up chain: U_n = w_n L_n; U_i = w_i L_i + tent_up(U_i+1).
    Image up = levels[built - 1];
    for (size_t k = 0; k < up.px.size(); k++) up.px[k] *= lw.w[k % 3][built];
    for (int i = built - 1; i >= 1; i--) {
        Image &L = levels[i - 1];
        Image next;
        next.w = L.w;
        next.h = L.h;
        next.px.resize(L.px.size());
        parallelRows(L.h, [&](int y) {
            for (int x = 0; x < L.w; x++) {
                float t[3];
                tentAt(up, x, y, t);
                const float *l = L.at(x, y);
                float *o = next.at(x, y);
                for (int c = 0; c < 3; c++) o[c] = lw.w[c][i] * l[c] + t[c];
            }
        });
        up = std::move(next);
    }
    levels.clear();

    // Composite and write back the change of each pixel's encoded RGB, chroma per 2x2 block.
    std::atomic<int64_t> changed{0};
    parallelRows(img.h / 2, [&](int by) {
        for (int bx = 0; bx < img.w / 2; bx++) {
            float dU = 0, dV = 0;
            for (int j = 0; j < 2; j++)
                for (int i = 0; i < 2; i++) {
                    int x = 2 * bx + i, y = 2 * by + j;
                    float e[3];
                    l0.get(x, y, e);
                    float t[3];
                    tentAt(up, x, y, t);
                    float acc[3], d[3];
                    float k = 1.0f;
                    if (gain > 1.0f) {
                        float m[3];
                        levelAt(gate, gateLevel, x, y, m);
                        k = coreGain(std::max(m[0], std::max(m[1], m[2])), log2Top, gain);
                    }
                    for (int c = 0; c < 3; c++) acc[c] = ((lw.core[c] + lw.w[c][0]) * k * e[c] + t[c]) / lw.transmission[c];
                    shoulder(acc, topOf(tr));
                    for (int c = 0; c < 3; c++) d[c] = encode(acc[c], tr) - encode(e[c], tr);
                    float dy, du, dv;
                    rgbToYuvDelta(d, coding, dy, du, dv);
                    if (std::fabs(dy) >= 0.5f) changed.fetch_add(1, std::memory_order_relaxed);
                    img.addY(x, y, dy, dither(x, y, 0));
                    dU += du * 0.25f;
                    dV += dv * 0.25f;
                }
            img.addU(bx, by, dU, dither(bx, by, 1));
            img.addV(bx, by, dV, dither(bx, by, 2));
        }
    });
    __android_log_print(ANDROID_LOG_INFO, TAG, "capture: %s, %d levels, %.1f%% of pixels changed", img.name(),
                        built, 100.0 * double(changed.load()) / (double(img.w) * img.h));
}


void scatterP010(AHardwareBuffer *ahb, const SoftFilter &filter, Transfer tr, YuvCoding coding) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    AHardwareBuffer_Planes planes{};
    // API 29; the module's minimum is 28.
    using LockPlanes = int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *);
    static auto lockPlanes = reinterpret_cast<LockPlanes>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_lockPlanes"));
    if (!lockPlanes ||
        lockPlanes(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &planes) != 0 ||
        planes.planeCount < 3) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "capture: P010 buffer not lockable (format 0x%x)", desc.format);
        return;
    }
    P010Image img;
    img.w = int(desc.width) & ~1;
    img.h = int(desc.height) & ~1;
    img.yBase = static_cast<uint8_t *>(planes.planes[0].data);
    img.yStride = planes.planes[0].rowStride;
    img.uBase = static_cast<uint8_t *>(planes.planes[1].data);
    img.uStride = planes.planes[1].rowStride;
    img.uPixel = planes.planes[1].pixelStride;
    img.vBase = static_cast<uint8_t *>(planes.planes[2].data);
    img.vStride = planes.planes[2].rowStride;
    img.vPixel = planes.planes[2].pixelStride;
    img.c = coding;
    img.detectJustification();
    bool interleaved = img.uPixel == 4 && img.vPixel == 4 && img.vBase == img.uBase + 2 && img.uStride == img.vStride;
    if (!interleaved || !scatterYuvGpu({true, img.yBase, img.uBase, img.yStride, img.uStride, img.w, img.h, img.c}, filter, tr)) {
        scatterImage(img, filter, tr, img.c);
    }
    AHardwareBuffer_unlock(ahb, nullptr);
}

// ---- preview: the same passes in Meishe's GL context -----------------------------------------

const char *kVertex = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char *kCommon = R"(#version 300 es
precision highp float;
precision highp int;
out vec4 fragColor;
// HLG: the master tone curve, row 0 signal -> scene light, row 1 log2 scene light -> signal.
uniform int uHlg;
uniform highp sampler2D uCurve;
uniform vec3 uCurveLimits;   // log2 scene min, log2 scene max, first forward value
float curveAt(int row, float pos) {
    float n = float(textureSize(uCurve, 0).x - 1);
    pos = clamp(pos, 0.0, n);
    int i = min(int(pos), int(n) - 1);
    float f = pos - float(i);
    return mix(texelFetch(uCurve, ivec2(i, row), 0).r, texelFetch(uCurve, ivec2(i + 1, row), 0).r, f);
}
vec3 decode(vec3 v) {
    v = clamp(v, 0.0, 1.0);
    if (uHlg == 1) {
        float n = float(textureSize(uCurve, 0).x - 1);
        return vec3(curveAt(0, v.r * n), curveAt(0, v.g * n), curveAt(0, v.b * n));
    }
    return mix(v / 12.92, pow((v + 0.055) / 1.055, vec3(2.4)), step(0.04045, v));
}
float encodeHlg(float e) {
    float lo = exp2(uCurveLimits.x);
    if (e <= lo) return max(e, 0.0) / lo * uCurveLimits.z;
    float n = float(textureSize(uCurve, 0).x - 1);
    return curveAt(1, (log2(e) - uCurveLimits.x) / (uCurveLimits.y - uCurveLimits.x) * n);
}
vec3 encode(vec3 e) {
    if (uHlg == 1) return vec3(encodeHlg(e.r), encodeHlg(e.g), encodeHlg(e.b));
    e = clamp(e, 0.0, 1.0);
    return mix(12.92 * e, 1.055 * pow(e, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, e));
}
// 2x tent upsample: fine texel centre (x + 0.5) sits at coarse coordinate (x + 0.5) / 2.
vec3 tentUp(sampler2D src, vec2 coarseTexel, vec2 fineCentre) {
    vec2 c = fineCentre * 0.5;
    vec3 acc = vec3(0.0);
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            float w = (x == 0 ? 0.5 : 0.25) * (y == 0 ? 0.5 : 0.25);
            acc += w * texture(src, (c + vec2(float(x), float(y))) * coarseTexel).rgb;
        }
    return acc;
}
)";

const char *kLinearize = R"(
uniform sampler2D uSrc;
void main() {
    fragColor = vec4(decode(texelFetch(uSrc, ivec2(gl_FragCoord.xy), 0).rgb), 1.0);
}
)";

const char *kDown = R"(
uniform sampler2D uSrc;
uniform vec2 uSrcTexel;
uniform vec3 uBoost;   // log2 clip, ramp stops, gain at the clip (first level only; gain 1 = off)
vec3 tap(vec2 c) {
    vec3 v = texture(uSrc, c * uSrcTexel).rgb;
    float peak = max(v.r, max(v.g, v.b));
    float t = clamp((log2(max(peak, 1e-6)) - (uBoost.x - uBoost.y)) / uBoost.y, 0.0, 1.0);
    return v * (1.0 + (uBoost.z - 1.0) * t);
}
void main() {
    // Output texel x is centred at source coordinate 2x + 1 (texel edges at integers).
    vec2 c = gl_FragCoord.xy * 2.0;
    vec3 a = tap(c) * 0.125;
    a += (tap(c + vec2(-1.0, -1.0)) + tap(c + vec2(1.0, -1.0)) + tap(c + vec2(-1.0, 1.0)) + tap(c + vec2(1.0, 1.0))) * 0.125;
    a += (tap(c + vec2(-2.0, -2.0)) + tap(c + vec2(2.0, -2.0)) + tap(c + vec2(-2.0, 2.0)) + tap(c + vec2(2.0, 2.0))) * 0.03125;
    a += (tap(c + vec2(-2.0, 0.0)) + tap(c + vec2(2.0, 0.0)) + tap(c + vec2(0.0, -2.0)) + tap(c + vec2(0.0, 2.0))) * 0.0625;
    fragColor = vec4(a, 1.0);
}
)";

const char *kUp = R"(
uniform sampler2D uLevel;
uniform sampler2D uCoarser;
uniform vec2 uCoarserTexel;
uniform vec3 uWeight;
uniform int uHasCoarser;
void main() {
    vec3 u = uWeight * texelFetch(uLevel, ivec2(gl_FragCoord.xy), 0).rgb;
    if (uHasCoarser == 1) u += tentUp(uCoarser, uCoarserTexel, gl_FragCoord.xy);
    fragColor = vec4(u, 1.0);
}
)";

// The scattered light of pixel p (scene light, after the shoulder); e is the pixel's own light.
const char *kScatterCore = R"(
uniform sampler2D uLinear;
uniform sampler2D uUp1;
uniform vec2 uUp1Texel;
uniform vec3 uCore;
uniform vec3 uTransmission;
uniform float uShoulder;   // knee
uniform float uTop;
uniform sampler2D uGate;   // pyramid level averaging the source neighbourhood
uniform vec2 uFullTexel;
uniform vec3 uSource;      // log2 clip, gate stops, core gain at the clip (1 = off)
vec3 scatterAt(ivec2 p, out vec3 e) {
    vec2 centre = vec2(p) + 0.5;
    float k = 1.0;
    if (uSource.z > 1.0) {
        vec3 m = texture(uGate, centre * uFullTexel).rgb;
        float t = clamp((log2(max(max(m.r, max(m.g, m.b)), 1e-6)) - (uSource.x - uSource.y)) / uSource.y, 0.0, 1.0);
        k = 1.0 + (uSource.z - 1.0) * t;
    }
    e = texelFetch(uLinear, p, 0).rgb;
    vec3 acc = (uCore * k * e + tentUp(uUp1, uUp1Texel, centre)) / uTransmission;
    float peak = max(acc.r, max(acc.g, acc.b));
    float comp = peak <= uShoulder ? peak : uShoulder + (uTop - uShoulder) * tanh((peak - uShoulder) / (uTop - uShoulder));
    return acc * (comp / max(peak, 1e-6));
}
)";

const char *kComposite = R"(
void main() {
    vec3 e;
    vec3 acc = scatterAt(ivec2(gl_FragCoord.xy), e);
    // The result is stored as 8 bits: triangular dither keeps the smooth halo from stepping into bands.
    vec2 q = gl_FragCoord.xy;
    vec3 n = vec3(fract(sin(dot(q, vec2(12.9898, 78.233))) * 43758.5453), fract(sin(dot(q, vec2(39.3468, 11.135))) * 24634.6345),
                  fract(sin(dot(q, vec2(73.1561, 52.235))) * 31415.9265));
    vec3 m = vec3(fract(sin(dot(q + 7.0, vec2(12.9898, 78.233))) * 43758.5453), fract(sin(dot(q + 7.0, vec2(39.3468, 11.135))) * 24634.6345),
                  fract(sin(dot(q + 7.0, vec2(73.1561, 52.235))) * 31415.9265));
    fragColor = vec4(encode(acc) + (n + m - 1.0) / 255.0, 1.0);
}
)";

// ---- capture frames: YUV planes in, YUV planes out ---------------------------------------------
//
// The planes are uploaded as integer textures (P010 words or NV21 bytes) and read back as RGBA8
// textures whose bytes are the plane's own bytes, so the host memory is filled by one glReadPixels.

const char *kYuvIn = R"(
uniform highp usampler2D uY;
uniform highp usampler2D uUV;
uniform int uP010;
uniform int uMsb;       // P010 codes at the top of the word
uniform int uUvSwap;    // NV21 stores V first
uniform float uScale;   // NV21: 10-bit units per code
uniform vec3 uCoding;   // kr, kb, full range
float codeOf(uint raw) {
    if (uP010 == 1) return float(uMsb == 1 ? (raw >> 6) : (raw & 0x3ffu));
    return float(raw) * uScale;
}
uvec2 rawUV(ivec2 b) {
    uvec2 r = texelFetch(uUV, b, 0).rg;
    return uUvSwap == 1 ? r.yx : r;
}
float yAt(ivec2 p) { return codeOf(texelFetch(uY, p, 0).r); }
vec2 uvAt(ivec2 b) {
    uvec2 r = rawUV(b);
    return vec2(codeOf(r.x), codeOf(r.y));
}
vec3 yuvToRgb(float Y, float U, float V) {
    bool full = uCoding.z > 0.5;
    float y = full ? Y / 1023.0 : (Y - 64.0) / 876.0;
    float cb = (U - 512.0) / (full ? 1023.0 : 896.0);
    float cr = (V - 512.0) / (full ? 1023.0 : 896.0);
    float kr = uCoding.x, kb = uCoding.y, kg = 1.0 - kr - kb;
    float r = y + 2.0 * (1.0 - kr) * cr;
    float b = y + 2.0 * (1.0 - kb) * cb;
    return vec3(r, (y - kr * r - kb * b) / kg, b);
}
)";

const char *kLinearizeYuv = R"(
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 c = uvAt(p >> 1);
    fragColor = vec4(decode(yuvToRgb(yAt(p), c.x, c.y)), 1.0);
}
)";

// Change of one pixel's YUV codes (10-bit units), as scatterImage writes it back.
const char *kYuvDelta = R"(
float hash32(uint v) {
    v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15; v *= 0x846ca68bu; v ^= v >> 16;
    return float(v & 0xffffffu) / 16777216.0;
}
// Triangular dither of +-1 code from the pixel position.
float dither(ivec2 p, int salt) {
    uint k = uint(p.x) * 73856093u ^ uint(p.y) * 19349663u ^ uint(salt) * 83492791u;
    return hash32(k) + hash32(k + 0x9e3779b9u) - 1.0;
}
vec3 deltaAt(ivec2 p) {
    vec3 e;
    vec3 acc = scatterAt(p, e);
    vec3 d = encode(acc) - encode(e);
    float kr = uCoding.x, kb = uCoding.y, kg = 1.0 - kr - kb;
    float y = kr * d.r + kg * d.g + kb * d.b;
    float cb = (d.b - y) / (2.0 * (1.0 - kb));
    float cr = (d.r - y) / (2.0 * (1.0 - kr));
    bool full = uCoding.z > 0.5;
    return vec3(y * (full ? 1023.0 : 876.0), cb * (full ? 1023.0 : 896.0), cr * (full ? 1023.0 : 896.0));
}
float quant(float v) { return floor(clamp(v, 0.0, 1023.0) + 0.5); }
// Low and high byte of a 10-bit code stored as a 16-bit word.
vec2 word(float q) {
    uint raw = uMsb == 1 ? (uint(q) << 6) : uint(q);
    return vec2(float(raw & 255u), float(raw >> 8));
}
float byteOf(float raw, float units, float dith) { return clamp(floor(raw + units / uScale + dith + 0.5), 0.0, 255.0); }
)";

// One output texel is 4 bytes of the Y plane: 2 P010 pixels or 4 NV21 pixels.
const char *kOutLuma = R"(
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    float b[4];
    if (uP010 == 1) {
        for (int i = 0; i < 2; i++) {
            ivec2 p = ivec2(2 * t.x + i, t.y);
            vec2 w = word(quant(yAt(p) + deltaAt(p).x + dither(p, 0)));
            b[2 * i] = w.x;
            b[2 * i + 1] = w.y;
        }
    } else {
        for (int i = 0; i < 4; i++) {
            ivec2 p = ivec2(4 * t.x + i, t.y);
            b[i] = byteOf(float(texelFetch(uY, p, 0).r), deltaAt(p).x, dither(p, 0));
        }
    }
    fragColor = vec4(b[0], b[1], b[2], b[3]) / 255.0;
}
)";

// One output texel is 4 bytes of the chroma plane: one P010 U, V pair or two NV21 V, U pairs.
const char *kOutChroma = R"(
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    float b[4];
    int n = uP010 == 1 ? 1 : 2;
    for (int i = 0; i < n; i++) {
        ivec2 blk = ivec2(n * t.x + i, t.y);
        vec2 s = vec2(0.0);
        for (int j = 0; j < 2; j++)
            for (int k = 0; k < 2; k++) s += 0.25 * deltaAt(2 * blk + ivec2(k, j)).yz;
        uvec2 raw = rawUV(blk);   // U, V
        float du = dither(blk, 1), dv = dither(blk, 2);
        if (uP010 == 1) {
            vec2 u = word(quant(codeOf(raw.x) + s.x + du));
            vec2 v = word(quant(codeOf(raw.y) + s.y + dv));
            b[0] = u.x; b[1] = u.y; b[2] = v.x; b[3] = v.y;
        } else {
            b[2 * i] = byteOf(float(raw.y), s.y, dv);
            b[2 * i + 1] = byteOf(float(raw.x), s.x, du);
        }
    }
    fragColor = vec4(b[0], b[1], b[2], b[3]) / 255.0;
}
)";

GLuint compile(GLenum type, const char *const *parts, int count) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, count, parts, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "shader: %s", log);
    }
    return s;
}

GLuint program(const char *fragment, const char *prelude = "") {
    GLuint vs = compile(GL_VERTEX_SHADER, &kVertex, 1);
    const char *parts[] = {kCommon, prelude, fragment};
    GLuint fs = compile(GL_FRAGMENT_SHADER, parts, 3);
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "program: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

struct Target {
    GLuint tex = 0;
    int w = 0, h = 0;
};

struct GlPipeline {
    GLuint linearize = 0, down = 0, up = 0, composite = 0, vao = 0, fbo = 0, curve = 0;
    GLuint linearizeYuv = 0, outLuma = 0, outChroma = 0;  // capture frames: planes in, planes out
    int w = 0, h = 0;
    Target l0;
    std::vector<Target> levels, ups;  // levels[i] = L_i+1, ups[i] = U_i+1

    bool ready() const { return linearize && down && up && composite; }
    bool yuvReady() const { return ready() && linearizeYuv && outLuma && outChroma; }
};

Target makeTarget(int w, int h) {
    Target t{0, w, h};
    glGenTextures(1, &t.tex);
    glBindTexture(GL_TEXTURE_2D, t.tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA16F, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

void releaseTargets(GlPipeline &p) {
    auto drop = [](Target &t) { if (t.tex) glDeleteTextures(1, &t.tex); t = Target{}; };
    drop(p.l0);
    for (auto &t : p.levels) drop(t);
    for (auto &t : p.ups) drop(t);
    p.levels.clear();
    p.ups.clear();
}

void sizeTargets(GlPipeline &p, int w, int h) {
    if (p.w == w && p.h == h) return;
    releaseTargets(p);
    p.w = w;
    p.h = h;
    p.l0 = makeTarget(w, h);
    int lw = w, lh = h;
    while (int(p.levels.size()) < kSoftLevels - 1 && std::min(lw, lh) >= 4) {
        lw /= 2;
        lh /= 2;
        p.levels.push_back(makeTarget(lw, lh));
        p.ups.push_back(makeTarget(lw, lh));
    }
}

std::map<EGLContext, GlPipeline> gPipelines;

// GL state Meishe may rely on after the call.
struct SavedGl {
    GLint fbo, program, vao, active, tex0, tex1, tex2, tex3, sampler0, sampler1, sampler2, sampler3, viewport[4];
    GLboolean blend, depth, scissor, stencil, cull;
    void save() {
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        glActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex0);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler0);
        glActiveTexture(GL_TEXTURE1);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex1);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler1);
        glActiveTexture(GL_TEXTURE2);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex2);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler2);
        glActiveTexture(GL_TEXTURE3);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex3);
        glGetIntegerv(GL_SAMPLER_BINDING, &sampler3);
        glGetIntegerv(GL_VIEWPORT, viewport);
        blend = glIsEnabled(GL_BLEND);
        depth = glIsEnabled(GL_DEPTH_TEST);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
        stencil = glIsEnabled(GL_STENCIL_TEST);
        cull = glIsEnabled(GL_CULL_FACE);
    }
    void restore() const {
        auto set = [](GLenum cap, GLboolean on) { on ? glEnable(cap) : glDisable(cap); };
        set(GL_BLEND, blend);
        set(GL_DEPTH_TEST, depth);
        set(GL_SCISSOR_TEST, scissor);
        set(GL_STENCIL_TEST, stencil);
        set(GL_CULL_FACE, cull);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, tex3);
        glBindSampler(3, sampler3);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, tex2);
        glBindSampler(2, sampler2);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, tex1);
        glBindSampler(1, sampler1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, tex0);
        glBindSampler(0, sampler0);
        glActiveTexture(active);
        glBindVertexArray(vao);
        glUseProgram(program);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    }
};

void drawInto(GlPipeline &p, GLuint tex, int w, int h) {
    glBindFramebuffer(GL_FRAMEBUFFER, p.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glViewport(0, 0, w, h);
    glDrawArrays(GL_TRIANGLES, 0, 3);
}

void bindTex(int unit, GLuint tex) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
}

// Programs, curve table and vertex state of the current GL context; false when a shader failed.
bool initPipeline(GlPipeline &p) {
    if (!p.vao) {
        p.linearize = program(kLinearize);
        p.down = program(kDown);
        p.up = program(kUp);
        p.composite = program(kComposite, kScatterCore);
        p.linearizeYuv = program(kLinearizeYuv, kYuvIn);
        std::string delta = std::string(kYuvIn) + kScatterCore + kYuvDelta;
        p.outLuma = program(kOutLuma, delta.c_str());
        p.outChroma = program(kOutChroma, delta.c_str());
        glGenVertexArrays(1, &p.vao);
        glGenFramebuffers(1, &p.fbo);
        std::vector<float> rows(kSoftHlgToScene, kSoftHlgToScene + kSoftCurveN);
        rows.insert(rows.end(), kSoftSceneToHlg, kSoftSceneToHlg + kSoftCurveN);
        glGenTextures(1, &p.curve);
        glBindTexture(GL_TEXTURE_2D, p.curve);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, kSoftCurveN, 2);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kSoftCurveN, 2, GL_RED, GL_FLOAT, rows.data());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    return p.ready();
}

void resetState(const GlPipeline &p) {
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    for (GLuint unit = 0; unit < 6; unit++) glBindSampler(unit, 0);
    glBindVertexArray(p.vao);
}

void setCurveUniforms(GLuint prog, Transfer tr) {
    glUniform1i(glGetUniformLocation(prog, "uHlg"), tr == kHlg ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uCurve"), 2);
    glUniform3f(glGetUniformLocation(prog, "uCurveLimits"), kSoftLog2SceneMin, kSoftLog2SceneMax, kSoftSceneToHlg[0]);
}

// Level 0 (scene light) is in p.l0: builds the blur pyramid and the up chain.
void buildPyramid(GlPipeline &p, int w, int h, const SoftFilter &filter, Transfer tr) {
    glUseProgram(p.down);
    glUniform1i(glGetUniformLocation(p.down, "uSrc"), 0);
    const Target *src = &p.l0;
    for (auto &level : p.levels) {
        glUniform3f(glGetUniformLocation(p.down, "uBoost"), std::log2(topOf(tr)), boostStops(tr), src == &p.l0 ? sourceGain(filter) : 1.0f);
        glUniform2f(glGetUniformLocation(p.down, "uSrcTexel"), 1.0f / src->w, 1.0f / src->h);
        bindTex(0, src->tex);
        drawInto(p, level.tex, level.w, level.h);
        src = &level;
    }

    int built = int(p.levels.size());
    LevelWeights lw = levelWeights(filter, std::hypot(float(w), float(h)), built);
    glUseProgram(p.up);
    glUniform1i(glGetUniformLocation(p.up, "uLevel"), 0);
    glUniform1i(glGetUniformLocation(p.up, "uCoarser"), 1);
    for (int i = built; i >= 1; i--) {
        glUniform3f(glGetUniformLocation(p.up, "uWeight"), lw.w[0][i], lw.w[1][i], lw.w[2][i]);
        glUniform1i(glGetUniformLocation(p.up, "uHasCoarser"), i < built ? 1 : 0);
        bindTex(0, p.levels[i - 1].tex);
        if (i < built) {
            const Target &coarser = p.ups[i];
            glUniform2f(glGetUniformLocation(p.up, "uCoarserTexel"), 1.0f / coarser.w, 1.0f / coarser.h);
            bindTex(1, coarser.tex);
        }
        drawInto(p, p.ups[i - 1].tex, p.ups[i - 1].w, p.ups[i - 1].h);
    }
}

// Uniforms and textures of kScatterCore for the program in use: l0 on unit 0, up chain on 1, curve on 2,
// source gate on 3.
void bindScatter(GlPipeline &p, GLuint prog, int w, int h, const SoftFilter &filter, Transfer tr) {
    int built = int(p.levels.size());
    LevelWeights lw = levelWeights(filter, std::hypot(float(w), float(h)), built);
    int gateLevel = sourceLevel(filter, std::hypot(float(w), float(h)), built);
    setCurveUniforms(prog, tr);
    glUniform1i(glGetUniformLocation(prog, "uLinear"), 0);
    glUniform1i(glGetUniformLocation(prog, "uUp1"), 1);
    glUniform2f(glGetUniformLocation(prog, "uUp1Texel"), 1.0f / p.ups[0].w, 1.0f / p.ups[0].h);
    glUniform3f(glGetUniformLocation(prog, "uCore"), lw.core[0] + lw.w[0][0], lw.core[1] + lw.w[1][0], lw.core[2] + lw.w[2][0]);
    glUniform3f(glGetUniformLocation(prog, "uTransmission"), lw.transmission[0], lw.transmission[1], lw.transmission[2]);
    glUniform1f(glGetUniformLocation(prog, "uShoulder"), kShoulder * topOf(tr));
    glUniform1f(glGetUniformLocation(prog, "uTop"), topOf(tr));
    glUniform1i(glGetUniformLocation(prog, "uGate"), 3);
    glUniform2f(glGetUniformLocation(prog, "uFullTexel"), 1.0f / w, 1.0f / h);
    glUniform3f(glGetUniformLocation(prog, "uSource"), std::log2(topOf(tr)), kSourceStops, sourceGain(filter));
    bindTex(3, p.levels[gateLevel - 1].tex);
    bindTex(0, p.l0.tex);
    bindTex(1, p.ups[0].tex);
    bindTex(2, p.curve);
}

// Scatters srcTex into dstTex (RGBA8, same size): the filter's input frame goes in, the scattered frame
// is what the filter then renders.
void scatterTexture(GLuint srcTex, GLuint dstTex, int w, int h, const SoftFilter &filter, Transfer tr) {
    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT) return;
    SavedGl saved;
    saved.save();
    GlPipeline &p = gPipelines[ctx];
    if (!initPipeline(p)) {
        saved.restore();
        return;
    }
    sizeTargets(p, w, h);
    resetState(p);

    glUseProgram(p.linearize);
    glUniform1i(glGetUniformLocation(p.linearize, "uSrc"), 0);
    setCurveUniforms(p.linearize, tr);
    bindTex(2, p.curve);
    bindTex(0, srcTex);
    drawInto(p, p.l0.tex, w, h);

    buildPyramid(p, w, h, filter, tr);

    glUseProgram(p.composite);
    bindScatter(p, p.composite, w, h, filter, tr);
    drawInto(p, dstTex, w, h);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);

    saved.restore();
}

// RGBA8 scratch frame for the scattered input, one per GL context, resized on demand.
GLuint scatterScratch(int w, int h) {
    struct Scratch { GLuint tex = 0; int w = 0, h = 0; };
    static std::map<EGLContext, Scratch> scratch;
    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT) return 0;
    Scratch &s = scratch[ctx];
    if (s.tex && s.w == w && s.h == h) return s.tex;
    GLint bound;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound);
    if (s.tex) glDeleteTextures(1, &s.tex);
    glGenTextures(1, &s.tex);
    glBindTexture(GL_TEXTURE_2D, s.tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, bound);
    s.w = w;
    s.h = h;
    return s.tex;
}

// ---- capture: the same passes on the GPU, in a context of our own -----------------------------
//
// The capture hooks hand over host memory, and the thread they run on has no GL context to rely
// on, so the passes run in a private pbuffer context; the thread's own context is restored after.

struct OwnContext {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    bool tried = false;

    bool create() {
        tried = true;
        eglBindAPI(EGL_OPENGL_ES_API);
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY || !eglInitialize(display, nullptr, nullptr)) return false;
        const EGLint configAttribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
                                        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        EGLConfig config;
        EGLint n = 0;
        if (!eglChooseConfig(display, configAttribs, &config, 1, &n) || n < 1) return false;
        const EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
        const EGLint surfaceAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface = eglCreatePbufferSurface(display, config, surfaceAttribs);
        return context != EGL_NO_CONTEXT && surface != EGL_NO_SURFACE;
    }
};

// Makes the private context current for a scope and gives the thread its own context back.
struct ContextScope {
    EGLDisplay display;
    EGLSurface draw, read;
    EGLContext context;
    bool ok;
    explicit ContextScope(const OwnContext &own)
        : display(eglGetCurrentDisplay()), draw(eglGetCurrentSurface(EGL_DRAW)), read(eglGetCurrentSurface(EGL_READ)),
          context(eglGetCurrentContext()) {
        ok = eglMakeCurrent(own.display, own.surface, own.surface, own.context);
    }
    ~ContextScope() {
        if (!ok) return;
        if (context == EGL_NO_CONTEXT) {
            eglMakeCurrent(eglGetCurrentDisplay(), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        } else {
            eglMakeCurrent(display, draw, read, context);
        }
    }
};

GLuint planeTexture(GLenum internal, int w, int h, GLenum format, GLenum type, GLint rowLength, const void *data) {
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, internal, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    if (data) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, data);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }
    return tex;
}

void setYuvUniforms(GLuint prog, const YuvPlanes &f, float scale) {
    glUniform1i(glGetUniformLocation(prog, "uY"), 4);
    glUniform1i(glGetUniformLocation(prog, "uUV"), 5);
    glUniform1i(glGetUniformLocation(prog, "uP010"), f.p010 ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uMsb"), f.coding.msb ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uUvSwap"), f.p010 ? 0 : 1);
    glUniform1f(glGetUniformLocation(prog, "uScale"), scale);
    glUniform3f(glGetUniformLocation(prog, "uCoding"), f.coding.kr, f.coding.kb, f.coding.full ? 1.0f : 0.0f);
}

bool runYuv(const YuvPlanes &f, const SoftFilter &filter, Transfer tr) {
    GlPipeline &p = gPipelines[eglGetCurrentContext()];
    if (!initPipeline(p) || !p.yuvReady()) return false;
    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    if (std::max(f.w, f.h) > maxSize) return false;
    while (glGetError() != GL_NO_ERROR) {}

    int w = f.w, h = f.h;
    float scale = f.coding.full ? 1023.0f / 255.0f : 4.0f;
    sizeTargets(p, w, h);
    resetState(p);
    int lumaPer = f.p010 ? 2 : 4, chromaPer = f.p010 ? 1 : 2;  // pixels (chroma: blocks) per output texel
    GLuint texY = f.p010 ? planeTexture(GL_R16UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_SHORT, GLint(f.yStride / 2), f.y)
                         : planeTexture(GL_R8UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_BYTE, GLint(f.yStride), f.y);
    GLuint texUV = f.p010 ? planeTexture(GL_RG16UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_SHORT, GLint(f.uvStride / 4), f.uv)
                          : planeTexture(GL_RG8UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_BYTE, GLint(f.uvStride / 2), f.uv);
    GLuint outY = planeTexture(GL_RGBA8, w / lumaPer, h, 0, 0, 0, nullptr);
    GLuint outUV = planeTexture(GL_RGBA8, w / 2 / chromaPer, h / 2, 0, 0, 0, nullptr);
    bindTex(4, texY);
    bindTex(5, texUV);

    glUseProgram(p.linearizeYuv);
    setYuvUniforms(p.linearizeYuv, f, scale);
    setCurveUniforms(p.linearizeYuv, tr);
    bindTex(2, p.curve);
    drawInto(p, p.l0.tex, w, h);

    buildPyramid(p, w, h, filter, tr);

    bool ok = true;
    auto pass = [&](GLuint prog, GLuint target, int tw, int th, void *dst, size_t stride) {
        glUseProgram(prog);
        bindScatter(p, prog, w, h, filter, tr);
        setYuvUniforms(prog, f, scale);
        drawInto(p, target, tw, th);
        if (glGetError() != GL_NO_ERROR || glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            ok = false;
            return;
        }
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glPixelStorei(GL_PACK_ROW_LENGTH, GLint(stride / 4));
        glReadPixels(0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, dst);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    };
    pass(p.outLuma, outY, w / lumaPer, h, f.y, f.yStride);
    if (ok) pass(p.outChroma, outUV, w / 2 / chromaPer, h / 2, f.uv, f.uvStride);
    ok = ok && glGetError() == GL_NO_ERROR;

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    GLuint textures[] = {texY, texUV, outY, outUV};
    glDeleteTextures(4, textures);
    releaseTargets(p);
    p.w = p.h = 0;
    return ok;
}

template <typename Fn>
bool withPrivateGlContext(Fn &&fn) {
    static std::mutex lock;
    static OwnContext own;
    std::lock_guard<std::mutex> guard(lock);
    if (!own.tried && !own.create()) __android_log_print(ANDROID_LOG_ERROR, TAG, "gpu capture: no GL context (egl error 0x%x)", eglGetError());
    if (own.context == EGL_NO_CONTEXT || own.surface == EGL_NO_SURFACE) return false;
    ContextScope scope(own);
    if (!scope.ok) return false;
    return fn();
}

bool scatterYuvGpu(const YuvPlanes &f, const SoftFilter &filter, Transfer tr) {
    char cpuOnly[PROP_VALUE_MAX] = {};
    if (__system_property_get("debug.muse.softlight_cpu", cpuOnly) > 0 && cpuOnly[0] == '1') return false;
    // 4-byte texels must tile the plane rows.
    if (f.w % (f.p010 ? 2 : 4) != 0 || f.h % 2 != 0 || f.yStride % 4 != 0 || f.uvStride % 4 != 0) return false;
    return withPrivateGlContext([&] {
        auto start = std::chrono::steady_clock::now();
        bool ok = runYuv(f, filter, tr);
        __android_log_print(ANDROID_LOG_INFO, TAG, "gpu capture %s %dx%d: %s in %lld ms", f.p010 ? "P010" : "NV21", f.w, f.h, ok ? "done" : "failed",
                            (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
        return ok;
    });
}

void grainHardwareBuffer(AHardwareBuffer *ahb, Transfer tr, YuvCoding coding) {
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(ahb, &desc);
    using LockPlanes = int (*)(AHardwareBuffer *, uint64_t, int32_t, const ARect *, AHardwareBuffer_Planes *);
    static auto lockPlanes = reinterpret_cast<LockPlanes>(dlsym(RTLD_DEFAULT, "AHardwareBuffer_lockPlanes"));
    AHardwareBuffer_Planes planes{};
    if (!lockPlanes ||
        lockPlanes(ahb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &planes) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "hncs grain: AHB not lockable (format 0x%x)", desc.format);
        return;
    }
    if (planes.planeCount < 3) {
        AHardwareBuffer_unlock(ahb, nullptr);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "hncs grain: AHB planeCount=%u (format 0x%x)", planes.planeCount, desc.format);
        return;
    }
    int w = int(desc.width) & ~1, h = int(desc.height) & ~1;
    auto *yBase = static_cast<uint8_t *>(planes.planes[0].data);
    uint32_t yStride = planes.planes[0].rowStride;
    auto *uBase = static_cast<uint8_t *>(planes.planes[1].data);
    uint32_t uStride = planes.planes[1].rowStride, uPixel = planes.planes[1].pixelStride;
    auto *vBase = static_cast<uint8_t *>(planes.planes[2].data);
    uint32_t vStride = planes.planes[2].rowStride, vPixel = planes.planes[2].pixelStride;

    hncs_grain::HncsGrainFrame frame{};
    frame.y = yBase;
    frame.yStride = yStride;
    frame.w = w;
    frame.h = h;
    frame.kr = coding.kr;
    frame.kb = coding.kb;
    frame.fullRange = coding.full;
    frame.hlg = (tr == kHlg);

    bool supported = false;
    if (uPixel == 4 && vPixel == 4 && uStride == vStride && (vBase == uBase + 2 || uBase == vBase + 2)) {
        P010Image img;
        img.w = w; img.h = h;
        img.yBase = yBase; img.yStride = yStride;
        img.uBase = uBase; img.uStride = uStride; img.uPixel = uPixel;
        img.vBase = vBase; img.vStride = vStride; img.vPixel = vPixel;
        img.c = coding;
        img.detectJustification();
        frame.p010 = true;
        frame.msb = img.c.msb;
        frame.vFirst = (vBase < uBase);
        frame.uv = frame.vFirst ? vBase : uBase;
        frame.uvStride = uStride;
        supported = true;
    } else if (uPixel == 2 && vPixel == 2 && uStride == vStride && (uBase == vBase + 1 || vBase == uBase + 1)) {
        frame.p010 = false;
        frame.msb = false;
        frame.vFirst = (vBase < uBase);
        frame.uv = frame.vFirst ? vBase : uBase;
        frame.uvStride = uStride;
        supported = true;
    }
    if (supported) {
        if (x100vi_grain::isX100viGrainActive()) {
            x100vi_grain::X100viGrainFrame xf{
                frame.p010, frame.y, frame.uv, frame.yStride, frame.uvStride,
                frame.w, frame.h, frame.kr, frame.kb, frame.fullRange, frame.msb, frame.hlg, frame.vFirst
            };
            withPrivateGlContext([&] { return x100vi_grain::applyX100viGrainGpu(xf); });
        } else if (hncs_grain::isHncsGrainActive()) {
            withPrivateGlContext([&] { return hncs_grain::applyHncsGrainGpu(frame); });
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, TAG, "hncs grain: unsupported AHB layout uPx=%u vPx=%u fmt=0x%x",
                            uPixel, vPixel, desc.format);
    }
    AHardwareBuffer_unlock(ahb, nullptr);
}

// ---- hooks -----------------------------------------------------------------------------------

// SMsGPUTextureInfo: texture id, width, height, -, format, primaries, transfer (2 = HLG), -.
struct GpuTextureInfo {
    uint32_t texture, width, height, reserved, format, primaries, transfer, extra;
};
// SMsHardwareBufferInfo: buffer, primaries, transfer (2 = HLG), YUV matrix, YUV range.
struct HardwareBufferInfo {
    AHardwareBuffer *buffer;
    int32_t primaries, transfer, matrix, range;
};

bool transferOf(uint32_t transfer, Transfer &out) {
    if (transfer == 2) { out = kHlg; return true; }
    if (transfer == 0) { out = kSrgb; return true; }
    return false;  // PQ: left to the stock soft light
}

using RenderHdrTexture = int (*)(void *, void *, const GpuTextureInfo *, unsigned, GpuTextureInfo *, const void *, const void *, int);
RenderHdrTexture origHdrTexture;
int hookHdrTexture(void *core, void *effect, const GpuTextureInfo *in, unsigned n, GpuTextureInfo *out, const void *param,
                   const void *metadata, int flags) {
    const SoftFilter *filter = filterFor(param);
    Transfer tr;
    if (!filter || !in || n < 1 || !out || !transferOf(out->transfer, tr)) return origHdrTexture(core, effect, in, n, out, param, metadata, flags);
    // The soft light acts on the light reaching the filter: scatter the input frame, then let the
    // filter (look, palette, film) render it.
    GLuint scattered = scatterScratch(int(in[0].width), int(in[0].height));
    if (!scattered) return origHdrTexture(core, effect, in, n, out, param, metadata, flags);
    scatterTexture(in[0].texture, scattered, int(in[0].width), int(in[0].height), *filter, tr);
    std::vector<GpuTextureInfo> inputs(in, in + n);
    inputs[0].texture = scattered;
    StockSoftOff off(param);
    return origHdrTexture(core, effect, inputs.data(), n, out, param, metadata, flags);
}

using RenderHardwareBuffer = int (*)(void *, void *, const HardwareBufferInfo *, unsigned, const HardwareBufferInfo *, const void *,
                                     const void *, int);
RenderHardwareBuffer origHardwareBuffer;
int renderHardwareBuffer(void *core, void *effect, const HardwareBufferInfo *in, unsigned n, const HardwareBufferInfo *out,
                         const void *param, const void *metadata, int flags) {
    const SoftFilter *filter = filterFor(param);
    Transfer tr;
    gCaptureSoft.store(filter != nullptr);
    if (!filter || !in || n < 1 || !in[0].buffer || !out || !out->buffer || !transferOf(uint32_t(in[0].transfer), tr)) {
        return origHardwareBuffer(core, effect, in, n, out, param, metadata, flags);
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(in[0].buffer, &desc);
    __android_log_print(ANDROID_LOG_INFO, TAG, "capture %ux%u %s, focal %.0f mm", desc.width, desc.height,
                        filter == &kHdf ? "HDF" : filter == &kBlackMist ? "black mist" : "white mist", double(gFocalMm.load()));
    auto start = std::chrono::steady_clock::now();
    AHardwareBuffer *scattered = copyP010(in[0].buffer);
    if (scattered) scatterP010(scattered, *filter, tr, yuvCoding(in[0].matrix, in[0].range));
    __android_log_print(ANDROID_LOG_INFO, TAG, "capture took %lld ms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start).count());
    std::vector<HardwareBufferInfo> inputs(in, in + n);
    if (scattered) inputs[0].buffer = scattered;
    int ret;
    {
        StockSoftOff off(param);
        ret = origHardwareBuffer(core, effect, inputs.data(), n, out, param, metadata, flags);
    }
    if (scattered) AHardwareBuffer_release(scattered);
    return ret;
}

// The filter node's output of a MonoPan50 capture gets the original grain (range 1 = full).
int hookHardwareBuffer(void *core, void *effect, const HardwareBufferInfo *in, unsigned n, const HardwareBufferInfo *out,
                       const void *param, const void *metadata, int flags) {
    int ret = renderHardwareBuffer(core, effect, in, n, out, param, metadata, flags);
    __android_log_print(ANDROID_LOG_INFO, TAG, "MsRenderHardwareBuffer ret %d, out %p, range %d, monopan %d", ret,
                        out ? static_cast<void *>(out->buffer) : nullptr, out ? out->range : -1, int(monoPanSelected()));
    if (ret > 0 && out && out->buffer) {
        if (monoPanSelected()) {
            monoPanFrame(out->buffer, out->range == 1);
        } else if (hncs_grain::isHncsGrainActive() || x100vi_grain::isX100viGrainActive()) {
            Transfer tr;
            if (transferOf(uint32_t(out->transfer), tr)) {
                grainHardwareBuffer(out->buffer, tr, yuvCoding(out->matrix, out->range));
            } else {
                __android_log_print(ANDROID_LOG_WARN, TAG, "post grain: unsupported transfer %d", out->transfer);
            }
        }
    }
    return ret;
}

// ---- capture through host buffers (the V1 filter node: GR) -----------------------------------
//
// The GR mode runs the camera's older filter node (gr_basictone.cpp), which renders host (CPU)
// buffers: the HDR entry for a 10-bit frame (P010, yuv type 5) and the plain entry for an 8-bit
// frame (NV21, yuv type 1). The frame is the same HLG signal the master filter node gets. An
// element of the info array, as the node fills it (0xd0 bytes):
//   +0x00 / +0x08 plane 0 / plane 1 data, +0x20 / +0x24 their row strides in bytes,
//   +0x30 width, +0x34 height, +0x38 yuv type, +0x3c primaries, +0x40 transfer (2 = HLG),
//   +0x44 yuv matrix, +0x48 yuv range.
// The plain entry takes the first 0x30 bytes as the frame and the rest as arguments. As with the
// hardware buffer, the scatter runs on a private copy of the input and the filter renders it.

constexpr size_t kHostElement = 0xd0;
constexpr int kYuvNv21 = 1, kYuvP010 = 5;

struct HostFrame {
    uint8_t *y, *uv;
    uint32_t yStride, uvStride;
    int w, h, type, matrix, range;
};

bool readHostFrame(const void *element, HostFrame &f) {
    auto *e = static_cast<const uint8_t *>(element);
    std::memcpy(&f.y, e + 0x00, sizeof f.y);
    std::memcpy(&f.uv, e + 0x08, sizeof f.uv);
    std::memcpy(&f.yStride, e + 0x20, sizeof f.yStride);
    std::memcpy(&f.uvStride, e + 0x24, sizeof f.uvStride);
    std::memcpy(&f.w, e + 0x30, sizeof f.w);
    std::memcpy(&f.h, e + 0x34, sizeof f.h);
    std::memcpy(&f.type, e + 0x38, sizeof f.type);
    std::memcpy(&f.matrix, e + 0x44, sizeof f.matrix);
    std::memcpy(&f.range, e + 0x48, sizeof f.range);
    return true;
}

struct HostCopy {
    std::unique_ptr<uint8_t[]> y, uv;
};

// Scatters a private copy of the frame; false leaves the stock path.
bool scatterHostFrame(const HostFrame &f, const SoftFilter &filter, HostCopy &copy) {
    if ((f.type != kYuvP010 && f.type != kYuvNv21) || !f.y || !f.uv || f.w < 16 || f.h < 16 || f.yStride < uint32_t(f.w) ||
        f.uvStride < uint32_t(f.w)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "host: frame left to the stock soft light (type %d, %dx%d, strides %u/%u)", f.type, f.w, f.h,
                            f.yStride, f.uvStride);
        return false;
    }
    size_t ySize = size_t(f.yStride) * f.h, uvSize = size_t(f.uvStride) * ((f.h + 1) / 2);
    copy.y.reset(new (std::nothrow) uint8_t[ySize]);
    copy.uv.reset(new (std::nothrow) uint8_t[uvSize]);
    if (!copy.y || !copy.uv) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "host: input copy not allocatable (%zu bytes)", ySize + uvSize);
        return false;
    }
    auto start = std::chrono::steady_clock::now();
    std::memcpy(copy.y.get(), f.y, ySize);
    std::memcpy(copy.uv.get(), f.uv, uvSize);
    YuvCoding coding = yuvCoding(f.matrix, f.range);
    __android_log_print(ANDROID_LOG_INFO, TAG, "host capture %dx%d %s, matrix %d range %d, focal %.0f mm", f.w, f.h,
                        f.type == kYuvP010 ? "P010" : "NV21", f.matrix, f.range, double(gFocalMm.load()));
    if (f.type == kYuvP010) {
        P010Image img;
        img.w = f.w & ~1;
        img.h = f.h & ~1;
        img.yBase = copy.y.get();
        img.yStride = f.yStride;
        img.uBase = copy.uv.get();
        img.vBase = copy.uv.get() + 2;
        img.uStride = img.vStride = f.uvStride;
        img.uPixel = img.vPixel = 4;
        img.c = coding;
        img.detectJustification();
        if (!scatterYuvGpu({true, img.yBase, img.uBase, f.yStride, f.uvStride, img.w, img.h, img.c}, filter, kHlg)) {
            scatterImage(img, filter, kHlg, img.c);
        }
    } else {
        Nv21Image img(copy.y.get(), f.yStride, copy.uv.get(), f.uvStride, f.w & ~1, f.h & ~1, coding);
        if (!scatterYuvGpu({false, copy.y.get(), copy.uv.get(), f.yStride, f.uvStride, img.w, img.h, img.c}, filter, kHlg)) {
            scatterImage(img, filter, kHlg, img.c);
        }
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "host capture took %lld ms", (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start).count());
    return true;
}

// The input array element of an info array call, pointing at the scattered copy.
struct HostElement {
    uint8_t bytes[kHostElement];
    void point(const HostCopy &copy) {
        uint8_t *y = copy.y.get(), *uv = copy.uv.get();
        std::memcpy(bytes + 0x00, &y, sizeof y);
        std::memcpy(bytes + 0x08, &uv, sizeof uv);
    }
};

void grainHostFrame(const HostFrame &f, Transfer tr) {
    if ((f.type != kYuvP010 && f.type != kYuvNv21) || !f.y || !f.uv || f.w < 16 || f.h < 16) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "hncs grain host skip: type=%d ptr=%p/%p %dx%d",
                            f.type, static_cast<const void *>(f.y), static_cast<const void *>(f.uv), f.w, f.h);
        return;
    }
    YuvCoding coding = yuvCoding(f.matrix, f.range);
    bool msb = false;
    if (f.type == kYuvP010) {
        P010Image img;
        img.w = f.w & ~1; img.h = f.h & ~1;
        img.yBase = f.y; img.yStride = f.yStride;
        img.detectJustification();
        msb = img.c.msb;
    }
    if (x100vi_grain::isX100viGrainActive()) {
        x100vi_grain::X100viGrainFrame xf{
            f.type == kYuvP010, f.y, f.uv, f.yStride, f.uvStride,
            f.w & ~1, f.h & ~1, coding.kr, coding.kb, coding.full, msb, tr == kHlg,
            f.type == kYuvNv21
        };
        withPrivateGlContext([&] { return x100vi_grain::applyX100viGrainGpu(xf); });
    } else if (hncs_grain::isHncsGrainActive()) {
        hncs_grain::HncsGrainFrame gf{
            f.type == kYuvP010, f.y, f.uv, f.yStride, f.uvStride,
            f.w & ~1, f.h & ~1, coding.kr, coding.kb, coding.full, msb, tr == kHlg,
            f.type == kYuvNv21
        };
        withPrivateGlContext([&] { return hncs_grain::applyHncsGrainGpu(gf); });
    }
}

using RenderPlain = int (*)(void *, void *, const void *, const void *, int, int, int, const void *, int, int, int);
RenderPlain origPlain;
int hookPlain(void *core, void *effect, const void *in, const void *out, int type, int matrix, int range, const void *param, int w, int h,
              int flags) {
    const SoftFilter *filter = filterFor(param);
    gCaptureSoft.store(filter != nullptr);
    HostFrame f;
    HostCopy copy;
    int ret;
    if (filter && in && out) {
        // The frame is described by the first 0x30 bytes and the arguments.
        uint8_t element[kHostElement] = {};
        std::memcpy(element, in, 0x30);
        std::memcpy(element + 0x30, &w, sizeof w);
        std::memcpy(element + 0x34, &h, sizeof h);
        std::memcpy(element + 0x38, &type, sizeof type);
        std::memcpy(element + 0x44, &matrix, sizeof matrix);
        std::memcpy(element + 0x48, &range, sizeof range);
        readHostFrame(element, f);
        if (scatterHostFrame(f, *filter, copy)) {
            uint8_t patched[0x30];
            std::memcpy(patched, in, sizeof patched);
            uint8_t *y = copy.y.get(), *uv = copy.uv.get();
            std::memcpy(patched + 0x00, &y, sizeof y);
            std::memcpy(patched + 0x08, &uv, sizeof uv);
            StockSoftOff off(param);
            ret = origPlain(core, effect, patched, out, type, matrix, range, param, w, h, flags);
        } else {
            ret = origPlain(core, effect, in, out, type, matrix, range, param, w, h, flags);
        }
    } else {
        ret = origPlain(core, effect, in, out, type, matrix, range, param, w, h, flags);
    }
    if (ret > 0 && out && (hncs_grain::isHncsGrainActive() || x100vi_grain::isX100viGrainActive())) {
        HostFrame outF;
        uint8_t element[kHostElement] = {};
        std::memcpy(element, out, 0x30);
        std::memcpy(element + 0x30, &w, sizeof w);
        std::memcpy(element + 0x34, &h, sizeof h);
        std::memcpy(element + 0x38, &type, sizeof type);
        std::memcpy(element + 0x44, &matrix, sizeof matrix);
        std::memcpy(element + 0x48, &range, sizeof range);
        if (readHostFrame(element, outF)) grainHostFrame(outF, outF.type == kYuvP010 ? kHlg : kSrgb);
    }
    return ret;
}

using RenderHostBuffer = int (*)(void *, void *, const void *, unsigned, const void *, const void *, int);
RenderHostBuffer origHostBuffer;
int hookHostBuffer(void *core, void *effect, const void *in, unsigned n, const void *out, const void *param, int flags) {
    const SoftFilter *filter = filterFor(param);
    gCaptureSoft.store(filter != nullptr);
    HostFrame f;
    HostCopy copy;
    int ret;
    if (filter && in && out && n == 1 && readHostFrame(in, f) && scatterHostFrame(f, *filter, copy)) {
        HostElement element;
        std::memcpy(element.bytes, in, kHostElement);
        element.point(copy);
        StockSoftOff off(param);
        ret = origHostBuffer(core, effect, element.bytes, n, out, param, flags);
    } else {
        ret = origHostBuffer(core, effect, in, n, out, param, flags);
    }
    if (ret > 0 && out && n == 1 && (hncs_grain::isHncsGrainActive() || x100vi_grain::isX100viGrainActive())) {
        HostFrame outF;
        if (readHostFrame(out, outF)) {
            int32_t outTr = 0;
            std::memcpy(&outTr, static_cast<const uint8_t *>(out) + 0x40, sizeof outTr);
            grainHostFrame(outF, (outF.type == kYuvP010 || outTr == 2) ? kHlg : kSrgb);
        }
    }
    return ret;
}

using RenderHdrHostBuffer = int (*)(void *, void *, const void *, unsigned, const void *, const void *, const void *, int);
RenderHdrHostBuffer origHdrHostBuffer;
int hookHdrHostBuffer(void *core, void *effect, const void *in, unsigned n, const void *out, const void *param, const void *meta, int flags) {
    const SoftFilter *filter = filterFor(param);
    gCaptureSoft.store(filter != nullptr);
    HostFrame f;
    HostCopy copy;
    int ret;
    if (filter && in && out && n == 1 && readHostFrame(in, f) && scatterHostFrame(f, *filter, copy)) {
        HostElement element;
        std::memcpy(element.bytes, in, kHostElement);
        element.point(copy);
        StockSoftOff off(param);
        ret = origHdrHostBuffer(core, effect, element.bytes, n, out, param, meta, flags);
    } else {
        ret = origHdrHostBuffer(core, effect, in, n, out, param, meta, flags);
    }
    if (ret > 0 && out && n == 1 && (hncs_grain::isHncsGrainActive() || x100vi_grain::isX100viGrainActive())) {
        HostFrame outF;
        if (readHostFrame(out, outF)) {
            int32_t outTr = 0;
            std::memcpy(&outTr, static_cast<const uint8_t *>(out) + 0x40, sizeof outTr);
            grainHostFrame(outF, (outF.type == kYuvP010 || outTr == 2) ? kHlg : kSrgb);
        }
    }
    return ret;
}

// ---- capture: HDRTransform's local fusion off -------------------------------------------------
//
// The quick image and the preview render the HLG frame through the master tone curve alone; the
// main image goes through HDRTransform processMasterV2, whose TmcDownTransEF::odtFusionPro (at 1K)
// takes the base from the tone-curve rendition and the detail of two pyramid levels from the
// linear rendition. That adds about 8 % local contrast at 0.03-0.5 degree over the whole frame,
// the scales the soft light lowers. With 关闭对比度增强 on and a replaced soft light the fused image is the tone-curve
// rendition itself, so the main image renders as the preview does.
//
// static odtFusionPro(CLBuffer &bright, CLBuffer &dark, CLBuffer &out, bool, bool): bright feeds
// buildPyramid, dark buildPyramidLaplacian.

using OdtFusionPro = int (*)(void *bright, void *dark, void *out, bool, bool);
using CopyTo = int (*)(const void *self, void *dst, const bool &blocking);
// Both HDRTransform libraries carry their own copy; the main image uses the Camera one.
template <int Lib>
struct FusionHook {
    static OdtFusionPro orig;
    static CopyTo copyTo;
    static int call(void *bright, void *dark, void *out, bool a, bool b) {
        if (!gNoLocalContrast.load() || !gCaptureSoft.load() || !copyTo) return orig(bright, dark, out, a, b);
        bool blocking = true;
        int ret = copyTo(bright, out, blocking);
        __android_log_print(ANDROID_LOG_INFO, TAG, "capture: HDRTransform local fusion off (lib %d, copy %d)", Lib, ret);
        return 0;
    }
    static void install(HookFunction hook, const char *name, void *handle) {
        copyTo = reinterpret_cast<CopyTo>(dlsym(handle, "_ZNK9framework3ocl8CLBuffer6copyToERS1_RKb"));
        hookExport(hook, handle, "_ZN14TmcDownTransEF12odtFusionProERN9framework3ocl8CLBufferES3_S3_bb",
                   reinterpret_cast<void *>(&call), reinterpret_cast<void **>(&orig), TAG);
        __android_log_print(ANDROID_LOG_INFO, TAG, "odtFusionPro hooked in %s (copyTo %p)", name, reinterpret_cast<void *>(copyTo));
    }
};
template <int Lib> OdtFusionPro FusionHook<Lib>::orig = nullptr;
template <int Lib> CopyTo FusionHook<Lib>::copyTo = nullptr;

}  // namespace

float softLightFocalMm() { return gFocalMm.load(); }

void onLibraryLoadedForSoftLight(HookFunction hook, const char *name, void *handle) {
    static bool fusionCamera = false, fusionQuick = false;
    if (!fusionCamera && std::strstr(name, "libOPAlgoCamHDRTransformCamera.so")) {
        fusionCamera = true;
        FusionHook<0>::install(hook, name, handle);
    } else if (!fusionQuick && std::strstr(name, "libOPAlgoCamHDRTransformQuick.so")) {
        fusionQuick = true;
        FusionHook<1>::install(hook, name, handle);
    }
    // libAlgoInterface dlopens and dlcloses libmsnativefilter.so per capture session, unmapping
    // previous inline patches on APS_UNINIT; re-hook whenever the freshly mapped code lacks our patch.
    if (!std::strstr(name, "libmsnativefilter.so")) return;
    struct ExportSlot {
        const char *symbol;
        void *replacement;
        void **backup;
        void *hookedAddr = nullptr;
        uint8_t patchedHead[16] = {};
        bool valid = false;
    };
    static std::mutex hookMutex;
    static ExportSlot slots[] = {
        {"MsRenderHDRTexture", reinterpret_cast<void *>(hookHdrTexture), reinterpret_cast<void **>(&origHdrTexture)},
        {"MsRenderHardwareBuffer", reinterpret_cast<void *>(hookHardwareBuffer), reinterpret_cast<void **>(&origHardwareBuffer)},
        {"MsRender", reinterpret_cast<void *>(hookPlain), reinterpret_cast<void **>(&origPlain)},
        {"MsRenderHostBuffer", reinterpret_cast<void *>(hookHostBuffer), reinterpret_cast<void **>(&origHostBuffer)},
        {"MsRenderHDRHostBuffer", reinterpret_cast<void *>(hookHdrHostBuffer), reinterpret_cast<void **>(&origHdrHostBuffer)},
    };
    std::lock_guard<std::mutex> guard(hookMutex);
    int hookedCount = 0;
    for (ExportSlot &slot : slots) {
        void *addr = dlsym(handle, slot.symbol);
        if (!addr) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym missed: %s", slot.symbol);
            continue;
        }
        if (slot.valid && slot.hookedAddr == addr && std::memcmp(addr, slot.patchedHead, sizeof(slot.patchedHead)) == 0) {
            continue;
        }
        if (hook(addr, slot.replacement, slot.backup) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "native re-hook failed: %s @ %p (prev=%p)",
                                slot.symbol, addr, slot.hookedAddr);
            continue;
        }
        slot.hookedAddr = addr;
        std::memcpy(slot.patchedHead, addr, sizeof(slot.patchedHead));
        slot.valid = true;
        ++hookedCount;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "libmsnativefilter loaded (%s, handle=%p): hooked %d exports (hwBuf=%p)",
                        name, handle, hookedCount, slots[1].hookedAddr);
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetFocal(JNIEnv *, jclass, jfloat millimetres) {
    if (std::fabs(gFocalMm.exchange(millimetres) - millimetres) > 0.5f) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "focal %.1f mm", double(millimetres));
    }
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetMasterMode(JNIEnv *, jclass, jboolean master) {
    if (gMasterMode.exchange(master) != bool(master)) __android_log_print(ANDROID_LOG_INFO, TAG, "master mode %s", master ? "on" : "off");
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetNoLocalContrast(JNIEnv *, jclass, jboolean on) {
    gNoLocalContrast.store(on);
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetLevels(JNIEnv *, jclass, jboolean weak,
                                                                                           jboolean medium, jboolean strong) {
    // ProMist types: weak (柔美) 1, medium (朦胧) 0, strong (梦幻) 2.
    gLevels.store((weak ? 1 << 1 : 0) | (medium ? 1 << 0 : 0) | (strong ? 1 << 2 : 0));
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetHncsGrain(
    JNIEnv *, jclass, jboolean masterMode, jboolean enabled, jint type, jint amount, jint size, jint roughness, jint color) {
    hncs_grain::setHncsGrainState(masterMode, enabled, type, amount, size, roughness, color);
}

extern "C" JNIEXPORT void JNICALL Java_local_jiege_hook_softlight_SoftLight_nativeSetX100viGrain(
    JNIEnv *, jclass, jboolean masterMode, jboolean enabled, jint strength, jint size, jboolean isAcros) {
    x100vi_grain::setX100viGrainState(masterMode, enabled, strength, size, isAcros);
}
