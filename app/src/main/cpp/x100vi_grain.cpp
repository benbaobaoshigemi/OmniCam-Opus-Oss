#include "x100vi_grain.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <map>
#include <mutex>
#include <vector>

namespace x100vi_grain {

namespace {

constexpr const char *kTag = "X100viGrain";
constexpr double kRefDiagPx = 9287.619770425573;  // hypot(7728, 5152)
constexpr double kFilm35mmDiagUm = 43270.0;
constexpr double kPi = 3.14159265358979323846;

struct GrainState {
    std::atomic<bool> masterMode{false};
    std::atomic<bool> enabled{false};
    std::atomic<int> strength{0};  // 0=off, 1=weak, 2=strong
    std::atomic<int> size{0};      // 0=small, 1=large
    std::atomic<bool> isAcros{false};
    std::atomic<uint32_t> shotCounter{1};
};

GrainState gState;

struct PresetSpec {
    int rHalf;
    float satA;
    float sigmaU8;
    const float *symVec;
    int symLen;
};

// 8-fold symmetric spatial kernels calibrated from X100VI 16-bit TIFF captures (grain_sweep.npz).
constexpr float kVecColorWeakSmall[15] = {
    1.0f, 0.33116f, 0.14895f, -0.00984f, -0.01873f, 0.24366f, 0.08451f, -0.02435f,
    -0.01773f, 0.0236f, -0.02192f, -0.01073f, -0.01738f, -0.00815f, -0.00521f,
};
constexpr float kVecColorStrongSmall[15] = {
    1.0f, 0.40531f, 0.15863f, -0.02756f, -0.02305f, 0.27077f, 0.08281f, -0.0401f,
    -0.02157f, 0.01405f, -0.02873f, -0.01325f, -0.02026f, -0.00774f, -0.00389f,
};
constexpr float kVecColorWeakLarge[10] = {
    1.0f, 0.04991f, -0.01990f, -0.00460f, -0.04136f, -0.02545f, -0.00465f, -0.00220f, -0.00187f, -0.00176f,
};
constexpr float kVecColorStrongLarge[10] = {
    1.0f, 0.05841f, -0.02504f, -0.00040f, -0.05131f, -0.03431f, -0.00312f, -0.00159f, -0.00239f, -0.00042f,
};
constexpr float kVecAcrosLarge[6] = {
    1.0f, 0.01164f, -0.01969f, -0.05149f, -0.011f, 0.01129f,
};

const PresetSpec &getPresetSpec(bool isAcros, int strength, int size) {
    static const PresetSpec kSpecs[8] = {
        {4, 0.0000f,  3.322f, kVecColorWeakSmall,   15},  // color, weak, small
        {3, 0.6532f,  7.676f, kVecColorWeakLarge,   10},  // color, weak, large
        {4, 0.0000f,  7.048f, kVecColorStrongSmall, 15},  // color, strong, small
        {3, 0.6942f, 15.860f, kVecColorStrongLarge, 10},  // color, strong, large
        {4, 0.0000f,  3.463f, kVecColorWeakSmall,   15},  // acros, weak, small
        {2, 0.9633f,  8.001f, kVecAcrosLarge,        6},  // acros, weak, large
        {4, 0.0000f,  7.347f, kVecColorStrongSmall, 15},  // acros, strong, small
        {2, 0.9633f, 16.532f, kVecAcrosLarge,        6},  // acros, strong, large
    };
    int idx = (isAcros ? 4 : 0) + ((strength == 2) ? 2 : 0) + ((size == 1) ? 1 : 0);
    return kSpecs[idx];
}

// Hardware PWL envelope LUTs (Y_out in [0, 1] -> gain) calibrated from de-clipped 12-grey camera curves.
constexpr int kColorLutCount = 10;
constexpr float kColorLutY[16] = {0.000f, 0.0375f, 0.0754f, 0.1315f, 0.2251f, 0.3419f, 0.7315f, 0.8492f, 0.9194f, 1.000f};
constexpr float kColorLutG[16] = {0.350f, 0.692f,  0.865f,  0.905f,  0.972f,  1.000f,  1.000f,  0.941f,  0.883f,  0.810f};

constexpr int kAcrosLutCount = 13;
constexpr float kAcrosLutY[16] = {
    0.000f, 0.0375f, 0.0754f, 0.1315f, 0.2251f, 0.3419f, 0.4582f, 0.5957f, 0.6597f, 0.7315f, 0.8492f, 0.9194f, 1.000f,
};
constexpr float kAcrosLutG[16] = {
    0.350f, 0.678f,  0.870f,  0.931f,  0.971f,  1.000f,  1.000f,  0.985f,  0.963f,  0.926f,  0.797f,  0.710f,  0.610f,
};

std::array<float, 81> buildSymKernel9x9(const PresetSpec &spec) {
    std::array<double, 81> raw{};
    int idx = 0;
    const int r = spec.rHalf;
    for (int dy = 0; dy <= r; ++dy) {
        for (int dx = dy; dx <= r; ++dx) {
            double v = spec.symVec[idx++];
            for (int sy : {-1, 1}) {
                for (int sx : {-1, 1}) {
                    int y1 = 4 + sy * dy;
                    int x1 = 4 + sx * dx;
                    int y2 = 4 + sy * dx;
                    int x2 = 4 + sx * dy;
                    raw[y1 * 9 + x1] = v;
                    raw[y2 * 9 + x2] = v;
                }
            }
        }
    }
    double sumSq = 0.0;
    for (double v : raw) sumSq += v * v;
    double invNorm = 1.0 / (std::sqrt(sumSq) + 1e-12);
    std::array<float, 81> out{};
    for (int i = 0; i < 81; ++i) out[i] = float(raw[i] * invNorm);
    return out;
}

float computeTanhInvStd(float satA) {
    if (satA <= 0.02f) return 1.0f;
    // Exact E[tanh(satA * Z)^2] for Z ~ N(0, 1) via 2001-point integration over [-8, 8].
    constexpr int N = 2000;
    constexpr double lo = -8.0, hi = 8.0, dz = (hi - lo) / N;
    const double invSqrt2Pi = 1.0 / std::sqrt(2.0 * kPi);
    double sum = 0.0;
    for (int i = 0; i <= N; ++i) {
        double z = lo + i * dz;
        double w = (i == 0 || i == N) ? 0.5 : 1.0;
        double t = std::tanh(double(satA) * z);
        double pdf = invSqrt2Pi * std::exp(-0.5 * z * z);
        sum += w * t * t * pdf;
    }
    double var = sum * dz;
    return float(1.0 / (std::sqrt(var) + 1e-12));
}

inline double sinc(double x) {
    if (std::abs(x) < 1e-8) return 1.0;
    double px = kPi * x;
    return std::sin(px) / px;
}

// Exact 2D Parseval ratio for 48 um aperture invariance across resolutions.
float computeApertureGain(const std::array<float, 81> &ker, int rHalf, double scale, double refPxUm) {
    constexpr int M = 64;
    const double sig48Ref = (48.0 / 4.0) / refPxUm;
    const double sigLp = 0.55 * scale;
    double num = 0.0;
    double den = 0.0;
    for (int iy = 0; iy < M; ++iy) {
        double v = (iy < M / 2 ? iy : iy - M) / double(M);
        for (int ix = 0; ix < M; ++ix) {
            double u = (ix < M / 2 ? ix : ix - M) / double(M);
            double re = 0.0;
            for (int dy = -rHalf; dy <= rHalf; ++dy) {
                for (int dx = -rHalf; dx <= rHalf; ++dx) {
                    re += ker[(dy + 4) * 9 + (dx + 4)] * std::cos(2.0 * kPi * (u * dx + v * dy));
                }
            }
            double psdK = re * re;
            double f2 = u * u + v * v;
            double h48 = std::exp(-4.0 * kPi * kPi * sig48Ref * sig48Ref * f2);
            double hResamp = 1.0;
            if (scale >= 1.0) {
                double hLp = std::exp(-4.0 * kPi * kPi * sigLp * sigLp * f2);
                double s = sinc(u) * sinc(v);
                double hBilin = s * s * s * s;
                hResamp = hLp * hBilin;
            } else {
                // Bicubic (Catmull-Rom) frequency roll-off is close to sinc^6 at low frequencies.
                double s = sinc(u) * sinc(v);
                hResamp = std::pow(s, 6.0);
            }
            num += psdK * h48;
            den += psdK * h48 * hResamp;
        }
    }
    if (den <= 1e-18) return 1.0f;
    return float(std::sqrt(num / den));
}

const char *kVert = R"(#version 300 es
precision highp float;
void main() {
    vec2 pos = vec2((gl_VertexID & 1) * 4 - 1, (gl_VertexID & 2) * 2 - 1);
    gl_Position = vec4(pos, 0.0, 1.0);
}
)";

// Stateless PCG32 + Box-Muller white Gaussian noise N(0, 1).
const char *kFragNoise = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;
uniform uint uSeed;

uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    uint idx = uint(p.x) * 1973u ^ uint(p.y) * 9277u ^ uSeed * 26699u;
    uint h1 = pcgHash(idx);
    uint h2 = pcgHash(idx ^ 0x9e3779b9u);
    float u1 = max(float(h1) / 4294967296.0, 1e-6);
    float u2 = float(h2) / 4294967296.0;
    float n = sqrt(-2.0 * log(u1)) * cos(6.28318530718 * u2);
    fragColor = vec4(n, 0.0, 0.0, 1.0);
}
)";

// Exact 2D 8-fold symmetric FIR convolution (up to 9x9, circular wrap) + tanh soft saturation.
const char *kFragConv = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;

uniform sampler2D uNoise;
uniform ivec2 uSize;
uniform int uR;
uniform float uKernel[81];
uniform float uSatA;
uniform float uTanhInvStd;
uniform float uGain;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float acc = 0.0;
    for (int dy = -uR; dy <= uR; ++dy) {
        int y = (p.y + dy + uSize.y) % uSize.y;
        int row = (dy + 4) * 9;
        for (int dx = -uR; dx <= uR; ++dx) {
            int x = (p.x + dx + uSize.x) % uSize.x;
            float w = uKernel[row + (dx + 4)];
            acc += w * texelFetch(uNoise, ivec2(x, y), 0).r;
        }
    }
    if (uSatA > 0.02) {
        acc = tanh(uSatA * acc) * uTanhInvStd;
    }
    fragColor = vec4(acc * uGain, 0.0, 0.0, 1.0);
}
)";

// Separable 1D Gaussian low-pass (circular wrap) for scale >= 1.02 before bilinear upsampling.
const char *kFragGauss1D = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;

uniform sampler2D uSrc;
uniform ivec2 uSize;
uniform ivec2 uDir;
uniform int uRadius;
uniform float uWeights[17];

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float acc = uWeights[0] * texelFetch(uSrc, p, 0).r;
    for (int i = 1; i <= uRadius; ++i) {
        ivec2 q1 = (p + uDir * i + uSize) % uSize;
        ivec2 q2 = (p - uDir * i + uSize) % uSize;
        acc += uWeights[i] * (texelFetch(uSrc, q1, 0).r + texelFetch(uSrc, q2, 0).r);
    }
    fragColor = vec4(acc, 0.0, 0.0, 1.0);
}
)";

// Resample from (uSrcSize) to target (uDstSize) with bilinear or Catmull-Rom bicubic, then scale by uGain.
const char *kFragResample = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;

uniform sampler2D uSrc;
uniform ivec2 uSrcSize;
uniform vec2 uDstSize;
uniform int uBicubic;
uniform float uGain;

float catmullRom(float t) {
    float a = -0.5;
    float x = abs(t);
    if (x <= 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
    if (x < 2.0) return ((a * x - 5.0 * a) * x + 8.0 * a) * x - 4.0 * a;
    return 0.0;
}

float fetchWrap(ivec2 q) {
    ivec2 m = ((q % uSrcSize) + uSrcSize) % uSrcSize;
    return texelFetch(uSrc, m, 0).r;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uDstSize;
    vec2 pos = uv * vec2(uSrcSize) - 0.5;
    ivec2 p0 = ivec2(floor(pos));
    vec2 f = pos - vec2(p0);

    float v = 0.0;
    if (uBicubic == 0) {
        float v00 = fetchWrap(p0 + ivec2(0, 0));
        float v10 = fetchWrap(p0 + ivec2(1, 0));
        float v01 = fetchWrap(p0 + ivec2(0, 1));
        float v11 = fetchWrap(p0 + ivec2(1, 1));
        v = mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
    } else {
        for (int dy = -1; dy <= 2; ++dy) {
            float wy = catmullRom(f.y - float(dy));
            for (int dx = -1; dx <= 2; ++dx) {
                float wx = catmullRom(f.x - float(dx));
                v += wx * wy * fetchWrap(p0 + ivec2(dx, dy));
            }
        }
    }
    fragColor = vec4(v * uGain, 0.0, 0.0, 1.0);
}
)";

const char *kBlendCommon = R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

out vec4 fragColor;

uniform highp usampler2D uY;
uniform highp usampler2D uUV;
uniform sampler2D uGrain;
uniform int uP010;
uniform int uMsb;
uniform int uUvSwap;
uniform float uScale;
uniform vec3 uCoding;  // kr, kb, full range
uniform int uHlg;
uniform float uSigma01;
uniform int uLutCount;
uniform float uLutY[16];
uniform float uLutG[16];

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

vec3 hlgInvOetf(vec3 v) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    v = clamp(v, 0.0, 1.0);
    vec3 lo = (v * v) / 3.0;
    vec3 hi = (exp((v - c) / a) + b) / 12.0;
    return mix(lo, hi, step(0.5, v));
}

vec3 hlgOetf(vec3 e) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    e = clamp(e, 0.0, 1.0);
    vec3 lo = sqrt(3.0 * e);
    vec3 hi = a * log(max(12.0 * e - b, vec3(1e-6))) + c;
    return mix(lo, hi, step(1.0 / 12.0, e));
}

vec3 extSrgbEncode(vec3 e) {
    vec3 lo = max(e, vec3(0.0)) * 12.92;
    vec3 hi = 1.055 * pow(max(e, vec3(0.0031308)), vec3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(0.0031308, e));
}

vec3 extSrgbDecode(vec3 v) {
    vec3 lo = max(v, vec3(0.0)) / 12.92;
    vec3 hi = pow((max(v, vec3(0.04045)) + 0.055) / 1.055, vec3(2.4));
    return mix(lo, hi, step(0.04045, v));
}

float evalPwl(float y) {
    float yc = clamp(y, 0.0, 1.0);
    for (int i = 0; i < 15; ++i) {
        if (i + 1 >= uLutCount) break;
        if (yc <= uLutY[i + 1] || i + 2 == uLutCount) {
            float denom = max(uLutY[i + 1] - uLutY[i], 1e-6);
            float t = clamp((yc - uLutY[i]) / denom, 0.0, 1.0);
            return mix(uLutG[i], uLutG[i + 1], t);
        }
    }
    return uLutG[uLutCount - 1];
}

vec3 grainDeltaYuv(ivec2 p) {
    vec2 uvC = uvAt(p >> 1);
    vec3 enc = clamp(yuvToRgb(yAt(p), uvC.x, uvC.y), 0.0, 1.0);
    vec3 I = (uHlg == 1) ? extSrgbEncode(hlgInvOetf(enc) / 0.26496256) : enc;
    float yEnc = clamp(dot(vec3(0.299, 0.587, 0.114), I), 0.0, 1.0);
    float env = evalPwl(yEnc);
    float g = texelFetch(uGrain, p, 0).r;
    float delta = g * uSigma01 * env;
    vec3 I_out = I + vec3(delta);
    vec3 encOut = (uHlg == 1)
        ? hlgOetf(clamp(extSrgbDecode(I_out) * 0.26496256, 0.0, 1.0))
        : clamp(I_out, 0.0, 1.0);
    vec3 d = encOut - enc;

    float kr = uCoding.x, kb = uCoding.y, kg = 1.0 - kr - kb;
    float dy = kr * d.r + kg * d.g + kb * d.b;
    float dcb = (d.b - dy) / (2.0 * (1.0 - kb));
    float dcr = (d.r - dy) / (2.0 * (1.0 - kr));
    bool full = uCoding.z > 0.5;
    return vec3(dy * (full ? 1023.0 : 876.0), dcb * (full ? 1023.0 : 896.0), dcr * (full ? 1023.0 : 896.0));
}

float hash32(uint v) {
    v ^= v >> 16; v *= 0x7feb352du; v ^= v >> 15; v *= 0x846ca68bu; v ^= v >> 16;
    return float(v & 0xffffffu) / 16777216.0;
}

float dither(ivec2 p, int salt) {
    uint k = uint(p.x) * 73856093u ^ uint(p.y) * 19349663u ^ uint(salt) * 83492791u;
    return hash32(k) + hash32(k + 0x9e3779b9u) - 1.0;
}

float quant(float v) { return floor(clamp(v, 0.0, 1023.0) + 0.5); }

vec2 word(float q) {
    uint raw = uMsb == 1 ? (uint(q) << 6) : uint(q);
    return vec2(float(raw & 255u), float(raw >> 8));
}

float byteOf(float raw, float units, float dith) {
    return clamp(floor(raw + units / uScale + dith + 0.5), 0.0, 255.0);
}
)";

const char *kFragOutLuma = R"(
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    float b[4];
    if (uP010 == 1) {
        for (int i = 0; i < 2; i++) {
            ivec2 p = ivec2(2 * t.x + i, t.y);
            vec2 w = word(quant(yAt(p) + grainDeltaYuv(p).x + dither(p, 0)));
            b[2 * i] = w.x;
            b[2 * i + 1] = w.y;
        }
    } else {
        for (int i = 0; i < 4; i++) {
            ivec2 p = ivec2(4 * t.x + i, t.y);
            b[i] = byteOf(float(texelFetch(uY, p, 0).r), grainDeltaYuv(p).x, dither(p, 0));
        }
    }
    fragColor = vec4(b[0], b[1], b[2], b[3]) / 255.0;
}
)";

const char *kFragOutChroma = R"(
void main() {
    ivec2 t = ivec2(gl_FragCoord.xy);
    float b[4];
    if (uP010 == 1) {
        ivec2 p = t * 2;
        vec3 d = grainDeltaYuv(p);
        vec2 c = uvAt(t);
        vec2 w0 = word(quant(c.x + d.y + dither(t, 1)));
        vec2 w1 = word(quant(c.y + d.z + dither(t, 2)));
        b[0] = uUvSwap == 1 ? w1.x : w0.x;
        b[1] = uUvSwap == 1 ? w1.y : w0.y;
        b[2] = uUvSwap == 1 ? w0.x : w1.x;
        b[3] = uUvSwap == 1 ? w0.y : w1.y;
    } else {
        for (int i = 0; i < 2; i++) {
            ivec2 c = ivec2(2 * t.x + i, t.y);
            vec3 d = grainDeltaYuv(c * 2);
            uvec2 r = rawUV(c);
            float u = byteOf(float(r.x), d.y, dither(c, 1));
            float v = byteOf(float(r.y), d.z, dither(c, 2));
            b[2 * i] = uUvSwap == 1 ? v : u;
            b[2 * i + 1] = uUvSwap == 1 ? u : v;
        }
    }
    fragColor = vec4(b[0], b[1], b[2], b[3]) / 255.0;
}
)";

GLuint compileShader(GLenum type, const char *src, const char *src2 = nullptr) {
    GLuint s = glCreateShader(type);
    const char *sources[2] = {src, src2};
    glShaderSource(s, src2 ? 2 : 1, sources, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetShaderInfoLog(s, sizeof log, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "shader compile failed: %s", log);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

GLuint linkProgram(const char *frag1, const char *frag2 = nullptr) {
    GLuint v = compileShader(GL_VERTEX_SHADER, kVert);
    GLuint f = compileShader(GL_FRAGMENT_SHADER, frag1, frag2);
    if (!v || !f) {
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {};
        glGetProgramInfoLog(p, sizeof log, nullptr, log);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

struct X100viPipeline {
    GLuint progNoise = 0;
    GLuint progConv = 0;
    GLuint progGauss1D = 0;
    GLuint progResample = 0;
    GLuint progOutLuma = 0;
    GLuint progOutChroma = 0;
    GLuint vao = 0;
    GLuint fbo = 0;
    bool r16fRenderable = false;
    bool initTried = false;

    bool ready() const {
        return progNoise && progConv && progGauss1D && progResample && progOutLuma && progOutChroma && vao && fbo;
    }
};

std::map<EGLContext, X100viPipeline> gPipelines;
std::mutex gPipeMutex;

bool initPipeline(X100viPipeline &p) {
    if (p.initTried) return p.ready();
    p.initTried = true;

    p.progNoise = linkProgram(kFragNoise);
    p.progConv = linkProgram(kFragConv);
    p.progGauss1D = linkProgram(kFragGauss1D);
    p.progResample = linkProgram(kFragResample);
    p.progOutLuma = linkProgram(kBlendCommon, kFragOutLuma);
    p.progOutChroma = linkProgram(kBlendCommon, kFragOutChroma);
    glGenVertexArrays(1, &p.vao);
    glGenFramebuffers(1, &p.fbo);

    GLuint probe = 0;
    glGenTextures(1, &probe);
    glBindTexture(GL_TEXTURE_2D, probe);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16F, 4, 4);
    glBindFramebuffer(GL_FRAMEBUFFER, p.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, probe, 0);
    p.r16fRenderable = (glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    glDeleteTextures(1, &probe);
    while (glGetError() != GL_NO_ERROR) {}

    return p.ready();
}

GLuint makeTex(GLenum internalFmt, int w, int h, GLenum format, GLenum type, GLint rowLength, const void *data) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFmt, w, h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (data) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, format, type, data);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    }
    return tex;
}

bool drawInto(const X100viPipeline &p, GLuint targetTex, int w, int h) {
    glBindFramebuffer(GL_FRAMEBUFFER, p.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, targetTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return false;
    glViewport(0, 0, w, h);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    return glGetError() == GL_NO_ERROR;
}

void bindUnit(int unit, GLuint tex) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
}

void setYuvUniforms(GLuint prog, const X100viGrainFrame &f, float scale) {
    glUniform1i(glGetUniformLocation(prog, "uY"), 0);
    glUniform1i(glGetUniformLocation(prog, "uUV"), 1);
    glUniform1i(glGetUniformLocation(prog, "uP010"), f.p010 ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uMsb"), f.msb ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uUvSwap"), f.vFirst ? 1 : 0);
    glUniform1f(glGetUniformLocation(prog, "uScale"), scale);
    glUniform3f(glGetUniformLocation(prog, "uCoding"), f.kr, f.kb, f.fullRange ? 1.0f : 0.0f);
}

}  // namespace

void setX100viGrainState(bool masterMode, bool enabled, int strength, int size, bool isAcros) {
    bool changed = false;
    changed |= (gState.masterMode.exchange(masterMode) != masterMode);
    changed |= (gState.enabled.exchange(enabled) != enabled);
    changed |= (gState.strength.exchange(strength) != strength);
    changed |= (gState.size.exchange(size) != size);
    changed |= (gState.isAcros.exchange(isAcros) != isAcros);
    if (changed) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "state: master=%d on=%d strength=%d size=%d acros=%d",
                            int(masterMode), int(enabled), strength, size, int(isAcros));
    }
}

bool isX100viGrainActive() {
    return gState.masterMode.load() && gState.enabled.load() && gState.strength.load() > 0;
}

bool applyX100viGrainGpu(const X100viGrainFrame &f) {
    if (!isX100viGrainActive()) return false;
    if (!f.y || !f.uv || f.w < 16 || f.h < 16) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "invalid frame %dx%d", f.w, f.h);
        return false;
    }
    if (f.w % (f.p010 ? 2 : 4) != 0 || f.h % 2 != 0 || f.yStride % 4 != 0 || f.uvStride % 4 != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unaligned frame p010=%d %dx%d strides=%u/%u",
                            int(f.p010), f.w, f.h, f.yStride, f.uvStride);
        return false;
    }

    const int strength = gState.strength.load();
    const int size = gState.size.load();
    const bool isAcros = gState.isAcros.load();
    if (strength <= 0) return false;

    const PresetSpec &spec = getPresetSpec(isAcros, strength, size);
    const std::array<float, 81> ker = buildSymKernel9x9(spec);
    const float tanhInvStd = computeTanhInvStd(spec.satA);

    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "no current EGLContext");
        return false;
    }

    std::lock_guard<std::mutex> lock(gPipeMutex);
    X100viPipeline &p = gPipelines[ctx];
    if (!initPipeline(p)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "initPipeline failed");
        return false;
    }

    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    const int w = f.w, h = f.h;
    const double refPxUm = kFilm35mmDiagUm / kRefDiagPx;
    const double tgtPxUm = kFilm35mmDiagUm / std::hypot(double(w), double(h));
    const double scale = tgtPxUm / refPxUm;
    const bool nativeRes = std::abs(scale - 1.0) < 0.02;
    const int wRef = nativeRes ? w : std::max(32, int(std::lround(w * scale)));
    const int hRef = nativeRes ? h : std::max(32, int(std::lround(h * scale)));

    if (std::max({w, h, wRef, hRef}) > maxSize) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "frame %dx%d (ref %dx%d) exceeds GL_MAX_TEXTURE_SIZE=%d",
                            w, h, wRef, hRef, maxSize);
        return false;
    }
    while (glGetError() != GL_NO_ERROR) {}

    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    for (GLuint u = 0; u < 6; ++u) glBindSampler(u, 0);
    glBindVertexArray(p.vao);

    const auto start = std::chrono::steady_clock::now();
    const GLenum lumaFmt = p.r16fRenderable ? GL_R16F : GL_RGBA16F;

    uint32_t shot = gState.shotCounter.fetch_add(1);
    uint64_t nowNs = static_cast<uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    uint32_t seed = uint32_t(shot * 2654435761u) ^ uint32_t(nowNs) ^ uint32_t(nowNs >> 32);

    GLuint texNoise = makeTex(lumaFmt, wRef, hRef, 0, 0, 0, nullptr);
    GLuint texRef = makeTex(lumaFmt, wRef, hRef, 0, 0, 0, nullptr);
    GLuint texTmp = (!nativeRes && scale >= 1.0) ? makeTex(lumaFmt, wRef, hRef, 0, 0, 0, nullptr) : 0;
    GLuint texLp = (!nativeRes && scale >= 1.0) ? makeTex(lumaFmt, wRef, hRef, 0, 0, 0, nullptr) : 0;
    GLuint texGrain = nativeRes ? texRef : makeTex(lumaFmt, w, h, 0, 0, 0, nullptr);

    bool ok = (glGetError() == GL_NO_ERROR);
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "alloc textures failed %dx%d ref=%dx%d", w, h, wRef, hRef);
    }

    // Pass 1: White Gaussian noise N(0, 1)
    if (ok) {
        glUseProgram(p.progNoise);
        glUniform1ui(glGetUniformLocation(p.progNoise, "uSeed"), seed);
        ok = drawInto(p, texNoise, wRef, hRef);
    }

    // Pass 2: 8-fold symmetric 2D FIR kernel + tanh saturation
    if (ok) {
        glUseProgram(p.progConv);
        bindUnit(0, texNoise);
        glUniform1i(glGetUniformLocation(p.progConv, "uNoise"), 0);
        glUniform2i(glGetUniformLocation(p.progConv, "uSize"), wRef, hRef);
        glUniform1i(glGetUniformLocation(p.progConv, "uR"), spec.rHalf);
        glUniform1fv(glGetUniformLocation(p.progConv, "uKernel"), 81, ker.data());
        glUniform1f(glGetUniformLocation(p.progConv, "uSatA"), spec.satA);
        glUniform1f(glGetUniformLocation(p.progConv, "uTanhInvStd"), tanhInvStd);
        glUniform1f(glGetUniformLocation(p.progConv, "uGain"), 1.0f);
        ok = drawInto(p, texRef, wRef, hRef);
    }

    if (texNoise) { glDeleteTextures(1, &texNoise); texNoise = 0; }

    // Pass 3 & 4: 48um aperture-invariant resampling when resolution differs from X100VI reference
    if (ok && !nativeRes) {
        GLuint resampleSrc = texRef;
        if (scale >= 1.0) {
            float sigLp = float(0.55 * scale);
            int rad = std::clamp(int(std::ceil(3.0f * sigLp)), 1, 16);
            float w1d[17] = {};
            double wSum = 1.0;
            w1d[0] = 1.0f;
            for (int i = 1; i <= rad; ++i) {
                double gw = std::exp(-0.5 * (double(i) / sigLp) * (double(i) / sigLp));
                w1d[i] = float(gw);
                wSum += 2.0 * gw;
            }
            for (int i = 0; i <= rad; ++i) w1d[i] = float(w1d[i] / wSum);

            glUseProgram(p.progGauss1D);
            bindUnit(0, texRef);
            glUniform1i(glGetUniformLocation(p.progGauss1D, "uSrc"), 0);
            glUniform2i(glGetUniformLocation(p.progGauss1D, "uSize"), wRef, hRef);
            glUniform2i(glGetUniformLocation(p.progGauss1D, "uDir"), 0, 1);
            glUniform1i(glGetUniformLocation(p.progGauss1D, "uRadius"), rad);
            glUniform1fv(glGetUniformLocation(p.progGauss1D, "uWeights"), 17, w1d);
            ok = drawInto(p, texTmp, wRef, hRef);

            if (ok) {
                bindUnit(0, texTmp);
                glUniform2i(glGetUniformLocation(p.progGauss1D, "uDir"), 1, 0);
                ok = drawInto(p, texLp, wRef, hRef);
                resampleSrc = texLp;
            }
        }
        if (ok) {
            float apGain = computeApertureGain(ker, spec.rHalf, scale, refPxUm);
            glUseProgram(p.progResample);
            bindUnit(0, resampleSrc);
            glUniform1i(glGetUniformLocation(p.progResample, "uSrc"), 0);
            glUniform2i(glGetUniformLocation(p.progResample, "uSrcSize"), wRef, hRef);
            glUniform2f(glGetUniformLocation(p.progResample, "uDstSize"), float(w), float(h));
            glUniform1i(glGetUniformLocation(p.progResample, "uBicubic"), scale < 1.0 ? 1 : 0);
            glUniform1f(glGetUniformLocation(p.progResample, "uGain"), apGain);
            ok = drawInto(p, texGrain, w, h);
        }
    }

    if (texTmp) { glDeleteTextures(1, &texTmp); texTmp = 0; }
    if (texLp) { glDeleteTextures(1, &texLp); texLp = 0; }
    if (!nativeRes && texRef) { glDeleteTextures(1, &texRef); texRef = 0; }

    // Pass 5: Output luma + chroma with PWL envelope & TPDF dither
    GLuint texY = 0, texUV = 0, outY = 0, outUV = 0;
    if (ok) {
        const int lumaPer = f.p010 ? 2 : 4;
        const int chromaPer = f.p010 ? 1 : 2;
        const float yuvScale = f.fullRange ? 1023.0f / 255.0f : 4.0f;

        texY = f.p010
            ? makeTex(GL_R16UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_SHORT, GLint(f.yStride / 2), f.y)
            : makeTex(GL_R8UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_BYTE, GLint(f.yStride), f.y);
        texUV = f.p010
            ? makeTex(GL_RG16UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_SHORT, GLint(f.uvStride / 4), f.uv)
            : makeTex(GL_RG8UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_BYTE, GLint(f.uvStride / 2), f.uv);
        outY = makeTex(GL_RGBA8, w / lumaPer, h, 0, 0, 0, nullptr);
        outUV = makeTex(GL_RGBA8, w / 2 / chromaPer, h / 2, 0, 0, 0, nullptr);
        ok = (glGetError() == GL_NO_ERROR);

        const float sigma01 = spec.sigmaU8 / 255.0f;
        const int lutCount = isAcros ? kAcrosLutCount : kColorLutCount;
        const float *lutY = isAcros ? kAcrosLutY : kColorLutY;
        const float *lutG = isAcros ? kAcrosLutG : kColorLutG;

        auto writePlane = [&](GLuint prog, GLuint target, int tw, int th, void *dst, size_t stride) -> bool {
            glUseProgram(prog);
            bindUnit(0, texY);
            bindUnit(1, texUV);
            bindUnit(2, texGrain);
            setYuvUniforms(prog, f, yuvScale);
            glUniform1i(glGetUniformLocation(prog, "uGrain"), 2);
            glUniform1i(glGetUniformLocation(prog, "uHlg"), f.hlg ? 1 : 0);
            glUniform1f(glGetUniformLocation(prog, "uSigma01"), sigma01);
            glUniform1i(glGetUniformLocation(prog, "uLutCount"), lutCount);
            glUniform1fv(glGetUniformLocation(prog, "uLutY"), 16, lutY);
            glUniform1fv(glGetUniformLocation(prog, "uLutG"), 16, lutG);
            if (!drawInto(p, target, tw, th)) return false;
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glPixelStorei(GL_PACK_ROW_LENGTH, GLint(stride / 4));
            glReadPixels(0, 0, tw, th, GL_RGBA, GL_UNSIGNED_BYTE, dst);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            return glGetError() == GL_NO_ERROR;
        };

        if (ok) ok = writePlane(p.progOutLuma, outY, w / lumaPer, h, f.y, f.yStride);
        if (ok) ok = writePlane(p.progOutChroma, outUV, w / 2 / chromaPer, h / 2, f.uv, f.uvStride);
    }

    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
    GLuint del[] = {texGrain, texY, texUV, outY, outUV};
    glDeleteTextures(5, del);

    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "capture %s %dx%d %s strength=%d size=%d acros=%d scale=%.3f: %s in %lld ms",
                        f.p010 ? "P010" : "NV21", w, h, f.hlg ? "HLG" : "sRGB",
                        strength, size, int(isAcros), scale,
                        ok ? "done" : "failed",
                        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start).count());
    return ok;
}

}  // namespace x100vi_grain
