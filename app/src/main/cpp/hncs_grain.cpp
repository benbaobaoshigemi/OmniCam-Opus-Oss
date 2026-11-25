#include "hncs_grain.h"
#include "hncs_grain_math.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <map>
#include <mutex>
#include <random>
#include <string>
#include <vector>

namespace hncs_grain {
namespace {

constexpr const char *kTag = "HncsGrain";

struct AtomicGrainState {
    std::atomic<bool> masterMode{false};
    std::atomic<bool> enabled{false};
    std::atomic<int> type{1};
    std::atomic<int> amount{40};
    std::atomic<int> size{40};
    std::atomic<int> roughness{15};
    std::atomic<int> color{0};
    std::atomic<uint32_t> shotCounter{1};
};

AtomicGrainState gState;

const char *kVertex = R"(#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

// Pass 1: 3D Simplex multi-channel film grain octave (Phocus GenFilmGrain).
const char *kFragGen = R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

out vec4 fragColor;

uniform highp usampler2D uRand0; // 441 x 21 (21^3)
uniform highp usampler2D uRand1; // 484 x 22 (22^3)
uniform highp usampler2D uRand2; // 625 x 25 (25^3)
uniform vec2 uFreq;             // res_k / multiplier
uniform vec2 uOfs;              // spatial jitter + harmonic shift
uniform int uColor;
uniform float uColorMix;        // min(color, 20) * 0.05

ivec3 wrapIdx(ivec3 c, int n) {
    ivec3 m = c % n;
    return m + ivec3(lessThan(m, ivec3(0))) * n;
}

vec3 gradAt(ivec3 c) {
    ivec3 c0 = wrapIdx(c, 21);
    ivec3 c1 = wrapIdx(c, 22);
    ivec3 c2 = wrapIdx(c, 25);
    uint f0 = texelFetch(uRand0, ivec2(c0.x + c0.z * 21, c0.y), 0).r;
    uint f1 = texelFetch(uRand1, ivec2(c1.x + c1.z * 22, c1.y), 0).r;
    uint f2 = texelFetch(uRand2, ivec2(c2.x + c2.z * 25, c2.y), 0).r;
    uvec3 u = uvec3(f1 ^ f2, f2 ^ f0, f1 ^ f0);
    return (vec3(u) - 32767.0) * (1.0 / 65535.0);
}

float simplex3(vec3 v) {
    const float F3 = 1.0 / 3.0;
    const float G3 = 1.0 / 6.0;
    float s = (v.x + v.y + v.z) * F3;
    vec3 i = floor(v + s);
    float t = (i.x + i.y + i.z) * G3;
    vec3 x0 = v - i + t;

    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);

    vec3 x1 = x0 - i1 + G3;
    vec3 x2 = x0 - i2 + (2.0 * G3);
    vec3 x3 = x0 - 0.5;

    ivec3 ii = ivec3(i);
    ivec3 ii1 = ii + ivec3(i1);
    ivec3 ii2 = ii + ivec3(i2);
    ivec3 ii3 = ii + ivec3(1);

    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    m = m * m;

    vec4 d = vec4(
        dot(gradAt(ii),  x0),
        dot(gradAt(ii1), x1),
        dot(gradAt(ii2), x2),
        dot(gradAt(ii3), x3)
    );
    return 52.0 * dot(m, d);
}

void main() {
    vec2 pos = (floor(gl_FragCoord.xy) + uOfs) * uFreq;
    float n0 = simplex3(vec3(pos, 0.0));
    if (uColor > 0) {
        float n1 = simplex3(vec3(pos, 1.0));
        float n2 = simplex3(vec3(pos, 2.0));
        fragColor = vec4(mix(n0, n1, uColorMix), n0, mix(n0, n2, uColorMix), 1.0);
    } else {
        fragColor = vec4(n0, n0, n0, 1.0);
    }
}
)";

// Pass 2: Vertical 9-tap separable blur on grain luma (Phocus UnsharpMaskVer in [0..1] scale).
const char *kFragUsmV = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;

uniform sampler2D uOct;
uniform int uColor;
uniform float uWeights[5];
uniform ivec2 uMaxCoord;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float acc = 0.0;
    for (int dy = -4; dy <= 4; ++dy) {
        int aw = dy < 0 ? -dy : dy;
        float w = uWeights[aw];
        if (w <= 0.0) continue;
        ivec2 q = ivec2(p.x, clamp(p.y + dy, 0, uMaxCoord.y));
        vec3 g = texelFetch(uOct, q, 0).rgb;
        float luma = uColor > 0 ? dot(vec3(0.299, 0.587, 0.114), g) : g.r;
        float v01 = clamp(0.125 + 0.125 * luma, 0.0, 1.0);
        acc += w * v01;
    }
    fragColor = vec4(acc, acc, acc, 1.0);
}
)";

// Pass 3: Horizontal USM + optional harmonic accumulation blend (UnsharpMaskMetalBlend / BlendHarmonic).
const char *kFragUsmHBlend = R"(#version 300 es
precision highp float;
precision highp int;

out vec4 fragColor;

uniform sampler2D uOct;
uniform sampler2D uBlurV;
uniform sampler2D uAcc;
uniform int uColor;
uniform int uDoUsm;
uniform int uHasBlend;
uniform float uSharpAmount;
uniform float uBlendAmount;
uniform float uWeights[5];
uniform ivec2 uMaxCoord;

float usmCalcAmount(float sharpAmount, float v01) {
    float clampAmt = clamp(1638400.0 / (sharpAmount + 100.0), 1000.0, 10000.0);
    float t0 = clampAmt * 0.00006103515625;
    float q = (1.0 - t0 - 3.0) * (v01 * v01) + 3.0 * v01 + t0;
    float amt = (0.0 < q && q < 1.0) ? (q * (sharpAmount + 100.0) - 100.0) : sharpAmount;
    return clamp(amt, 0.0, 1000.0) * 0.01;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 oct = texelFetch(uOct, p, 0).rgb;
    if (uColor == 0) oct = vec3(oct.r);

    if (uDoUsm == 1 && uSharpAmount > 0.0) {
        float b = 0.0;
        for (int dx = -4; dx <= 4; ++dx) {
            int aw = dx < 0 ? -dx : dx;
            float w = uWeights[aw];
            if (w <= 0.0) continue;
            ivec2 q = ivec2(clamp(p.x + dx, 0, uMaxCoord.x), p.y);
            b += w * texelFetch(uBlurV, q, 0).r;
        }
        float luma = uColor > 0 ? dot(vec3(0.299, 0.587, 0.114), oct) : oct.r;
        float v = clamp(0.125 + 0.125 * luma, 0.0, 1.0);
        float amt = usmCalcAmount(uSharpAmount, v);
        float delta01 = amt * (v - b);
        vec3 sharpened01 = clamp(0.125 + 0.125 * oct + vec3(delta01), 0.0, 1.0);
        oct = (sharpened01 - 0.125) / 0.125;
    }

    if (uHasBlend == 1) {
        vec3 prev = texelFetch(uAcc, p, 0).rgb;
        if (uColor == 0) prev = vec3(prev.r);
        oct = mix(prev, oct, uBlendAmount);
    }

    fragColor = vec4(oct, 1.0);
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
uniform vec3 uCoding;       // kr, kb, full range
uniform int uHlg;
uniform int uColor;
uniform int uUseLumChannel; // 0 = RGB, 2 = YCoCg
uniform float uInvGamma;
uniform float uBrightScale;
uniform vec3 uAlphaBlend;

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

vec3 grainDeltaYuv(ivec2 p) {
    vec2 uvC = uvAt(p >> 1);
    vec3 enc = clamp(yuvToRgb(yAt(p), uvC.x, uvC.y), 0.0, 1.0);
    vec3 I = (uHlg == 1) ? extSrgbEncode(hlgInvOetf(enc) / 0.26496256) : enc;
    vec3 g = uColor > 0 ? texelFetch(uGrain, p, 0).rgb : vec3(texelFetch(uGrain, p, 0).r);

    vec3 dI;
    if (uUseLumChannel == 2) {
        float refY = (I.r + 2.0 * I.g + I.b) * 0.25;
        float gY = (g.r + 2.0 * g.g + g.b) * 0.25;
        if (gY > 0.0) gY *= uBrightScale;
        float gU = g.r - g.g;
        float gV = g.b - g.g;
        float mag = abs(refY) * (65535.0 * 0.25);
        float w = mag > 1e-5 ? pow(mag, uInvGamma) : 0.0;
        vec3 dYuv = vec3(gY, gU, gV) * w * (uAlphaBlend / 65535.0);
        dI = vec3(
            dYuv.x + (3.0 * dYuv.y - dYuv.z) * 0.25,
            dYuv.x - (dYuv.y + dYuv.z) * 0.25,
            dYuv.x + (3.0 * dYuv.z - dYuv.y) * 0.25
        );
    } else {
        vec3 gs = mix(g, g * uBrightScale, step(0.0, g));
        vec3 mag = abs(I) * (65535.0 * 0.25);
        vec3 w = mix(vec3(0.0), pow(max(mag, vec3(1e-5)), vec3(uInvGamma)), greaterThan(mag, vec3(1e-5)));
        dI = gs * w * (uAlphaBlend / 65535.0);
    }

    vec3 I_out = I + dI;
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
    int n = uP010 == 1 ? 1 : 2;
    for (int i = 0; i < n; i++) {
        ivec2 blk = ivec2(n * t.x + i, t.y);
        vec2 s = vec2(0.0);
        for (int j = 0; j < 2; j++) {
            for (int k = 0; k < 2; k++) {
                s += 0.25 * grainDeltaYuv(2 * blk + ivec2(k, j)).yz;
            }
        }
        uvec2 raw = rawUV(blk); // U, V
        float du = dither(blk, 1), dv = dither(blk, 2);
        if (uP010 == 1) {
            vec2 u = word(quant(codeOf(raw.x) + s.x + du));
            vec2 v = word(quant(codeOf(raw.y) + s.y + dv));
            if (uUvSwap == 1) {
                b[0] = v.x; b[1] = v.y; b[2] = u.x; b[3] = u.y;
            } else {
                b[0] = u.x; b[1] = u.y; b[2] = v.x; b[3] = v.y;
            }
        } else {
            if (uUvSwap == 1) {
                b[2 * i]     = byteOf(float(raw.y), s.y, dv); // NV21: V first
                b[2 * i + 1] = byteOf(float(raw.x), s.x, du);
            } else {
                b[2 * i]     = byteOf(float(raw.x), s.x, du); // NV12: U first
                b[2 * i + 1] = byteOf(float(raw.y), s.y, dv);
            }
        }
    }
    fragColor = vec4(b[0], b[1], b[2], b[3]) / 255.0;
}
)";

GLuint compileShader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
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

GLuint linkProgram(const char *fragHead, const char *fragBody = nullptr) {
    GLuint v = compileShader(GL_VERTEX_SHADER, kVertex);
    if (!v) return 0;
    std::string combined = fragHead;
    if (fragBody) combined += fragBody;
    GLuint f = compileShader(GL_FRAGMENT_SHADER, combined.c_str());
    if (!f) {
        glDeleteShader(v);
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

struct GrainPipeline {
    GLuint progGen = 0;
    GLuint progUsmV = 0;
    GLuint progUsmHBlend = 0;
    GLuint progOutLuma = 0;
    GLuint progOutChroma = 0;
    GLuint randTex[3] = {0, 0, 0};
    GLuint vao = 0;
    GLuint fbo = 0;
    bool r16fRenderable = false;
    bool initTried = false;

    bool ready() const {
        return progGen && progUsmV && progUsmHBlend && progOutLuma && progOutChroma &&
               randTex[0] && randTex[1] && randTex[2] && vao && fbo;
    }
};

std::map<EGLContext, GrainPipeline> gPipelines;
std::mutex gPipeMutex;

bool initGrainPipeline(GrainPipeline &p) {
    if (p.initTried) return p.ready();
    p.initTried = true;

    p.progGen = linkProgram(kFragGen);
    p.progUsmV = linkProgram(kFragUsmV);
    p.progUsmHBlend = linkProgram(kFragUsmHBlend);
    p.progOutLuma = linkProgram(kBlendCommon, kFragOutLuma);
    p.progOutChroma = linkProgram(kBlendCommon, kFragOutChroma);
    glGenVertexArrays(1, &p.vao);
    glGenFramebuffers(1, &p.fbo);

    static const int kSizes[3] = {21, 22, 25};
    static const double kSeeds[3] = {17.0, 19.0, 23.0};
    glGenTextures(3, p.randTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    for (int i = 0; i < 3; ++i) {
        std::vector<uint16_t> lut;
        buildRandTable(kSizes[i], kSeeds[i], lut);
        glBindTexture(GL_TEXTURE_2D, p.randTex[i]);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI, kSizes[i] * kSizes[i], kSizes[i]);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kSizes[i] * kSizes[i], kSizes[i],
                        GL_RED_INTEGER, GL_UNSIGNED_SHORT, lut.data());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    // Probe GL_R16F FBO completeness once; fall back to GL_RGBA16F if driver doesn't render R16F.
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

bool drawInto(const GrainPipeline &p, GLuint targetTex, int w, int h) {
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

void setYuvUniforms(GLuint prog, const HncsGrainFrame &f, float scale) {
    glUniform1i(glGetUniformLocation(prog, "uY"), 0);
    glUniform1i(glGetUniformLocation(prog, "uUV"), 1);
    glUniform1i(glGetUniformLocation(prog, "uP010"), f.p010 ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uMsb"), f.msb ? 1 : 0);
    glUniform1i(glGetUniformLocation(prog, "uUvSwap"), f.vFirst ? 1 : 0);
    glUniform1f(glGetUniformLocation(prog, "uScale"), scale);
    glUniform3f(glGetUniformLocation(prog, "uCoding"), f.kr, f.kb, f.fullRange ? 1.0f : 0.0f);
}

}  // namespace

void setHncsGrainState(bool masterMode, bool enabled, int type, int amount, int size, int roughness, int color) {
    bool changed = false;
    changed |= (gState.masterMode.exchange(masterMode) != masterMode);
    changed |= (gState.enabled.exchange(enabled) != enabled);
    changed |= (gState.type.exchange(type) != type);
    changed |= (gState.amount.exchange(amount) != amount);
    changed |= (gState.size.exchange(size) != size);
    changed |= (gState.roughness.exchange(roughness) != roughness);
    changed |= (gState.color.exchange(color) != color);
    if (changed) {
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "state: master=%d on=%d type=%d amount=%d size=%d rough=%d color=%d",
                            int(masterMode), int(enabled), type, amount, size, roughness, color);
    }
}

bool isHncsGrainActive() {
    return gState.masterMode.load() && gState.enabled.load() && gState.amount.load() > 0;
}

bool applyHncsGrainGpu(const HncsGrainFrame &f) {
    if (!isHncsGrainActive()) return false;
    if (!f.y || !f.uv || f.w < 16 || f.h < 16) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "invalid frame %dx%d y=%p uv=%p",
                            f.w, f.h, static_cast<const void *>(f.y), static_cast<const void *>(f.uv));
        return false;
    }
    if (f.w % (f.p010 ? 2 : 4) != 0 || f.h % 2 != 0 || f.yStride % 4 != 0 || f.uvStride % 4 != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unaligned frame p010=%d %dx%d strides=%u/%u",
                            int(f.p010), f.w, f.h, f.yStride, f.uvStride);
        return false;
    }

    GrainConfig cfg{
        gState.enabled.load(),
        gState.type.load(),
        gState.amount.load(),
        gState.size.load(),
        gState.roughness.load(),
        gState.color.load(),
    };
    uint32_t shot = gState.shotCounter.fetch_add(1);
    uint64_t nowNs = static_cast<uint64_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    int frameNb = int((shot * 2654435761u ^ uint32_t(nowNs)) & 0x7fu);
    GrainDerived d = computeGrainParams(cfg, frameNb, f.w, f.h);
    if (!d.active) return false;

    EGLContext ctx = eglGetCurrentContext();
    if (ctx == EGL_NO_CONTEXT) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "no current EGLContext");
        return false;
    }

    std::lock_guard<std::mutex> lock(gPipeMutex);
    GrainPipeline &p = gPipelines[ctx];
    if (!initGrainPipeline(p)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "initGrainPipeline failed");
        return false;
    }

    GLint maxSize = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxSize);
    if (std::max(f.w, f.h) > maxSize) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "frame %dx%d exceeds GL_MAX_TEXTURE_SIZE=%d", f.w, f.h, maxSize);
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

    const int w = f.w, h = f.h;
    const int color = cfg.color;
    const bool doUsm = d.sharpAmount > 0.0f;
    const GLenum grainFmt = (color > 0 || !p.r16fRenderable) ? GL_RGBA16F : GL_R16F;
    const GLenum lumaFmt = p.r16fRenderable ? GL_R16F : GL_RGBA16F;

    GLuint texAcc = makeTex(grainFmt, w, h, 0, 0, 0, nullptr);
    GLuint texDst = (d.order > 1 || doUsm) ? makeTex(grainFmt, w, h, 0, 0, 0, nullptr) : 0;
    GLuint texOct = (d.order > 1) ? makeTex(grainFmt, w, h, 0, 0, 0, nullptr) : 0;
    GLuint texBlurV = doUsm ? makeTex(lumaFmt, w, h, 0, 0, 0, nullptr) : 0;

    GLenum texErr = glGetError();
    bool ok = (texErr == GL_NO_ERROR);
    if (!ok) __android_log_print(ANDROID_LOG_ERROR, kTag, "alloc grain textures failed: glErr=0x%x %dx%d col=%d", texErr, w, h, color);
    const auto start = std::chrono::steady_clock::now();

    // Randomize tile origin per shot cleanly within positive lattice coordinates.
    float baseOfsX = 64.0f + float(fractd(nowNs * 0.0001731) * 192.0);
    float baseOfsY = 64.0f + float(fractd(nowNs * 0.0003197) * 192.0);

    auto runGen = [&](GLuint dstTex, float freqScale, float shift) -> bool {
        glUseProgram(p.progGen);
        bindUnit(0, p.randTex[0]);
        bindUnit(1, p.randTex[1]);
        bindUnit(2, p.randTex[2]);
        glUniform1i(glGetUniformLocation(p.progGen, "uRand0"), 0);
        glUniform1i(glGetUniformLocation(p.progGen, "uRand1"), 1);
        glUniform1i(glGetUniformLocation(p.progGen, "uRand2"), 2);
        glUniform2f(glGetUniformLocation(p.progGen, "uFreq"), freqScale / d.multiplier, freqScale / d.multiplier);
        glUniform2f(glGetUniformLocation(p.progGen, "uOfs"), baseOfsX + d.frameScale + shift, baseOfsY + d.frameScale + shift);
        glUniform1i(glGetUniformLocation(p.progGen, "uColor"), color);
        glUniform1f(glGetUniformLocation(p.progGen, "uColorMix"), d.colorMix);
        return drawInto(p, dstTex, w, h);
    };

    auto runUsmAndBlend = [&](GLuint srcOct, GLuint prevAcc, GLuint outTex, float radius, bool blend, float blendAmt) -> bool {
        float usmW[5] = {};
        if (doUsm) {
            computeUsmWeights(radius, usmW);
            glUseProgram(p.progUsmV);
            bindUnit(0, srcOct);
            glUniform1i(glGetUniformLocation(p.progUsmV, "uOct"), 0);
            glUniform1i(glGetUniformLocation(p.progUsmV, "uColor"), color);
            glUniform1fv(glGetUniformLocation(p.progUsmV, "uWeights"), 5, usmW);
            glUniform2i(glGetUniformLocation(p.progUsmV, "uMaxCoord"), w - 1, h - 1);
            if (!drawInto(p, texBlurV, w, h)) return false;
        }
        glUseProgram(p.progUsmHBlend);
        bindUnit(0, srcOct);
        bindUnit(1, doUsm ? texBlurV : srcOct);
        bindUnit(2, blend ? prevAcc : srcOct);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uOct"), 0);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uBlurV"), 1);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uAcc"), 2);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uColor"), color);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uDoUsm"), doUsm ? 1 : 0);
        glUniform1i(glGetUniformLocation(p.progUsmHBlend, "uHasBlend"), blend ? 1 : 0);
        glUniform1f(glGetUniformLocation(p.progUsmHBlend, "uSharpAmount"), d.sharpAmount);
        glUniform1f(glGetUniformLocation(p.progUsmHBlend, "uBlendAmount"), blendAmt);
        glUniform1fv(glGetUniformLocation(p.progUsmHBlend, "uWeights"), 5, usmW);
        glUniform2i(glGetUniformLocation(p.progUsmHBlend, "uMaxCoord"), w - 1, h - 1);
        return drawInto(p, outTex, w, h);
    };

    // Octave 0
    if (ok) ok = runGen(texAcc, d.res0, 0.0f);
    if (ok && doUsm) {
        ok = runUsmAndBlend(texAcc, 0, texDst, 1.0f, false, 0.0f);
        if (ok) std::swap(texAcc, texDst);
    }

    // Harmonics 1..order-1
    for (int k = 1; ok && k < d.order; ++k) {
        float div = d.harmDiv[k - 1];
        float shift = std::exp2(float(k - 1)) * 2048.0f * d.frameScale;
        ok = runGen(texOct, d.res0 / div, shift);
        if (ok) {
            ok = runUsmAndBlend(texOct, texAcc, texDst, std::max(1.0f, div), true, d.harmAttn[k - 1]);
            if (ok) std::swap(texAcc, texDst);
        }
    }

    if (texOct) { glDeleteTextures(1, &texOct); texOct = 0; }
    if (texDst) { glDeleteTextures(1, &texDst); texDst = 0; }
    if (texBlurV) { glDeleteTextures(1, &texBlurV); texBlurV = 0; }

    GLuint texY = 0, texUV = 0, outY = 0, outUV = 0;
    if (ok) {
        const int lumaPer = f.p010 ? 2 : 4;
        const int chromaPer = f.p010 ? 1 : 2;
        const float scale = f.fullRange ? 1023.0f / 255.0f : 4.0f;

        texY = f.p010
            ? makeTex(GL_R16UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_SHORT, GLint(f.yStride / 2), f.y)
            : makeTex(GL_R8UI, w, h, GL_RED_INTEGER, GL_UNSIGNED_BYTE, GLint(f.yStride), f.y);
        texUV = f.p010
            ? makeTex(GL_RG16UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_SHORT, GLint(f.uvStride / 4), f.uv)
            : makeTex(GL_RG8UI, w / 2, h / 2, GL_RG_INTEGER, GL_UNSIGNED_BYTE, GLint(f.uvStride / 2), f.uv);
        outY = makeTex(GL_RGBA8, w / lumaPer, h, 0, 0, 0, nullptr);
        outUV = makeTex(GL_RGBA8, w / 2 / chromaPer, h / 2, 0, 0, 0, nullptr);
        ok = (glGetError() == GL_NO_ERROR);

        auto writePlane = [&](GLuint prog, GLuint target, int tw, int th, void *dst, size_t stride) -> bool {
            glUseProgram(prog);
            bindUnit(0, texY);
            bindUnit(1, texUV);
            bindUnit(2, texAcc);
            setYuvUniforms(prog, f, scale);
            glUniform1i(glGetUniformLocation(prog, "uGrain"), 2);
            glUniform1i(glGetUniformLocation(prog, "uHlg"), f.hlg ? 1 : 0);
            glUniform1i(glGetUniformLocation(prog, "uColor"), color);
            glUniform1i(glGetUniformLocation(prog, "uUseLumChannel"), d.useLumChannel);
            glUniform1f(glGetUniformLocation(prog, "uInvGamma"), d.invGamma);
            glUniform1f(glGetUniformLocation(prog, "uBrightScale"), d.brightScale);
            glUniform3f(glGetUniformLocation(prog, "uAlphaBlend"), d.alphaBlend[0], d.alphaBlend[1], d.alphaBlend[2]);
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
    GLuint del[] = {texAcc, texY, texUV, outY, outUV};
    glDeleteTextures(5, del);

    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "capture %s %dx%d %s type=%d amt=%d size=%d rough=%d col=%d: %s in %lld ms",
                        f.p010 ? "P010" : "NV21", w, h, f.hlg ? "HLG" : "sRGB",
                        cfg.type, cfg.amount, cfg.size, cfg.roughness, cfg.color,
                        ok ? "done" : "failed",
                        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - start).count());
    return ok;
}

}  // namespace hncs_grain
