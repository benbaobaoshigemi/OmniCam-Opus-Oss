#pragma once

// Exact Hasselblad Phocus 4.1.1 CFilmGrainFilter mathematics (reverse-engineered from PhocusApi64.dll).
// Kept pure C++ (no Android/GLES headers) so host offline unit tests compile and verify every formula directly.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace hncs_grain {

struct GrainConfig {
    bool enabled = false;
    int type = 1;       // 0 = Fine (Tabular/Gamma1.5), 1 = Standard (ISO/Gamma4.0), 2 = Soft (Artistic)
    int amount = 40;    // 0..100
    int size = 40;      // 0..100
    int roughness = 15; // 0..100
    int color = 0;      // 0..100
};

struct GrainDerived {
    bool active = false;
    bool sqrtMode = true;
    int type = 1;
    int order = 4;
    int useLumChannel = 0; // 0 = RGB, 2 = YCoCg
    float invGamma = 0.25f;
    float res0 = 1.0f;
    float multiplier = 2.5f;
    float frameScale = 1.0f;
    float brightScale = 1.0f;
    float colorMix = 0.0f;
    float sharpAmount = 150.0f; // Roughness * 10
    float harmAttn[3] = {0.4f, 0.2f, 0.1f};
    float harmDiv[3] = {2.0f, 3.0f, 4.0f};
    float alphaBlend[3] = {0.0f, 0.0f, 0.0f};
};

inline double fractd(double x) { return x - std::floor(x); }

// FgframeScale @ 0x753410: deterministic jitter multiplier from frame number, defaults to [1.0, 9.0).
inline float frameScaleOf(int frameNb) {
    if (frameNb < 0) return 1.0f;
    double angle = double(frameNb & 0x7f) * 128.0;
    return float(1.0 + 8.0 * fractd(128.0 * std::sin(angle)));
}

// Compute derived Phocus grain parameters at native 1:1 pixel scale (Phocus previewScale == 1).
inline GrainDerived computeGrainParams(const GrainConfig &rawCfg, int frameNb, int width, int height) {
    (void)width;
    (void)height;
    GrainDerived d{};
    if (!rawCfg.enabled || rawCfg.amount <= 0) return d;

    const int type = std::clamp(rawCfg.type, 0, 2);
    const int amount = std::clamp(rawCfg.amount, 0, 100);
    const int size = std::clamp(rawCfg.size, 0, 100);
    const int roughness = std::clamp(rawCfg.roughness, 0, 100);
    const int color = std::clamp(rawCfg.color, 0, 100);

    d.active = true;
    d.type = type;
    d.multiplier = 2.5f;
    d.frameScale = frameScaleOf(frameNb);
    d.useLumChannel = (color == 0) ? 0 : 2;
    d.colorMix = 0.05f * float(std::min(color, 20));
    d.sharpAmount = float(roughness) * 10.0f;

    bool harmAttVar = true;
    if (type == 0) {
        d.invGamma = 1.0f / 1.5f;
        d.sqrtMode = false;
        d.order = (size > 1) ? 4 : 1;
        harmAttVar = true;
        d.harmDiv[0] = 2.0f;
        d.harmDiv[1] = 4.0f;
        d.harmDiv[2] = 8.0f;
        d.res0 = 1.0f;
    } else if (type == 1) {
        d.invGamma = 0.25f;
        d.sqrtMode = true;
        d.order = (size > 1) ? 4 : 1;
        harmAttVar = true;
        d.harmDiv[0] = 2.0f;
        d.harmDiv[1] = 3.0f;
        d.harmDiv[2] = 4.0f;
        d.res0 = 1.0f;
    } else {
        d.invGamma = 0.25f;
        d.sqrtMode = true;
        d.order = 3;
        harmAttVar = false;
        d.harmDiv[0] = 2.0f;
        d.harmDiv[1] = 4.0f;
        d.harmDiv[2] = 8.0f;
        d.harmAttn[0] = 0.4f;
        d.harmAttn[1] = 0.2f;
        d.harmAttn[2] = 0.1f;
        d.res0 = 30.0f / float(size + 29);
    }

    // Variable harmonic attenuation (HarmAttVar @ 0x753f6b..0x7541c0)
    if (harmAttVar) {
        if (size <= 1) {
            d.harmAttn[0] = 0.0f;
            d.harmAttn[1] = 0.0f;
            d.harmAttn[2] = 0.0f;
        } else {
            double a2 = double(size) / 150.0;
            double a1 = a2 * 1.6;
            double a0 = a1 * 1.6;
            if (a1 > 1.0) a1 = 2.0 - a1;
            const double a0Orig = a0;
            if (a0 > 1.0) a0 = 2.0 - a0;
            a0 = std::clamp(a0, 0.2, 0.9);
            a1 = std::clamp(a1, 0.1, 0.9);
            if (a2 > 0.9) {
                a2 = 0.9;
                if (a0Orig < 0.3) a0 = 0.3;
            }
            a2 = std::max(a2, 0.1);
            d.harmAttn[0] = float(a0);
            d.harmAttn[1] = float(a1);
            d.harmAttn[2] = float(a2);
        }
    }

    // CalcAlphaBlend @ 0x7534e0..0x7538d0
    double baseAlpha = d.sqrtMode ? (double(amount) * 4.0) : (double(amount) / 10.0);
    double a0 = baseAlpha;
    double a1 = baseAlpha;
    double a2 = baseAlpha;
    if (color != 0 && d.useLumChannel == 2) {
        double colorScaling = double(color) / 40.0;
        a1 *= colorScaling;
        a2 *= colorScaling;
    }
    if (color >= 1 && color <= 10) {
        double f = 1.0 + 0.3 * double(10 - color) / 10.0;
        if (f > 1.0) {
            f = std::max(1.0, f - double(roughness) / 100.0);
        }
        a0 *= f;
    }
    d.alphaBlend[0] = float(4.0 * a0);
    d.alphaBlend[1] = float(4.0 * a1);
    d.alphaBlend[2] = float(4.0 * a2);
    d.brightScale = float(std::max(1.0, 1.5 * double(roughness) / 100.0));

    return d;
}

// 9-tap separable Gaussian weights for grain USM (Phocus 0x751d30).
// outW[d] holds symmetric weight for offset +-d (d = 0..4).
inline void computeUsmWeights(float radius, float outW[5]) {
    for (int i = 0; i < 5; ++i) outW[i] = 0.0f;
    if (radius <= 0.1f) {
        outW[0] = 1.0f / 3.0f;
        return;
    }
    const double r = double(radius);
    double kept[9] = {};
    int keptDx[9] = {};
    int count = 0;
    double sum = 0.0;
    for (int dx = -4; dx <= 4; ++dx) {
        double v = std::exp(-(double(dx) * double(dx)) / (r * r));
        if (v > 0.1) {
            kept[count] = v;
            keptDx[count] = dx;
            sum += v;
            ++count;
        }
    }
    if (count < 3) {
        count = 3;
        keptDx[0] = -1; kept[0] = std::exp(-1.0 / (r * r));
        keptDx[1] =  0; kept[1] = 1.0;
        keptDx[2] =  1; kept[2] = kept[0];
        sum = kept[0] + kept[1] + kept[2];
    }
    for (int i = 0; i < count; ++i) {
        int adx = std::abs(keptDx[i]);
        if (adx < 5) outW[adx] = float(kept[i] / sum);
    }
}

// USM tone-dependent gain curve (CalcAmount from UnsharpMaskMetal @ 0xd04420, darkLimit=0, hdrScale=1).
inline float usmCalcAmount(float sharpAmount, float v01) {
    if (sharpAmount <= 0.0f) return 0.0f;
    float clampAmt = std::clamp(1638400.0f / (sharpAmount + 100.0f), 1000.0f, 10000.0f);
    float t0 = clampAmt * 0.00006103515625f;
    float q = (1.0f - t0 - 3.0f) * (v01 * v01) + 3.0f * v01 + t0;
    float amt = (0.0f < q && q < 1.0f) ? (q * (sharpAmount + 100.0f) - 100.0f) : sharpAmount;
    return std::clamp(amt, 0.0f, 1000.0f) * 0.01f;
}

// Phocus prime gradient LUTs: 21^3 (seed 17), 22^3 (seed 19), 25^3 (seed 23), packed as (N*N) x N.
inline void buildRandTable(int N, double seed, std::vector<uint16_t> &out) {
    const int width = N * N;
    out.resize(size_t(width) * size_t(N));
    for (int z = 0; z < N; ++z) {
        for (int y = 0; y < N; ++y) {
            for (int x = 0; x < N; ++x) {
                int k = (z * N + y) * N + x;
                double val = fractd(2097152.0 * std::sin(17.0 * (double(k) + seed))) - 0.5;
                double u = std::clamp(32767.0 + 65535.0 * val, 0.0, 65535.0);
                out[size_t(y) * width + size_t(z * N + x)] = static_cast<uint16_t>(u);
            }
        }
    }
}

// ---- ITU-R BT.2100 HLG & Extended sRGB transfer helpers -----------------------------------------

constexpr float kHlgA = 0.17883277f;
constexpr float kHlgB = 0.28466892f;
constexpr float kHlgC = 0.55991073f;
// HLG inverse OETF at 75% diffuse reference white: (exp((0.75 - c)/a) + b) / 12 = 0.26496256.
constexpr float kHlgWhite = 0.26496256f;

inline float hlgInvOetf(float v) {
    v = std::clamp(v, 0.0f, 1.0f);
    if (v <= 0.5f) return (v * v) / 3.0f;
    return (std::exp((v - kHlgC) / kHlgA) + kHlgB) / 12.0f;
}

inline float hlgOetf(float e) {
    e = std::clamp(e, 0.0f, 1.0f);
    if (e <= (1.0f / 12.0f)) return std::sqrt(3.0f * e);
    return kHlgA * std::log(12.0f * e - kHlgB) + kHlgC;
}

inline float extSrgbEncode(float e) {
    if (e <= 0.0031308f) return std::max(0.0f, 12.92f * e);
    return 1.055f * std::pow(e, 1.0f / 2.4f) - 0.055f;
}

inline float extSrgbDecode(float v) {
    if (v <= 0.04045f) return std::max(0.0f, v / 12.92f);
    return std::pow((v + 0.055f) / 1.055f, 2.4f);
}

}  // namespace hncs_grain
