// Film grain and halation in the master capture's ProProc pass (libOPAlgoCamHybridRaw). The stock kernel proproc3F_rgb_p010_u16_c1_buf
// turns the post-CCM linear light into the LMT's input code (gamma table), tile by tile; the LMT is the whole film. The film's two
// effects go where they belong in front of it:
//   halation  on the linear light, before the encode: the light the film base returns is added to the frame's light, and the LMT develops it
//   grain     on the code, after the encode: the code change that develops as the film's grain (film_prelmt.h)
// The returned light is made once per frame, before its first tile, by three more kernels: the sources' light on a coarse grid (the stock
// prefix, so it is the frame's own light), its Gaussian scatter, and their mixture.
//
// The engine builds its kernels at init and runKernel's source argument is only their tag, so the film's variants are built here: the
// library's own proproc.cl text with those two steps added, run with the stock arguments and range in place of the stock kernel. Its `pars`
// buffer is replaced by a copy that carries the plan (and the grid) after the stock floats.
//
// Quick images and thumbnails run other kernels (proproc_lite_*) and the other HybridRaw library: they stay as they are.
#include "film_proproc.h"

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "ccm_dump_source.h"
#include "cl_api.h"
#include "film_prelmt.h"
#include "native_hooks.h"

namespace {

constexpr char TAG[] = "FilmProProc";
constexpr char kStockName[] = "proproc3F_rgb_p010_u16_c1_buf";
constexpr char kFilmName[] = "proproc3F_film_rgb_p010_u16_c1_buf";
constexpr char kLiteName[] = "proproc_lite_rgb_p010_u16_c1_buf";
constexpr char kLiteFilmName[] = "proproc_lite_film_rgb_p010_u16_c1_buf";

// pars layout, in floats: [0, 15) the stock ones, then
constexpr int kVigEnable = 15;
constexpr int kGrain = 16, kSeed = 17, kAxis = 18, kLattice = 19, kPopulations = 20;
constexpr int kHalo = 21, kGridW = 22, kGridH = 23, kCell = 24, kSourceGain = 25, kFrameW = 26, kFrameH = 27;
constexpr int kMatrix = 28, kWeights = 37, kSigma = 46, kSources = 49, kPxPerMM = 52, kGlobalY = 53, kGlobalBright = 54;
constexpr int kWhiteLo = 55, kDualLo = 56, kDualSpan = 57, kDualWeight = 58;
constexpr int kPromLo = 59, kPromHi = 60, kRampDamp = 61, kWinFloor = 62, kDomeBase = 63;
constexpr int kVigKx = 64, kVigBx = 65, kVigKy = 66, kVigBy = 67, kVigGain = 68;
constexpr int kPopulationBase = 69, kPopulationStride = 64, kTapStride = 2 * kFilmMaxRadius + 1;
constexpr int kNodes = kFilmLattice * kFilmLattice * kFilmLattice;
constexpr int kGainBase = kPopulationBase + 2 * kPopulationStride;
constexpr int kExposureBase = kGainBase + kNodes * 9;
constexpr int kInverseBase = kExposureBase + kNodes * 3;
constexpr int kGridBase = kInverseBase + kNodes * 9;
constexpr int kIcdfSize = 1024;
static_assert(kTapStride == 13, "the kernel source reads the taps at 11 + 13 c");
// Clipped samples carry no record of how far above sensor saturation a light source stood. Each "boost" preset (moderate, gentle, bold,
// in the setting's order) is a composite profile coupling core admission (white-core threshold and two-channel warm/coloured emitter
// admission), contrast response against the normalized unclipped surround, highlight-shoulder ramp damping (relieved by convex optical
// glare cusps), macro bright-sheet tolerance, backlit-opening floor, glint base weight, and peak source multiple.
struct BoostProfile {
    float peak;
    float whiteLo, dualLo, dualSpan, dualWeight;
    float promLo, promHi, rampDamp;
    float macroDiv, sheetLo, sheetHi;
    float winFloor, domeBase, skirtGain;
};
constexpr BoostProfile kBoostProfiles[3] = {
    // moderate
    {20.0f, 0.90f, 0.34f, 0.32f, 0.86f, 0.90f, 2.90f, 0.78f, 5.8f, 0.36f, 0.50f, 0.24f, 0.86f, 0.35f},
    // gentle
    {9.0f, 0.92f, 0.40f, 0.36f, 0.72f, 1.30f, 3.80f, 0.88f, 6.4f, 0.34f, 0.48f, 0.12f, 0.72f, 0.25f},
    // bold
    {42.0f, 0.88f, 0.26f, 0.28f, 0.98f, 0.55f, 2.10f, 0.64f, 5.3f, 0.38f, 0.52f, 0.38f, 1.00f, 0.50f},
};
constexpr int kPlanes = 9;

std::string layoutDefines() {
    char text[700];
    std::snprintf(text, sizeof(text),
                  "#define FILM_N %d\n#define FILM_POP %d\n#define FILM_POP_STRIDE %d\n#define FILM_GAIN %d\n#define FILM_EXPOSURE %d\n"
                  "#define FILM_INVERSE %d\n#define FILM_EXPONENT %.1ff\n#define FILM_EXP10_LOG2 3.32192809f\n"
                  "#define FILM_VIG_EN %d\n#define FILM_VIG_KX %d\n#define FILM_VIG_BX %d\n#define FILM_VIG_KY %d\n#define FILM_VIG_BY %d\n#define FILM_VIG_GAIN %d\n",
                  kFilmLattice, kPopulationBase, kPopulationStride, kGainBase, kExposureBase, kInverseBase, kFilmLinearExponent,
                  kVigEnable, kVigKx, kVigBx, kVigKy, kVigBy, kVigGain);
    return text;
}

const char kFilmFunctions[] = R"film_cl(
inline float3 film_vignette(float3 lin, int x, int y, __global const float* P) {
    if (P[FILM_VIG_EN] <= 0.5f) return lin;
    float dx = mad((float)x, P[FILM_VIG_KX], P[FILM_VIG_BX]);
    float dy = mad((float)y, P[FILM_VIG_KY], P[FILM_VIG_BY]);
    float inv = native_recip(1.f + dx * dx + dy * dy);
    float gain = P[FILM_VIG_GAIN];
    float3 out = lin * (inv * inv * gain);
    if (gain > 1.001f) {
        float3 t = fmax(out - 0.85f, 0.f);
        float3 soft = 0.85f + 0.15f * (t / (0.15f + t));
        out = select(out, soft, isgreater(out, (float3)(0.85f)));
    }
    return out;
}
inline uint film_pcg(uint v) {
    uint s = v * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28) + 4u)) ^ s) * 277803737u;
    return (w >> 22) ^ w;
}
inline uint film_hash(int x, int y, uint seed, uint layer) {
    return film_pcg((uint)x ^ film_pcg((uint)y ^ film_pcg(seed ^ (layer * 0x9E3779B9u))));
}
inline float3 film_mat3_mul(__global const float* m, float3 v) {
    return (float3)(m[0] * v.x + m[1] * v.y + m[2] * v.z,
                    m[3] * v.x + m[4] * v.y + m[5] * v.z,
                    m[6] * v.x + m[7] * v.y + m[8] * v.z);
}

// ---- grain: reads pre-convolved half4 noise field and moves the code by M(code) n
inline float3 film_grain(float3 code, int x, int y, __global const float* P, __global const half* noiseMem, int noiseY0) {
    if (P[16] < 0.5f) return code;
    int W = (int)P[26];
    float3 n = vload_half4((size_t)(y - noiseY0) * W + x, noiseMem).xyz;
    int N = (int)P[19];
    float3 u = clamp(code * native_recip(P[18]), 0.f, 1.f) * (float)(N - 1);
    int3 i0 = min(convert_int3(floor(u)), (int3)(N - 2));
    float3 f = u - convert_float3(i0);
    int sG = N, sB = N * N, base = (i0.z * N + i0.y) * N + i0.x;
    __global const float* G = P + FILM_GAIN;
    float3 r00 = mix(film_mat3_mul(G + base * 9, n), film_mat3_mul(G + (base + 1) * 9, n), f.x);
    float3 r10 = mix(film_mat3_mul(G + (base + sG) * 9, n), film_mat3_mul(G + (base + sG + 1) * 9, n), f.x);
    float3 r01 = mix(film_mat3_mul(G + (base + sB) * 9, n), film_mat3_mul(G + (base + sB + 1) * 9, n), f.x);
    float3 r11 = mix(film_mat3_mul(G + (base + sB + sG) * 9, n), film_mat3_mul(G + (base + sB + sG + 1) * 9, n), f.x);
    float3 delta = mix(mix(r00, r10, f.y), mix(r01, r11, f.y), f.z);
    return clamp(code + clamp(delta, -0.12f, 0.12f), 0.f, 1.f);
}

// ---- halation: linear light -> the film's layer exposures, and the way back for a change of them
inline float3 film_exposure(float3 lin, __global const float* P, int* out_base, float3* out_f) {
    float3 c = clamp(lin, 0.f, 1.f);
    float3 u = native_powr(max(c, 1.0e-7f), (float3)(1.f / FILM_EXPONENT)) * (float)(FILM_N - 1);
    u = select(u, (float3)(0.f), isequal(c, (float3)(0.f)));
    int3 i0 = min(convert_int3(floor(u)), (int3)(FILM_N - 2));
    float3 f = u - convert_float3(i0);
    int sG = FILM_N, sB = FILM_N * FILM_N;
    int base = (i0.z * FILM_N + i0.y) * FILM_N + i0.x;
    if (out_base) { *out_base = base; *out_f = f; }
    __global const float* T = P + FILM_EXPOSURE;
    float3 r00 = mix(vload3(base, T), vload3(base + 1, T), f.x);
    float3 r10 = mix(vload3(base + sG, T), vload3(base + sG + 1, T), f.x);
    float3 r01 = mix(vload3(base + sB, T), vload3(base + sB + 1, T), f.x);
    float3 r11 = mix(vload3(base + sB + sG, T), vload3(base + sB + sG + 1, T), f.x);
    float3 e = mix(mix(r00, r10, f.y), mix(r01, r11, f.y), f.z);
    return native_exp2(e * FILM_EXP10_LOG2);
}

inline float3 film_sort3(float3 v) {
    float a = min(v.x, v.y), b = max(v.x, v.y);
    return (float3)(min(a, v.z), min(max(a, v.z), b), max(b, v.z));
}
inline float film_core_weight(float3 lin, __global const float* P) {
    float3 s = film_sort3(clamp(lin, 0.f, 1.f));
    float wWhite = smoothstep(P[55], 0.995f, s.x);
    float cGeom = sqrt(max(s.x * s.y, 0.f));
    float wDual = smoothstep(P[56], P[56] + P[57], cGeom) * smoothstep(0.92f, 0.996f, s.z);
    return max(wWhite, P[58] * wDual);
}

inline float3 film_halation(float3 lin, int x, int y, __global const float* P, __global const half* G) {
    if (P[21] < 0.5f) return lin;
    int gw = (int)P[22], gh = (int)P[23];
    float cell = P[24];
    float fx = clamp(((float)x + 0.5f) * native_recip(cell) - 0.5f, 0.f, (float)(gw - 1));
    float fy = clamp(((float)y + 0.5f) * native_recip(cell) - 0.5f, 0.f, (float)(gh - 1));
    int x0 = min((int)fx, max(gw - 2, 0)), y0 = min((int)fy, max(gh - 2, 0));
    int x1 = min(x0 + 1, gw - 1), y1 = min(y0 + 1, gh - 1);
    float ax = fx - (float)x0, ay = fy - (float)y0;
    int plane4 = gw * gh;
    int i00 = y0 * gw + x0, i01 = y0 * gw + x1, i10 = y1 * gw + x0, i11 = y1 * gw + x1;
    float w00 = (1.f - ax) * (1.f - ay), w01 = ax * (1.f - ay), w10 = (1.f - ax) * ay, w11 = ax * ay;
    __global const half* S = G + 3 * plane4 * 4;
    float3 s = vload_half4(i00, S).xyz * w00 + vload_half4(i01, S).xyz * w01 +
               vload_half4(i10, S).xyz * w10 + vload_half4(i11, S).xyz * w11;
    int base = 0;
    float3 f = (float3)(0.f);
    float3 eBase = film_exposure(lin, P, &base, &f);
    float wClip = film_core_weight(lin, P);
    if (wClip > 0.f) {
        __global const half* E = G + 4 * plane4 * 4;
        __global const half* W = G + 5 * plane4 * 4;
        float3 eExtra = vload_half4(i00, E).xyz * w00 + vload_half4(i01, E).xyz * w01 +
                        vload_half4(i10, E).xyz * w10 + vload_half4(i11, E).xyz * w11;
        float wGrid = vload_half4(i00, W).x * w00 + vload_half4(i01, W).x * w01 +
                      vload_half4(i10, W).x * w10 + vload_half4(i11, W).x * w11;
        eBase += min(eExtra * clamp(wClip * native_recip(max(wGrid, 1.0e-4f)), 0.f, 1.f), max(s - eBase, (float3)(0.f)));
    }
    float3 ret = (float3)(P[49] > 0.5f ? s.x - eBase.x : 0.f, P[50] > 0.5f ? s.y - eBase.y : 0.f, P[51] > 0.5f ? s.z - eBase.z : 0.f);
    __global const float* M = P + 28;
    float3 dE = film_mat3_mul(M, ret);
    int sG = FILM_N, sB = FILM_N * FILM_N;
    __global const float* I = P + FILM_INVERSE;
    float3 r00 = mix(film_mat3_mul(I + base * 9, dE), film_mat3_mul(I + (base + 1) * 9, dE), f.x);
    float3 r10 = mix(film_mat3_mul(I + (base + sG) * 9, dE), film_mat3_mul(I + (base + sG + 1) * 9, dE), f.x);
    float3 r01 = mix(film_mat3_mul(I + (base + sB) * 9, dE), film_mat3_mul(I + (base + sB + 1) * 9, dE), f.x);
    float3 r11 = mix(film_mat3_mul(I + (base + sB + sG) * 9, dE), film_mat3_mul(I + (base + sB + sG + 1) * 9, dE), f.x);
    float3 dlin = mix(mix(r00, r10, f.y), mix(r01, r11, f.y), f.z);
    float3 posRoom = max((float3)(1.f) - lin, (float3)(0.f));
    float3 posDlin = max(dlin, (float3)(0.f));
    float posReq = max(max(posDlin.x, posDlin.y), posDlin.z);
    float3 achDlin = min(posDlin, posRoom);
    float posAch = max(max(achDlin.x, achDlin.y), achDlin.z);
    float negScale = posReq > 1.0e-6f ? posAch * native_recip(posReq) : 1.f;
    dlin = max(dlin, 0.f) + min(dlin, 0.f) * negScale;
    return clamp(lin + dlin, 0.f, 1.f);
}

)film_cl";

// Pre-convolved tileable grain noise strip and hierarchical half4 halation grid kernels.
const char kFilmKernels[] = R"film_cl(
__kernel void film_noise_tile(__global half* outNoise, int W, int H, int stripY,
                              __global const float* P, __constant half* icdfs, uint seed, int pops) {
    int lx = get_local_id(0), ly = get_local_id(1);
    int tid = ly * 16 + lx;
    int gx0 = get_group_id(0) * 16, gy0 = get_group_id(1) * 16;
    int gx = gx0 + lx, gy = gy0 + ly + stripY;
    __local half ltab[1024];
    __local float sx[28 * 29], sy[28 * 29], sz[28 * 29];
    __local float hx[28 * 17], hy[28 * 17], hz[28 * 17];
    float3 totalN = (float3)(0.f);
    for (int p = 0; p < pops; p++) {
        __constant half* srcTab = icdfs + p * 1024;
        for (int i = tid; i < 1024; i += 256) ltab[i] = srcTab[i];
        __global const float* q = P + FILM_POP + p * FILM_POP_STRIDE;
        float own = q[3], shared = q[4];
        uint layerBase = (uint)q[5];
        int R = (int)q[7];
        int tw = 16 + 2 * R;
        barrier(CLK_LOCAL_MEM_FENCE);
        for (int oy = 0; oy < 2; oy++) {
            int ty = ly + oy * 16;
            if (ty >= tw) continue;
            int py = gy0 + stripY + ty - R;
            for (int ox = 0; ox < 2; ox++) {
                int tx = lx + ox * 16;
                if (tx >= tw) continue;
                int px = gx0 + tx - R;
                uint h = film_hash(px, py, seed, layerBase);
                float r0 = (float)ltab[h >> 22]; h = film_pcg(h);
                float r1 = (float)ltab[h >> 22]; h = film_pcg(h);
                float r2 = (float)ltab[h >> 22];
                float sh = 0.f;
                if (shared != 0.f) { h = film_pcg(h); sh = (float)ltab[h >> 22] * shared; }
                int slot = ty * 29 + tx;
                sx[slot] = own * r0 + sh;
                sy[slot] = own * r1 + sh;
                sz[slot] = own * r2 + sh;
            }
        }
        barrier(CLK_LOCAL_MEM_FENCE);
        for (int row = ly; row < tw; row += 16) {
            int base = row * 29 + lx;
            float ax = 0.f, ay = 0.f, az = 0.f;
            for (int d = 0; d <= 2 * R; d++) {
                int c = base + d;
                ax = mad(sx[c], q[11 + d], ax);
                ay = mad(sy[c], q[24 + d], ay);
                az = mad(sz[c], q[37 + d], az);
            }
            int hslot = row * 17 + lx;
            hx[hslot] = ax; hy[hslot] = ay; hz[hslot] = az;
        }
        barrier(CLK_LOCAL_MEM_FENCE);
        float bx = 0.f, by = 0.f, bz = 0.f;
        for (int d = 0; d <= 2 * R; d++) {
            int r = (ly + d) * 17 + lx;
            bx = mad(hx[r], q[11 + d], bx);
            by = mad(hy[r], q[24 + d], by);
            bz = mad(hz[r], q[37 + d], bz);
        }
        totalN += (float3)(bx * q[8], by * q[9], bz * q[10]);
        barrier(CLK_LOCAL_MEM_FENCE);
    }
    if (gx < W && gy < H) {
        vstore_half4((float4)(totalN, 0.f), (size_t)(gy - stripY) * W + gx, outNoise);
    }
}

__kernel void film_source(__global half* src, int srcW, int srcH, int srcStride, __global float* pars,
    __local float* cache, uchar addNoiseEnable, __global float* noiseMap, int noiseW, int noiseH, float noiseWeight,
    float noiseThres, float redGain, float blueGain, float awbMaxValue, float ispGain, int luxIndex, __global float* ccmTable,
    __global ushort* gammaTable, float gammaMaxScale, float maxValue, float randomNum, int dstStride, int dstScanLine,
    float outScaleW, float outScaleH, int offsetX, int offsetY, __global ushort* dst, int dstOffsetY, int dstHeight,
    float whiteLevel, int lsb2msb, float minGateEdge0, float minGateEdge1, float rgMaxGateEdge0, float rgMaxGateEdge1,
    float dipSpreadEdge0, float dipSpreadEdge1, float dipBrightEdge0, float dipBrightEdge1,
    float dipNotAllMidEdge0, float dipNotAllMidEdge1, __global half* G)
{
    int cx = get_global_id(0), cy = get_global_id(1);
    int gw = (int)pars[22], gh = (int)pars[23], cell = (int)pars[24];
    if (cx >= gw || cy >= gh) return;
    int W = (int)pars[26], H = (int)pars[27];
    int3 srcSize = (int3)(srcW, srcH, srcStride);
    int2 dstSize = (int2)(W, H);
    int2 noiseSize = (int2)(noiseW, noiseH);
    float3 rgb2yuvParsVec0 = vload3(0, pars);
    float3 rgb2yuvParsVec1 = vload3(1, pars);
    float3 rgb2yuvParsVec2 = vload3(2, pars);
    float3 awbGain = (float3)(redGain, 1.f, blueGain);
    float3 ccmVec0 = vload3(0, ccmTable);
    float3 ccmVec1 = vload3(1, ccmTable);
    float3 ccmVec2 = vload3(2, ccmTable);
    float3 shotVec = vload3(0, pars + 9);
    float3 readVec = vload3(0, pars + 9 + 3);
    float3 sumE = (float3)(0.f), sumClipE = (float3)(0.f), sumRing = (float3)(0.f);
    float sumW = 0.f, sumY = 0.f, sumCmin = 0.f, sumSh = 0.f;
    int count = 0;
    for (int dy = 0; dy < cell; dy += 2) {
        for (int dx = 0; dx < cell; dx += 2) {
            int x = cx * cell + dx, y = cy * cell + dy;
            if (x >= W || y >= H) continue;
            float3 lin = capture_linear3f_pixel(src, x, y, srcSize, rgb2yuvParsVec0, rgb2yuvParsVec1, rgb2yuvParsVec2,
                (uchar)0, noiseMap, noiseSize, shotVec, readVec, noiseWeight, noiseThres,
                awbGain, awbMaxValue, ispGain, luxIndex, ccmVec0, ccmVec1, ccmVec2,
                gammaTable, gammaMaxScale, maxValue, randomNum, dstSize, outScaleW,
                outScaleH, offsetX, offsetY, whiteLevel, minGateEdge0, minGateEdge1, rgMaxGateEdge0, rgMaxGateEdge1,
                dipSpreadEdge0, dipSpreadEdge1, dipBrightEdge0, dipBrightEdge1, dipNotAllMidEdge0, dipNotAllMidEdge1);
            lin = film_vignette(lin, x, y, pars);
            float3 c = clamp(lin, 0.f, 1.f);
            float3 s = film_sort3(c);
            float wClip = film_core_weight(c, pars);
            float yLuma = 0.2289746f * c.x + 0.6917385f * c.y + 0.0792869f * c.z;
            float wSh = smoothstep(0.40f, 0.62f, yLuma) * (1.f - wClip);
            float3 e = film_exposure(c, pars, 0, 0);
            float wRing = smoothstep(0.12f, 0.35f, s.z) * (1.f - smoothstep(0.82f, 0.97f, s.x));
            sumE += e;
            sumClipE += wClip * e;
            sumRing += wRing * e;
            sumW += wClip;
            sumY += yLuma;
            sumCmin += wClip * s.x;
            sumSh += wSh;
            count++;
        }
    }
    float inv = count > 0 ? 1.f / (float)count : 0.f;
    float wG = sumW * inv, yG = sumY * inv, shG = sumSh * inv;
    float cminG = sumW > 1.0e-4f ? sumCmin / sumW : 0.f;
    float uUnclip = 1.f - wG;
    int n = gw * gh, idx = cy * gw + cx;
    vstore_half4((float4)(sumE * inv, 0.f), idx, G);
    vstore_half4((float4)(sumRing * inv, 0.f), idx, G + 4 * n * 4);
    vstore_half4((float4)(wG, cminG, clamp(wG + shG, 0.f, 1.f), 0.f), idx, G + 5 * n * 4);
    vstore_half4((float4)(yG * uUnclip, uUnclip, shG, 0.f), idx, G + 6 * n * 4);
    vstore_half4((float4)(sumClipE * inv, 0.f), idx, G + 8 * n * 4);
}

__kernel void film_reduce(__global float* pars, __global const half* G) {
    __local float3 acc[64];
    int tid = (int)get_local_id(0);
    int n = (int)pars[22] * (int)pars[23];
    __global const half* p5 = G + 5 * n * 4;
    __global const half* p6 = G + 6 * n * 4;
    float3 sum = (float3)(0.f);
    for (int i = tid; i < n; i += 64) {
        float4 v6 = vload_half4(i, p6);
        sum += (float3)(v6.x, v6.y, vload_half4(i, p5).z);
    }
    acc[tid] = sum;
    for (int stride = 32; stride > 0; stride >>= 1) {
        barrier(CLK_LOCAL_MEM_FENCE);
        if (tid < stride) acc[tid] += acc[tid + stride];
    }
    if (tid == 0 && n > 0) {
        pars[53] = acc[0].x / max(acc[0].y, 1.0e-3f);
        pars[54] = acc[0].z / (float)n;
    }
}

__kernel void film_stage(__global const float* pars, __global half* G, int stage, float p0, float p1) {
    int gw = (int)pars[22], gh = (int)pars[23];
    int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= gw || cy >= gh) return;
    int n = gw * gh, idx = cy * gw + cx;
    if (stage == 0) {
        float uUnclip = 1.f - vload_half4(idx, G + 5 * n * 4).x;
        float skirtIn = vload_half4(idx, G + 2 * n * 4).x;
        float4 out7 = vload_half4(idx, G + 7 * n * 4);
        float shBlur = vload_half4(idx, G + 6 * n * 4).z;
        float4 env3 = vload_half4(idx, G + 3 * n * 4);
        float globalY = pars[53];
        float yEnv = (env3.x + 0.06f * globalY) / (env3.y + 0.06f);
        float nearClip = max(1.f - out7.y, 0.f);
        float skirtExcess = max(skirtIn - 1.10f * out7.x, 0.f) * uUnclip;
        vstore_half4((float4)(skirtExcess, 0.f, 0.f, 0.f), idx, G + 2 * n * 4);
        vstore_half4((float4)(smoothstep(0.11f, 0.25f, shBlur), 0.f, 0.f, 0.f), idx, G + 6 * n * 4);
        vstore_half4((float4)(yEnv, nearClip, 0.f, 0.f), idx, G + 7 * n * 4);
    } else if (stage == 1) {
        float4 p7 = vload_half4(idx, G + 7 * n * 4);
        float skirtSupport = vload_half4(idx, G + 2 * n * 4).x;
        float shSpread = vload_half4(idx, G + 6 * n * 4).x;
        float wDome = vload_half4(idx, G + 3 * n * 4).x;
        float rawSkirt = smoothstep(0.003f, 0.045f, skirtSupport) * smoothstep(0.01f, 0.10f, p7.y);
        float sharpTrans = 1.f - pars[61] * smoothstep(0.04f, 0.22f, shSpread);
        vstore_half4((float4)(p7.x, rawSkirt, max(sharpTrans, rawSkirt), 0.f), idx, G + 7 * n * 4);
        vstore_half4((float4)(wDome, 0.f, 0.f, 0.f), idx, G + 6 * n * 4);
    } else {
        float rhoMacro = vload_half4(idx, G + 2 * n * 4).z;
        vstore_half4((float4)(smoothstep(p0, p1, rhoMacro), 0.f, 0.f, 0.f), idx, G + 2 * n * 4);
    }
}

__kernel void film_boost(__global const float* pars, __global half* G, float skirtGain) {
    int gw = (int)pars[22], gh = (int)pars[23];
    int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= gw || cy >= gh) return;
    int n = gw * gh, idx = cy * gw + cx;
    float3 e0 = vload_half4(idx, G).xyz;
    float3 eClip = vload_half4(idx, G + 8 * n * 4).xyz;
    float3 vecBlur = vload_half4(idx, G + 4 * n * 4).xyz;
    float4 cellStat = vload_half4(idx, G + 5 * n * 4);
    float4 feat = vload_half4(idx, G + 7 * n * 4);
    float wDome = vload_half4(idx, G + 6 * n * 4).x;
    float sheetMask = vload_half4(idx, G + 2 * n * 4).x;
    float cminG = cellStat.y;
    float yEnv = feat.x, rawSkirt = feat.y, effSharp = feat.z;

    float3 sourceMask = (float3)(pars[49] > 0.5f ? 1.f : 0.f, pars[50] > 0.5f ? 1.f : 0.f, pars[51] > 0.5f ? 1.f : 0.f);
    float3 e0Masked = e0 * sourceMask;
    float3 eClipMasked = eClip * sourceMask;
    float eClipScalar = max(eClipMasked.x + eClipMasked.y + eClipMasked.z, 0.f);

    float globalY = pars[53], globalBright = pars[54];
    float yRef = 0.70f * yEnv + 0.30f * globalY;
    float evEnv = log2((0.98f + 0.003f) / (yRef + 0.003f));
    float prominence = smoothstep(pars[59], pars[60], evEnv);

    float compactMacro = 1.f - smoothstep(0.02f, 0.10f, sheetMask);
    float indoor = (1.f - smoothstep(0.025f, 0.065f, globalY)) * (1.f - smoothstep(0.28f, 0.46f, globalBright));
    float windowFloor = pars[62] * indoor * smoothstep(1.8f, 4.0f, evEnv);
    float compactness = max(compactMacro, windowFloor);

    float domeFactor = pars[63] + 0.26f * wDome;
    float spatialFactor = clamp((0.95f * prominence * effSharp + skirtGain * rawSkirt) * compactness, 0.f, 1.f);
    float extraMult = max(pars[25] - 1.f, 0.f) * domeFactor * spatialFactor;

    float eClipSum = max(eClip.x + eClip.y + eClip.z, 1.0e-6f);
    float3 uSelf = eClip / eClipSum;
    float denBlur = vecBlur.x + vecBlur.y + vecBlur.z;
    float ringConf = smoothstep(0.01f, 0.08f, denBlur);
    float whiteCore = smoothstep(0.75f, 0.98f, cminG);
    float3 uSkirt = vecBlur / max(denBlur, 1.0e-6f);
    float blend = ringConf * whiteCore;
    float3 uDir = (1.f - blend) * uSelf + blend * uSkirt;

    float3 eExtra = uDir * sourceMask * (extraMult * eClipScalar);
    vstore_half4((float4)(e0Masked + eExtra, 0.f), idx, G);
    vstore_half4((float4)(eExtra, 0.f), idx, G + 4 * n * 4);
}

__kernel void film_ds2(__global const half* srcBuf, int srcOff, int sW, int sH,
                       __global half* dstBuf, int dstOff, int dW, int dH) {
    int dx = get_global_id(0), dy = get_global_id(1);
    if (dx >= dW || dy >= dH) return;
    __global const half* s = srcBuf + srcOff;
    int x0 = min(dx * 2, sW - 1), x1 = min(dx * 2 + 1, sW - 1);
    int y0 = min(dy * 2, sH - 1), y1 = min(dy * 2 + 1, sH - 1);
    float4 a = vload_half4(y0 * sW + x0, s) + vload_half4(y0 * sW + x1, s) +
               vload_half4(y1 * sW + x0, s) + vload_half4(y1 * sW + x1, s);
    vstore_half4(a * 0.25f, dy * dW + dx, dstBuf + dstOff);
}

__kernel void film_us2(__global const half* srcBuf, int srcOff, int sW, int sH,
                       __global half* dstBuf, int dstOff, int dW, int dH) {
    int dx = get_global_id(0), dy = get_global_id(1);
    if (dx >= dW || dy >= dH) return;
    __global const half* s = srcBuf + srcOff;
    float sx = clamp(0.5f * ((float)dx + 0.5f) - 0.5f, 0.f, (float)max(sW - 1, 0));
    float sy = clamp(0.5f * ((float)dy + 0.5f) - 0.5f, 0.f, (float)max(sH - 1, 0));
    int x0 = min((int)sx, max(sW - 2, 0)), y0 = min((int)sy, max(sH - 2, 0));
    int x1 = min(x0 + 1, sW - 1), y1 = min(y0 + 1, sH - 1);
    float ax = sx - (float)x0, ay = sy - (float)y0;
    float4 v = vload_half4(y0 * sW + x0, s) * ((1.f - ax) * (1.f - ay)) +
               vload_half4(y0 * sW + x1, s) * (ax * (1.f - ay)) +
               vload_half4(y1 * sW + x0, s) * ((1.f - ax) * ay) +
               vload_half4(y1 * sW + x1, s) * (ax * ay);
    vstore_half4(v, dy * dW + dx, dstBuf + dstOff);
}

__kernel void film_blur_sym_half4(__global const half* inBuf, int inOff, __global half* outBuf, int outOff,
                                  int W, int H, int horizontal, int radius, __constant float* w, int wOff) {
    int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= W || cy >= H) return;
    __global const half* in = inBuf + inOff;
    float4 acc = vload_half4(cy * W + cx, in) * w[wOff];
    if (horizontal) {
        int row = cy * W;
        for (int i = 1; i <= radius; i++) {
            float wi = w[wOff + i];
            int xl = max(cx - i, 0), xr = min(cx + i, W - 1);
            acc += wi * (vload_half4(row + xl, in) + vload_half4(row + xr, in));
        }
    } else {
        for (int i = 1; i <= radius; i++) {
            float wi = w[wOff + i];
            int yu = max(cy - i, 0), yd = min(cy + i, H - 1);
            acc += wi * (vload_half4(yu * W + cx, in) + vload_half4(yd * W + cx, in));
        }
    }
    vstore_half4(acc, cy * W + cx, outBuf + outOff);
}

// scattered(record j) = sum over scales k of weight[j][k] * blur_k(light(record j)); plane 2 holds the current blur, plane 3 the sum
__kernel void film_mix(__global const float* pars, __global half* G, int k) {
    int gw = (int)pars[22], gh = (int)pars[23];
    int cx = get_global_id(0), cy = get_global_id(1);
    if (cx >= gw || cy >= gh) return;
    int n = gw * gh, idx = cy * gw + cx;
    float4 b = vload_half4(idx, G + 2 * n * 4);
    float4 w = (float4)(pars[37 + k], pars[37 + 3 + k], pars[37 + 6 + k], 0.f);
    float4 s = k == 0 ? (float4)(0.f) : vload_half4(idx, G + 3 * n * 4);
    vstore_half4(s + w * b, idx, G + 3 * n * 4);
}
)film_cl";

// The library's own proproc.cl text (a string in its read-only data, the source its kernels were built from).
struct Blob {
    const char *begin = nullptr;
    size_t size = 0;
};

int scanLibrary(dl_phdr_info *info, size_t, void *data) {
    static const char marker[] = "#define PROPROC_RGB_TO_P010(";
    static const char pixel[] = "inline float3 proproc3F_rgb_p010_pixel_u16(";
    if (!info->dlpi_name || !std::strstr(info->dlpi_name, "libOPAlgoCamHybridRaw.so")) return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_R)) continue;
        const char *begin = reinterpret_cast<const char *>(info->dlpi_addr + ph.p_vaddr), *limit = begin + ph.p_filesz;
        for (const char *at = begin; at < limit;) {
            const char *hit = static_cast<const char *>(memmem(at, size_t(limit - at), marker, sizeof(marker) - 1));
            if (!hit) break;
            const char *start = hit, *end = hit;
            while (start > begin && start[-1] != 0) --start;
            while (end < limit && *end) ++end;
            if (memmem(start, size_t(end - start), pixel, sizeof(pixel) - 1)) {
                static_cast<Blob *>(data)->begin = start;
                static_cast<Blob *>(data)->size = size_t(end - start);
                return 1;
            }
            at = end;
        }
    }
    return 0;
}

// The program the film kernels are built from: the helper source of the capture dump (base, common functions, the noise helpers, the
// stock prefix up to the linear light), the film's functions, the library's 3F pixel function with the two steps added, its kernel macro
// with the film's `pars` handed to the pixel function, and one instantiation under the film's name.
bool filmProgramSource(std::string &out) {
    Blob blob;
    dl_iterate_phdr(scanLibrary, &blob);
    if (!blob.begin) {
        filmTrace("proproc.cl not found in the library");
        return false;
    }
    const std::string text(blob.begin, blob.size);
    const size_t pixelAt = text.find("inline float3 proproc3F_rgb_p010_pixel_u16("), macroAt = text.find("#define PROPROC_RGB_TO_P010("),
                 instanceAt = text.find("PROPROC_RGB_TO_P010(proproc3F_rgb_p010_u16_c1_buf");
    if (pixelAt == std::string::npos || macroAt == std::string::npos || instanceAt == std::string::npos || !(pixelAt < macroAt && macroAt < instanceAt)) {
        filmTrace("proproc.cl layout not as expected (%zu bytes)", text.size());
        return false;
    }
    std::string pixel = text.substr(pixelAt, macroAt - pixelAt);
    auto replaceOnce = [&](std::string &where, const std::string &from, const std::string &to) {
        size_t at = where.find(from);
        if (at == std::string::npos || where.find(from, at + 1) != std::string::npos) {
            filmTrace("proproc.cl: \"%.50s\" %s", from.c_str(), at == std::string::npos ? "missing" : "not unique");
            return false;
        }
        where.replace(at, from.size(), to);
        return true;
    };
    std::string macro = text.substr(macroAt, instanceAt - macroAt);
    if (!replaceOnce(pixel, "inline float3 proproc3F_rgb_p010_pixel_u16(__global half* src,",
                     "inline float3 proproc3F_rgb_p010_pixel_u16(__global float* filmPars, __global half* filmGrid, __global half* filmNoise, int filmNoiseY0, __global half* src,") ||
        !replaceOnce(pixel, "float3 ccmVec = (float3)(ccmR, ccmG, ccmB);",
                     "float3 ccmVec = (float3)(ccmR, ccmG, ccmB);\n    ccmVec = film_vignette(ccmVec, x, y, filmPars);\n    ccmVec = film_halation(ccmVec, x, y, filmPars, filmGrid);") ||
        !replaceOnce(pixel, "float3 gammaVec = clamp(gammaOri * gammaMaxScale, 0.f, 1.f);",
                     "float3 gammaVec = clamp(gammaOri * gammaMaxScale, 0.f, 1.f);\n    gammaVec = film_grain(gammaVec, x, y, filmPars, filmNoise, filmNoiseY0);") ||
        !replaceOnce(macro, "float dipNotAllMidEdge1)",
                     "float dipNotAllMidEdge1, __global half* filmGrid, __global half* filmNoise, int filmNoiseY0)") ||
        !replaceOnce(macro, "PIXELFUN(src,", "PIXELFUN(pars, filmGrid, filmNoise, filmNoiseY0, src,"))
        return false;
    out = std::string(kCcmDumpSource) + layoutDefines() + kFilmFunctions + pixel + macro + kFilmKernels +
          "PROPROC_RGB_TO_P010(proproc3F_film_rgb_p010_u16_c1_buf, ushort, proproc3F_rgb_p010_pixel_u16)\n";
    return true;
}

// The built kernels, per OpenCL context.
struct Program {
    clapi::Api api;
    void *program = nullptr, *tile = nullptr, *noiseTile = nullptr, *source = nullptr, *reduce = nullptr, *stage = nullptr,
         *ds2 = nullptr, *us2 = nullptr, *blurSym = nullptr, *mix = nullptr, *boost = nullptr;
    explicit Program(clapi::Api a) : api(a) {}
    ~Program() {
        for (void *kernel : {tile, noiseTile, source, reduce, stage, ds2, us2, blurSym, mix, boost})
            if (kernel) api.releaseKernel(kernel);
        if (program) api.releaseProgram(program);
    }
};

struct LiteProgram {
    clapi::Api api;
    void *program = nullptr;
    void *kernel = nullptr;
    explicit LiteProgram(clapi::Api a) : api(a) {}
    ~LiteProgram() {
        if (kernel) api.releaseKernel(kernel);
        if (program) api.releaseProgram(program);
    }
};

int scanLiteLibrary(dl_phdr_info *info, size_t, void *data) {
    static const char marker[] = "proproc_lite_rgb_p010_u16_c1_buf";
    static const char pixel[] = "inline float3 proproc_lite_rgb_p010_pixel_u16(";
    if (!info->dlpi_name || (!std::strstr(info->dlpi_name, "libOPAlgoCamHybridRawQuick.so")
                             && !std::strstr(info->dlpi_name, "libOPAlgoCamHybridRaw.so")))
        return 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_R)) continue;
        const char *begin = reinterpret_cast<const char *>(info->dlpi_addr + ph.p_vaddr), *limit = begin + ph.p_filesz;
        for (const char *at = begin; at < limit;) {
            const char *hit = static_cast<const char *>(memmem(at, size_t(limit - at), marker, sizeof(marker) - 1));
            if (!hit) break;
            const char *start = hit, *end = hit;
            while (start > begin && start[-1] != 0) --start;
            while (end < limit && *end) ++end;
            if (memmem(start, size_t(end - start), pixel, sizeof(pixel) - 1)) {
                static_cast<Blob *>(data)->begin = start;
                static_cast<Blob *>(data)->size = size_t(end - start);
                return 1;
            }
            at = end;
        }
    }
    return 0;
}

bool liteProgramSource(std::string &out) {
    Blob blob;
    dl_iterate_phdr(scanLiteLibrary, &blob);
    if (!blob.begin) {
        filmTrace("proproc_lite.cl not found in library");
        return false;
    }
    std::string lite(blob.begin, blob.size);
    auto replaceOnce = [&](std::string &where, const std::string &from, const std::string &to) {
        size_t at = where.find(from);
        if (at == std::string::npos || where.find(from, at + 1) != std::string::npos) {
            filmTrace("proproc_lite.cl: \"%.50s\" %s", from.c_str(), at == std::string::npos ? "missing" : "not unique");
            return false;
        }
        where.replace(at, from.size(), to);
        return true;
    };
    if (!replaceOnce(lite, "float outScaleW, float outScaleH, int offsetX, int offsetY, float whiteLevel)",
                     "float outScaleW, float outScaleH, int offsetX, int offsetY, float whiteLevel, float4 filmVigParam)") ||
        !replaceOnce(lite, "float3 srcVec = common_func_resize_bilinear_f32_c3_buf(src, srcCoord.x, srcCoord.y, srcSize.s0, srcSize.s1, srcSize.s2);",
                     "float3 srcVec = common_func_resize_bilinear_f32_c3_buf(src, srcCoord.x, srcCoord.y, srcSize.s0, srcSize.s1, srcSize.s2);\n"
                     "    if (filmVigParam.x > 0.f) {\n"
                     "        float2 vigD = (dstCoordnorm * 2.f - 1.f) * filmVigParam.xy;\n"
                     "        float vigC1 = 1.f / (1.f + dot(vigD, vigD));\n"
                     "        float vigGain = filmVigParam.z;\n"
                     "        srcVec *= vigC1 * vigC1 * vigGain;\n"
                     "        if (vigGain > 1.001f) {\n"
                     "            float3 normSrc = srcVec * (1.f / 65535.f);\n"
                     "            float3 t = fmax(normSrc - 0.85f, 0.f);\n"
                     "            float3 soft = 0.85f + 0.15f * (t / (0.15f + t));\n"
                     "            srcVec = select(normSrc, soft, isgreater(normSrc, (float3)(0.85f))) * 65535.f;\n"
                     "        }\n"
                     "    }") ||
        !replaceOnce(lite, "__kernel void proproc_lite_rgb_p010_u16_c1_buf(",
                     "__kernel void proproc_lite_film_rgb_p010_u16_c1_buf(") ||
        !replaceOnce(lite, "float whiteLevel, int lsb2msb)",
                     "float whiteLevel, int lsb2msb, float4 filmVigParam)") ||
        !replaceOnce(lite, "outScaleW, outScaleH, offsetX, offsetY, whiteLevel);",
                     "outScaleW, outScaleH, offsetX, offsetY, whiteLevel, filmVigParam);"))
        return false;
    const std::string dumpSrc(kCcmDumpSource);
    const size_t proprocStart = dumpSrc.find("** File: - proproc.cl");
    const std::string helpers = proprocStart != std::string::npos ? dumpSrc.substr(0, proprocStart) : dumpSrc;
    out = helpers + "\n" + lite;
    return true;
}

std::unique_ptr<LiteProgram> buildLiteProgram(const clapi::Api &api, void *context, void *queue) {
    std::string source;
    if (!liteProgramSource(source)) return nullptr;
    void *device = nullptr;
    if (!clapi::clOk(api.queueInfo(queue, 0x1091, sizeof(device), &device, nullptr), "lite queue device")) return nullptr;
    auto built = std::make_unique<LiteProgram>(api);
    const char *text = source.c_str();
    int error = 0;
    built->program = api.createProgram(context, 1, &text, nullptr, &error);
    if (!clapi::clOk(error, "lite createProgram")) return nullptr;
    const void *devices[] = {device};
    error = api.buildProgram(built->program, 1, devices,
        "-cl-std=CL2.0 -O3 -cl-mad-enable -cl-single-precision-constant -DCL_KHR_FP16_ENABLE=1 -DCL_PLATFORM_QCOM", nullptr, nullptr);
    if (error != 0) {
        size_t size = 0;
        api.buildInfo(built->program, device, 0x1183, 0, nullptr, &size);
        std::vector<char> log(size + 1);
        api.buildInfo(built->program, device, 0x1183, size, log.data(), nullptr);
        filmTrace("lite program build failed (%d): %.220s", error, log.data());
        return nullptr;
    }
    built->kernel = api.createKernel(built->program, kLiteFilmName, &error);
    if (!clapi::clOk(error, "lite createKernel")) return nullptr;
    return built;
}

std::unique_ptr<Program> buildProgram(const clapi::Api &api, void *context, void *queue) {
    std::string source;
    if (!filmProgramSource(source)) return nullptr;
    void *device = nullptr;
    if (!clapi::clOk(api.queueInfo(queue, 0x1091, sizeof(device), &device, nullptr), "queue device")) return nullptr;
    auto built = std::make_unique<Program>(api);
    const char *text = source.c_str();
    int error = 0;
    built->program = api.createProgram(context, 1, &text, nullptr, &error);
    if (!clapi::clOk(error, "film createProgram")) return nullptr;
    const void *devices[] = {device};
    error = api.buildProgram(built->program, 1, devices,
        "-cl-std=CL2.0 -O3 -cl-mad-enable -cl-single-precision-constant -DCL_KHR_FP16_ENABLE=1 -DCL_PLATFORM_QCOM", nullptr, nullptr);
    if (error != 0) {
        size_t size = 0;
        api.buildInfo(built->program, device, 0x1183, 0, nullptr, &size);
        std::vector<char> log(size + 1);
        api.buildInfo(built->program, device, 0x1183, size, log.data(), nullptr);
        filmTrace("film program build failed (%d): %.220s", error, log.data());
        captureDumpWrite("/data/user/0/com.oplus.camera/files/jiege/film_proproc_build.log", log.data(), size);
        return nullptr;
    }
    struct {
        void **slot;
        const char *name;
    } kernels[] = {{&built->tile, kFilmName},
                   {&built->noiseTile, "film_noise_tile"},
                   {&built->source, "film_source"},
                   {&built->reduce, "film_reduce"},
                   {&built->stage, "film_stage"},
                   {&built->ds2, "film_ds2"},
                   {&built->us2, "film_us2"},
                   {&built->blurSym, "film_blur_sym_half4"},
                   {&built->mix, "film_mix"},
                   {&built->boost, "film_boost"}};
    for (auto &k : kernels) {
        *k.slot = api.createKernel(built->program, k.name, &error);
        if (!clapi::clOk(error, k.name)) return nullptr;
    }
    return built;
}

struct Plan {
    std::string selection;
    std::array<int, 9> ccm{};
    bool luxBlend = false;
    int width = 0, height = 0;
    bool hasGrain = false, hasHalo = false, hasVignette = false;
    float vigScaleX = 0.0f, vigScaleY = 0.0f, vigGain = 1.0f;
    BoostProfile boost = kBoostProfiles[0];
    FilmGrainPlan grain;
    FilmHalationPlan halo;
};

struct BlurEntry {
    int levels = 0;
    int radius = 0;
    int wOff = 0;
};

// The frame in flight: the pars buffer and GPU scratch/noise/icdf/weight buffers shared across its tiles.
struct Frame {
    void *parsMem = nullptr;
    void *gridMem = nullptr;
    void *pyrMem = nullptr;
    void *noiseMem = nullptr;
    void *icdfMem = nullptr;
    void *wMem = nullptr;
    void *dummyMem = nullptr;
    void *dst = nullptr;
    int pyrStride = 0;
    int noiseRows = 0;
    uint32_t seed = 0;
    std::vector<BlurEntry> blurs;
    int (*releaseMem)(void *) = nullptr;
    ~Frame() { reset(); }
    void reset() {
        for (void **slot : {&parsMem, &gridMem, &pyrMem, &noiseMem, &icdfMem, &wMem, &dummyMem}) {
            if (*slot && releaseMem) releaseMem(*slot);  // commands still queued keep it
            *slot = nullptr;
        }
        dst = nullptr;
        pyrStride = 0;
        noiseRows = 0;
        blurs.clear();
    }
};

std::mutex mutex;
std::unordered_map<void *, std::unique_ptr<Program>> programs;
std::unordered_map<void *, std::unique_ptr<LiteProgram>> litePrograms;
bool programFailed = false;
bool liteProgramFailed = false;
std::shared_ptr<const Plan> lastPlan;
Frame frame;
uint32_t counter = 0;

bool setInt(const clapi::Api &api, void *kernel, unsigned index, int value) {
    return clapi::clOk(api.setArg(kernel, index, sizeof(value), &value), "film set int");
}

uint16_t floatToHalf(float value) {
    uint32_t f = 0;
    std::memcpy(&f, &value, sizeof(f));
    const uint32_t sign = (f >> 16) & 0x8000u;
    const int exp = int((f >> 23) & 0xFFu) - 127 + 15;
    uint32_t mant = f & 0x007FFFFFu;
    if (exp <= 0) {
        if (exp < -10) return uint16_t(sign);
        mant = (mant | 0x00800000u) >> (1 - exp);
        return uint16_t(sign | ((mant + 0x00001000u) >> 13));
    }
    if (exp >= 31) return uint16_t(sign | 0x7BFFu);
    mant += 0x00001000u;
    if (mant & 0x00800000u) {
        mant = 0;
        if (exp + 1 >= 31) return uint16_t(sign | 0x7BFFu);
        return uint16_t(sign | (uint32_t(exp + 1) << 10));
    }
    return uint16_t(sign | (uint32_t(exp) << 10) | (mant >> 13));
}

void buildIcdfHalf(float lambda, float norm, uint16_t *out) {
    constexpr double kInvSqrt2 = 0.7071067811865475;
    if (lambda >= 16.0f) {
        for (int i = 0; i < kIcdfSize; ++i) {
            const double u = (i + 0.5) / double(kIcdfSize);
            double lo = -6.0, hi = 6.0;
            for (int it = 0; it < 40; ++it) {
                const double mid = 0.5 * (lo + hi);
                const double cdf = 0.5 * (1.0 + std::erf(mid * kInvSqrt2));
                if (cdf < u) lo = mid; else hi = mid;
            }
            out[i] = floatToHalf(float(0.5 * (lo + hi)));
        }
        return;
    }
    double cdf[33];
    double p = std::exp(-double(lambda));
    cdf[0] = p;
    for (int k = 1; k <= 32; ++k) {
        p *= double(lambda) / double(k);
        cdf[k] = cdf[k - 1] + p;
    }
    cdf[32] = 1.0;
    for (int i = 0; i < kIcdfSize; ++i) {
        const double u = (i + 0.5) / double(kIcdfSize);
        int k = 0;
        while (k < 32 && cdf[k] < u) ++k;
        out[i] = floatToHalf(float((double(k) - double(lambda)) * double(norm)));
    }
}

BlurEntry appendBlurEntry(float sigma, std::vector<float> &wPack) {
    const float s = std::max(sigma, 0.5f);
    int levels = 0;
    if (s > 2.2f) levels = std::min(3, std::max(0, int(std::floor(std::log2(s / 1.5f)))));
    const int scale = 1 << levels;
    float sEff = s;
    int capR = 160;
    if (levels > 0) {
        const double pow4 = double(scale) * double(scale);
        const double addedVar = (pow4 - 1.0) / 12.0 + 0.25 * (pow4 - 1.0) / 3.0;
        sEff = float(std::sqrt(std::max((double(s) * s - addedVar) / pow4, 0.25)));
        capR = std::max(1, int(std::lround(160.0 / double(scale))));
    }
    const int radius = std::min(int(std::ceil(3.0f * sEff)), capR);
    const int wOff = int(wPack.size());
    std::vector<double> w(size_t(radius + 1));
    double total = 1.0;
    w[0] = 1.0;
    for (int k = 1; k <= radius; ++k) {
        w[k] = std::exp(-0.5 * double(k * k) / (double(sEff) * sEff));
        total += 2.0 * w[k];
    }
    for (int k = 0; k <= radius; ++k) wPack.push_back(float(w[k] / total));
    return {levels, radius, wOff};
}

void computeVignetteScales(int width, int height, float focalMm, float &scaleX, float &scaleY) {
    constexpr double kHalfLong43 = 17.306646;
    constexpr double kHalfShort43 = 12.979985;
    const double longPx = double(std::max(width, height));
    const double shortPx = double(std::max(1, std::min(width, height)));
    const double aspect = longPx / shortPx;
    const double halfLongMm = (aspect >= 4.0 / 3.0) ? kHalfLong43 : kHalfShort43 * aspect;
    const double halfShortMm = (aspect >= 4.0 / 3.0) ? kHalfLong43 / aspect : kHalfShort43;
    const double f = std::max(double(focalMm), 1.0);
    const double sLong = halfLongMm / f;
    const double sShort = halfShortMm / f;
    if (width >= height) {
        scaleX = float(sLong);
        scaleY = float(sShort);
    } else {
        scaleX = float(sShort);
        scaleY = float(sLong);
    }
}

// Allocates and initializes the compact pars buffer (~3 MB) and GPU-only working buffers for a frame.
bool makeFrame(const clapi::Api &api, void *context, const Plan &plan, const float stock[15], int width, int height, uint32_t seed,
               int maxNoiseRows, Frame &out) {
    out.releaseMem = api.releaseMem;
    out.seed = seed;
    std::vector<float> pars(size_t(kGridBase), 0.0f);
    std::memcpy(pars.data(), stock, 15 * sizeof(float));
    pars[kFrameW] = float(width);
    pars[kFrameH] = float(height);
    if (plan.hasVignette) {
        pars[kVigEnable] = 1.0f;
        const float halfW = 0.5f * float(width);
        const float halfH = 0.5f * float(height);
        pars[kVigKx] = plan.vigScaleX / halfW;
        pars[kVigBx] = (0.5f / halfW - 1.0f) * plan.vigScaleX;
        pars[kVigKy] = plan.vigScaleY / halfH;
        pars[kVigBy] = (0.5f / halfH - 1.0f) * plan.vigScaleY;
        pars[kVigGain] = plan.vigGain;
    }
    std::vector<uint16_t> icdf(size_t(2) * kIcdfSize, 0);
    if (plan.hasGrain) {
        pars[kGrain] = 1.0f;
        std::memcpy(&pars[kSeed], &seed, sizeof(seed));
        pars[kAxis] = plan.grain.axisMax;
        pars[kLattice] = float(kFilmLattice);
        pars[kPopulations] = float(plan.grain.populations.size());
        for (size_t p = 0; p < plan.grain.populations.size() && p < 2; ++p) {
            const FilmGrainPopulation &f = plan.grain.populations[p];
            float *q = &pars[size_t(kPopulationBase) + p * kPopulationStride];
            q[0] = f.lambda;
            q[1] = f.limit;
            q[2] = f.norm;
            q[3] = f.own;
            q[4] = f.shared;
            q[5] = float(f.layerBase);
            q[6] = float(f.sharedLayer);
            q[7] = float(f.radius);
            for (int c = 0; c < 3; c++) {
                q[8 + c] = f.amp[c];
                for (size_t t = 0; t < f.taps[c].size() && t < size_t(kTapStride); ++t) q[11 + c * kTapStride + t] = f.taps[c][t];
            }
            buildIcdfHalf(f.lambda, f.norm, icdf.data() + p * kIcdfSize);
        }
        std::memcpy(&pars[kGainBase], plan.grain.gain.data(), plan.grain.gain.size() * sizeof(float));
    }
    std::vector<float> wPack;
    if (plan.hasHalo) {
        const FilmHalationPlan &h = plan.halo;
        const BoostProfile &b = plan.boost;
        pars[kHalo] = 1.0f;
        pars[kGridW] = float(h.gridW);
        pars[kGridH] = float(h.gridH);
        pars[kCell] = float(h.cell);
        pars[kSourceGain] = b.peak;
        pars[kPxPerMM] = h.pxPerMM;
        pars[kWhiteLo] = b.whiteLo;
        pars[kDualLo] = b.dualLo;
        pars[kDualSpan] = b.dualSpan;
        pars[kDualWeight] = b.dualWeight;
        pars[kPromLo] = b.promLo;
        pars[kPromHi] = b.promHi;
        pars[kRampDamp] = b.rampDamp;
        pars[kWinFloor] = b.winFloor;
        pars[kDomeBase] = b.domeBase;
        for (int c = 0; c < 3; c++)
            for (int j = 0; j < 3; j++) pars[kMatrix + c * 3 + j] = h.matrix[c][j];
        for (int j = 0; j < 3; j++)
            for (int k = 0; k < 3; k++) pars[kWeights + j * 3 + k] = h.weights[j][k];
        for (int k = 0; k < 3; k++) {
            pars[kSigma + k] = h.sigmaCells[k];
            pars[kSources + k] = h.source[k];
        }
        std::memcpy(&pars[kExposureBase], h.exposure.data(), h.exposure.size() * sizeof(float));
        std::memcpy(&pars[kInverseBase], h.inverse.data(), h.inverse.size() * sizeof(float));

        const float cell = float(h.cell);
        const float minDim = float(std::min(width, height));
        const float sigChroma = std::max(1.5f, 0.35f * h.pxPerMM / cell);
        const float sigIn = std::max(1.0f, 0.16f * h.pxPerMM / cell);
        const float sigOut = std::max(2.5f, 0.65f * h.pxPerMM / cell);
        const float sigDome = std::max(1.0f, 0.20f * h.pxPerMM / cell);
        const float sigEnv = std::max(3.0f, (minDim / 9.0f) / cell);
        const float sigSh = std::max(2.5f, (minDim / 10.0f) / cell);
        const float sigMacro = std::max(3.5f, (minDim / b.macroDiv) / cell);
        for (float sig : {sigChroma, sigEnv, sigIn, sigOut, sigSh, sigIn * 1.5f, sigSh * 0.8f, sigDome, sigMacro,
                          sigMacro * 1.15f, h.sigmaCells[0], h.sigmaCells[1], h.sigmaCells[2]}) {
            out.blurs.push_back(appendBlurEntry(sig, wPack));
        }
    }
    int error = 0;
    out.parsMem = api.createBuffer(context, 1 | 32, pars.size() * sizeof(float), pars.data(), &error);
    if (!clapi::clOk(error, "create film pars")) { out.reset(); return false; }
    out.dummyMem = api.createBuffer(context, 1, 64, nullptr, &error);
    if (!clapi::clOk(error, "create film dummy")) { out.reset(); return false; }
    if (plan.hasGrain) {
        out.noiseRows = std::max(1, std::min(height, maxNoiseRows));
        const size_t noiseBytes = size_t(width) * size_t(out.noiseRows) * 4 * sizeof(uint16_t);
        out.noiseMem = api.createBuffer(context, 1, noiseBytes, nullptr, &error);
        if (!clapi::clOk(error, "create film noise")) { out.reset(); return false; }
        out.icdfMem = api.createBuffer(context, 4 | 32, icdf.size() * sizeof(uint16_t), icdf.data(), &error);
        if (!clapi::clOk(error, "create film icdf")) { out.reset(); return false; }
    }
    if (plan.hasHalo) {
        const size_t nCells = size_t(plan.halo.gridW) * size_t(plan.halo.gridH);
        const size_t gridBytes = size_t(kPlanes) * nCells * 4 * sizeof(uint16_t);
        out.gridMem = api.createBuffer(context, 1, gridBytes, nullptr, &error);
        if (!clapi::clOk(error, "create film grid")) { out.reset(); return false; }
        const size_t pw1 = size_t((plan.halo.gridW + 1) / 2);
        const size_t ph1 = size_t((plan.halo.gridH + 1) / 2);
        out.pyrStride = int(pw1 * ph1 * 4);
        const size_t pyrBytes = size_t(3) * size_t(out.pyrStride) * sizeof(uint16_t);
        out.pyrMem = api.createBuffer(context, 1, std::max<size_t>(pyrBytes, 64), nullptr, &error);
        if (!clapi::clOk(error, "create film pyr")) { out.reset(); return false; }
        out.wMem = api.createBuffer(context, 4 | 32, std::max<size_t>(wPack.size(), 1) * sizeof(float), wPack.data(), &error);
        if (!clapi::clOk(error, "create film blur weights")) { out.reset(); return false; }
    }
    return true;
}

// The returned light of the frame, on its half4 GPU grid, before the first tile.
bool makeReturnedLight(const clapi::Api &api, void *queue, Program &program, const Plan &plan, const Frame &frame,
                       const clapi::Arg *args, unsigned dim) {
    using namespace clapi;
    const FilmHalationPlan &h = plan.halo;
    const int gw = h.gridW, gh = h.gridH, planeOff = gw * gh * 4;
    const size_t reduceSize[1] = {64};
    bool ok = true;
    auto enqueueGrid16 = [&](void *kernel, int w, int hgt, const char *label) {
        const size_t g[2] = {size_t(((w + 15) / 16) * 16), size_t(((hgt + 15) / 16) * 16)};
        const size_t l[2] = {16, 16};
        return clOk(api.enqueue(queue, kernel, dim, nullptr, g, l, 0, nullptr, nullptr), label);
    };
    for (unsigned i = 0; i < 43 && ok; ++i)
        ok = clOk(api.setArg(program.source, i, i == 4 ? sizeof(frame.parsMem) : args[i].size,
                             i == 4 ? static_cast<const void *>(&frame.parsMem) : args[i].value),
                  "source set arg");
    ok = ok && clOk(api.setArg(program.source, 43, sizeof(frame.gridMem), &frame.gridMem), "source grid arg");
    if (ok) ok = enqueueGrid16(program.source, gw, gh, "source enqueue");
    ok = ok && clOk(api.setArg(program.reduce, 0, sizeof(frame.parsMem), &frame.parsMem), "reduce pars") &&
         clOk(api.setArg(program.reduce, 1, sizeof(frame.gridMem), &frame.gridMem), "reduce grid") &&
         clOk(api.enqueue(queue, program.reduce, 1, nullptr, reduceSize, reduceSize, 0, nullptr, nullptr), "reduce enqueue");

    auto runBlurSym = [&](void *inBuf, int inOff, void *outBuf, int outOff, int w, int hgt, int horiz, const BlurEntry &be) {
        ok = ok && clOk(api.setArg(program.blurSym, 0, sizeof(inBuf), &inBuf), "blur in") &&
             setInt(api, program.blurSym, 1, inOff) &&
             clOk(api.setArg(program.blurSym, 2, sizeof(outBuf), &outBuf), "blur out") &&
             setInt(api, program.blurSym, 3, outOff) &&
             setInt(api, program.blurSym, 4, w) && setInt(api, program.blurSym, 5, hgt) &&
             setInt(api, program.blurSym, 6, horiz) && setInt(api, program.blurSym, 7, be.radius) &&
             clOk(api.setArg(program.blurSym, 8, sizeof(frame.wMem), &frame.wMem), "blur w") &&
             setInt(api, program.blurSym, 9, be.wOff) &&
             enqueueGrid16(program.blurSym, w, hgt, "blur enqueue");
    };
    auto runDs2 = [&](void *srcBuf, int srcOff, int sw, int sh, void *dstBuf, int dstOff, int dw, int dh) {
        ok = ok && clOk(api.setArg(program.ds2, 0, sizeof(srcBuf), &srcBuf), "ds2 src") && setInt(api, program.ds2, 1, srcOff) &&
             setInt(api, program.ds2, 2, sw) && setInt(api, program.ds2, 3, sh) &&
             clOk(api.setArg(program.ds2, 4, sizeof(dstBuf), &dstBuf), "ds2 dst") && setInt(api, program.ds2, 5, dstOff) &&
             setInt(api, program.ds2, 6, dw) && setInt(api, program.ds2, 7, dh) &&
             enqueueGrid16(program.ds2, dw, dh, "ds2 enqueue");
    };
    auto runUs2 = [&](void *srcBuf, int srcOff, int sw, int sh, void *dstBuf, int dstOff, int dw, int dh) {
        ok = ok && clOk(api.setArg(program.us2, 0, sizeof(srcBuf), &srcBuf), "us2 src") && setInt(api, program.us2, 1, srcOff) &&
             setInt(api, program.us2, 2, sw) && setInt(api, program.us2, 3, sh) &&
             clOk(api.setArg(program.us2, 4, sizeof(dstBuf), &dstBuf), "us2 dst") && setInt(api, program.us2, 5, dstOff) &&
             setInt(api, program.us2, 6, dw) && setInt(api, program.us2, 7, dh) &&
             enqueueGrid16(program.us2, dw, dh, "us2 enqueue");
    };
    auto blurPlane = [&](int from, int to, int blurIdx) {
        const BlurEntry &be = frame.blurs[size_t(blurIdx)];
        if (be.levels == 0) {
            runBlurSym(frame.gridMem, from * planeOff, frame.gridMem, 1 * planeOff, gw, gh, 1, be);
            runBlurSym(frame.gridMem, 1 * planeOff, frame.gridMem, to * planeOff, gw, gh, 0, be);
            return;
        }
        const int L = be.levels;
        std::pair<int, int> dims[4];
        dims[0] = {gw, gh};
        int cw = gw, ch = gh;
        int curSlot = 0;
        for (int l = 0; l < L; ++l) {
            const int nw = (cw + 1) / 2, nh = (ch + 1) / 2;
            const int nxtSlot = l & 1;
            if (l == 0) runDs2(frame.gridMem, from * planeOff, cw, ch, frame.pyrMem, nxtSlot * frame.pyrStride, nw, nh);
            else runDs2(frame.pyrMem, curSlot * frame.pyrStride, cw, ch, frame.pyrMem, nxtSlot * frame.pyrStride, nw, nh);
            curSlot = nxtSlot;
            cw = nw;
            ch = nh;
            dims[l + 1] = {cw, ch};
        }
        runBlurSym(frame.pyrMem, curSlot * frame.pyrStride, frame.pyrMem, 2 * frame.pyrStride, cw, ch, 1, be);
        runBlurSym(frame.pyrMem, 2 * frame.pyrStride, frame.pyrMem, curSlot * frame.pyrStride, cw, ch, 0, be);
        for (int l = L - 1; l >= 0; --l) {
            const int dw = dims[l].first, dh = dims[l].second;
            const int nxtSlot = 1 - curSlot;
            if (l == 0) runUs2(frame.pyrMem, curSlot * frame.pyrStride, cw, ch, frame.gridMem, to * planeOff, dw, dh);
            else runUs2(frame.pyrMem, curSlot * frame.pyrStride, cw, ch, frame.pyrMem, nxtSlot * frame.pyrStride, dw, dh);
            curSlot = nxtSlot;
            cw = dw;
            ch = dh;
        }
    };
    auto runStage = [&](int stage, float p0 = 0.0f, float p1 = 0.0f) {
        ok = ok && clOk(api.setArg(program.stage, 0, sizeof(frame.parsMem), &frame.parsMem), "stage pars") &&
             clOk(api.setArg(program.stage, 1, sizeof(frame.gridMem), &frame.gridMem), "stage grid") &&
             setInt(api, program.stage, 2, stage) &&
             clOk(api.setArg(program.stage, 3, sizeof(p0), &p0), "stage p0") &&
             clOk(api.setArg(program.stage, 4, sizeof(p1), &p1), "stage p1") &&
             enqueueGrid16(program.stage, gw, gh, "stage enqueue");
    };

    const BoostProfile &b = plan.boost;
    blurPlane(4, 4, 0);   // sigChroma
    blurPlane(6, 3, 1);   // sigEnv
    blurPlane(6, 2, 2);   // sigIn
    blurPlane(6, 7, 3);   // sigOut
    blurPlane(6, 6, 4);   // sigSh
    runStage(0);
    blurPlane(2, 2, 5);   // sigIn * 1.5
    blurPlane(6, 6, 6);   // sigSh * 0.8
    blurPlane(5, 3, 7);   // sigDome
    runStage(1);
    blurPlane(5, 2, 8);   // sigMacro
    runStage(2, b.sheetLo, b.sheetHi);
    blurPlane(2, 2, 9);   // sigMacro * 1.15

    ok = ok && clOk(api.setArg(program.boost, 0, sizeof(frame.parsMem), &frame.parsMem), "boost pars") &&
         clOk(api.setArg(program.boost, 1, sizeof(frame.gridMem), &frame.gridMem), "boost grid") &&
         clOk(api.setArg(program.boost, 2, sizeof(b.skirtGain), &b.skirtGain), "boost skirt") &&
         enqueueGrid16(program.boost, gw, gh, "boost enqueue");
    for (int k = 0; k < 3 && ok; ++k) {
        blurPlane(0, 2, 10 + k);
        ok = ok && clOk(api.setArg(program.mix, 0, sizeof(frame.parsMem), &frame.parsMem), "mix pars") &&
             clOk(api.setArg(program.mix, 1, sizeof(frame.gridMem), &frame.gridMem), "mix grid") &&
             setInt(api, program.mix, 2, k) &&
             enqueueGrid16(program.mix, gw, gh, "mix enqueue");
    }
    return ok;
}

}  // namespace

void filmTrace(const char *format, ...) {
    char text[300];
    va_list args;
    va_start(args, format);
    std::vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", text);
    const char *path = "/data/user/0/com.oplus.camera/files/jiege/film_proproc.log";
    struct stat info;
    if (stat(path, &info) == 0 && info.st_size > 65536) std::remove(path);
    if (FILE *file = std::fopen(path, "a")) {
        std::fprintf(file, "%ld %s\n", long(std::time(nullptr)), text);
        std::fclose(file);
    }
}

bool filmProProcOwns(const std::string &name) { return name == kStockName || name == kLiteName; }

static float computeVigGain(float sx, float sy, const std::string &look, const mastercolor::Params &params) {
    if (look == "oppo") return 1.0f;
    auto it = params.find("vig_comp");
    const float frac = it != params.end() ? std::clamp(it->second, 0.0f, 1.0f) : 0.0f;
    if (!(frac > 0.0f)) return 1.0f;
    constexpr int nx = 12, ny = 9;
    double sum = 0.0;
    for (int j = 0; j < ny; ++j) {
        const double y = ((j + 0.5) / ny * 2.0 - 1.0) * sy;
        const double y2 = y * y;
        for (int i = 0; i < nx; ++i) {
            const double x = ((i + 0.5) / nx * 2.0 - 1.0) * sx;
            const double c1 = 1.0 / (1.0 + x * x + y2);
            sum += c1 * c1;
        }
    }
    const double mean = std::max(1e-3, sum / (nx * ny));
    return float(std::clamp(std::pow(1.0 / mean, double(frac)), 1.0, 2.6));
}

static bool filmProProcRunLite(void *engine, const CaptureKernelArgs &builder, unsigned dim, const size_t *offset,
                               const size_t *global, const size_t *local, bool sync) {
    using namespace clapi;
    const Arg *args = builder.begin;
    if (builder.end - builder.begin != 33 || dim != 2 || !global) return false;
    if (monoPanSelected()) return false;
    std::string look;
    float toneWeight = 1, colourWeight = 1;
    mastercolor::Params params;
    masterColorLook(look, toneWeight, colourWeight, params);
    Api api(engine);
    void *queue = field<void *>(engine, 0x50);
    float ccm[9];
    bool sceneMatched = false;
    int sceneCct = 0;
    std::string sceneLens;
    if (clOk(api.read(queue, scalar<void *>(args[17]), 1, 0, sizeof(ccm), ccm, 0, nullptr, nullptr), "lite read CCM")) {
        sceneMatched = masterColorSceneOfFrame(ccm, sceneCct, sceneLens);
    }
    const float focalMm = softLightFocalMm();
    const bool wantVig = masterColorMasterMode() && masterColorNativeVignette()
                         && masterColorFilmPhysicalVignette(look, params) && focalMm > 0.0f;
    if (!wantVig) return false;
    std::lock_guard<std::mutex> lock(mutex);
    if (liteProgramFailed) return false;
    void *context = nullptr;
    if (!clOk(api.queueInfo(queue, 0x1090, sizeof(context), &context, nullptr), "lite queue context")) return false;
    auto &program = litePrograms[context];
    if (!program) {
        program = buildLiteProgram(api, context, queue);
        if (!program) {
            litePrograms.erase(context);
            liteProgramFailed = true;
            return false;
        }
        filmTrace("lite vignette kernel built");
    }
    const int width = int(global[0]), height = scalar<int>(args[30]);
    float sx = 0.0f, sy = 0.0f;
    computeVignetteScales(width, height, focalMm, sx, sy);
    const float vigGain = computeVigGain(sx, sy, look, params);
    const float vigParam[4] = {sx, sy, vigGain, 0.0f};
    bool ok = true;
    for (unsigned i = 0; i < 33 && ok; ++i) {
        ok = clOk(api.setArg(program->kernel, i, args[i].size, args[i].value), "lite set arg");
    }
    if (ok) ok = clOk(api.setArg(program->kernel, 33, sizeof(vigParam), vigParam), "lite vig param arg");
    if (ok) ok = clOk(api.enqueue(queue, program->kernel, dim, offset, global, local, 0, nullptr, nullptr), "lite enqueue");
    if (ok && sync) ok = clOk(api.finish(queue), "lite finish");
    if (ok) filmTrace("lite vignette %dx%d focal=%.1fmm scale=(%.4f, %.4f) gain=%.3f", width, height, focalMm, sx, sy, vigGain);
    return ok;
}

bool filmProProcRun(void *engine, const std::string &name, const CaptureKernelArgs &builder, unsigned dim, const size_t *offset, const size_t *global,
                    const size_t *local, bool sync) {
    if (name == kLiteName) return filmProProcRunLite(engine, builder, dim, offset, global, local, sync);
    using namespace clapi;
    const Arg *args = builder.begin;
    if (builder.end - builder.begin != 43 || dim != 2 || !global) return false;
    std::string look;
    float toneWeight = 1, colourWeight = 1;
    mastercolor::Params params;
    const bool hasCustomLook = masterColorLook(look, toneWeight, colourWeight, params);
    const bool monoPan = monoPanSelected();
    const bool filmFull = !monoPan && hasCustomLook && look.rfind("film_", 0) == 0 && toneWeight >= 1.0f && colourWeight >= 1.0f;
    float ccm[9];
    Api api(engine);
    void *queue = field<void *>(engine, 0x50);
    if (!clOk(api.read(queue, scalar<void *>(args[17]), 1, 0, sizeof(ccm), ccm, 0, nullptr, nullptr), "read CCM")) return false;
    int sceneCct = 0;
    std::string sceneLens;
    const bool sceneMatched = masterColorSceneOfFrame(ccm, sceneCct, sceneLens);
    const float focalMm = softLightFocalMm();
    const bool wantVig = masterColorMasterMode() && masterColorNativeVignette()
                         && masterColorFilmPhysicalVignette(look, params) && !monoPan && focalMm > 0.0f;
    if (!filmFull && !wantVig) return false;
    std::lock_guard<std::mutex> lock(mutex);
    if (programFailed) return false;
    void *context = nullptr;
    if (!clOk(api.queueInfo(queue, 0x1090, sizeof(context), &context, nullptr), "queue context")) return false;
    auto &program = programs[context];
    if (!program) {
        program = buildProgram(api, context, queue);
        if (!program) {
            programs.erase(context);
            programFailed = true;
            return false;
        }
        filmTrace("film kernels built");
    }
    const int width = int(global[0]), height = scalar<int>(args[30]);
    const bool luxBlend = scalar<int>(args[16]) > 280;
    std::string selection = look;
    for (const auto &p : params) selection += "|" + p.first + "=" + std::to_string(p.second);
    const int focalDeciMm = wantVig ? int(std::lround(focalMm * 10.0f)) : 0;
    selection += "|vig=" + std::to_string(int(wantVig)) + "|f=" + std::to_string(focalDeciMm)
               + "|full=" + std::to_string(int(filmFull));
    std::array<int, 9> ccmKey{};
    for (int i = 0; i < 9; i++) ccmKey[i] = int(std::lround(ccm[i] * 2000.0));
    std::shared_ptr<const Plan> plan = lastPlan;
    if (!plan || plan->selection != selection || plan->ccm != ccmKey || plan->luxBlend != luxBlend || plan->width != width ||
        plan->height != height) {
        auto built = std::make_shared<Plan>();
        built->selection = selection;
        built->ccm = ccmKey;
        built->luxBlend = luxBlend;
        built->width = width;
        built->height = height;
        if (filmFull) {
            mastercolor::Key k;
            k.path = mastercolor::Path::Main;
            if (!sceneMatched) {
                // no frame with this CCM has had its LMT chosen: its colour temperature and lens are not known here, so the film's effects are not laid
                filmTrace("no scene data for CCM %.3f %.3f %.3f ...: %s", ccm[0], ccm[1], ccm[2], wantVig ? "vignette only" : "stock");
                if (!wantVig) return false;  // not remembered: the frame's LMT may still be chosen
            } else {
                k.cct = sceneCct;
                k.lens = sceneLens;
                std::copy(ccm, ccm + 9, k.ccm.begin());
                k.luxBlend = luxBlend;
                k.look = look;
                k.params = params;
                const auto start = std::chrono::steady_clock::now();
                const auto boost = params.find("boost");
                const int level = boost == params.end() || !(boost->second >= 0.5f) ? 0 : std::min(int(boost->second + 0.5f), 2);
                built->boost = kBoostProfiles[level];
                mastercolor::filmPlans(k, width, height, &built->grain, built->hasGrain, &built->halo, built->hasHalo);
                filmTrace("%s %dx%d cct %d lens %s: grain %d halation %d (cell %d m00 %.4f) in %lld ms", look.c_str(), width, height, k.cct, k.lens.c_str(),
                          int(built->hasGrain), int(built->hasHalo), built->halo.cell, built->halo.matrix[0][0],
                          (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
            }
        }
        if (wantVig) {
            built->hasVignette = true;
            computeVignetteScales(width, height, focalMm, built->vigScaleX, built->vigScaleY);
            built->vigGain = computeVigGain(built->vigScaleX, built->vigScaleY, look, params);
            filmTrace("vignette %dx%d focal=%.1fmm scale=(%.4f, %.4f) gain=%.3f", width, height, focalMm,
                      built->vigScaleX, built->vigScaleY, built->vigGain);
        }
        lastPlan = built;
        plan = built;
        frame.reset();
    }
    if (!plan->hasGrain && !plan->hasHalo && !plan->hasVignette) return false;
    const size_t alignY = (local && local[1] > 0) ? local[1] : 16;
    const size_t maxBytesRows = std::max<size_t>(16, (size_t(64) * 1024u * 1024u) / (size_t(std::max(width, 1)) * 8u));
    const size_t rowStep = std::max(alignY, (maxBytesRows / alignY) * alignY);
    // a new frame: its destination changed or its first tile arrives
    void *dst = scalar<void *>(args[28]);
    const int firstRow = int(offset ? offset[1] : 0) + scalar<int>(args[29]);
    if (!frame.parsMem || dst != frame.dst || firstRow == 0) {
        frame.reset();
        float stock[15];
        if (!clOk(api.read(queue, scalar<void *>(args[4]), 1, 0, sizeof(stock), stock, 0, nullptr, nullptr), "read pars")) return false;
        const uint32_t seed = uint32_t(std::chrono::steady_clock::now().time_since_epoch().count() * 2654435761u) + 977u * (++counter);
        const auto start = std::chrono::steady_clock::now();
        if (!makeFrame(api, context, *plan, stock, width, height, seed, int(rowStep), frame)) return false;
        frame.dst = dst;
        if (plan->hasHalo && !makeReturnedLight(api, queue, *program, *plan, frame, args, dim)) {
            filmTrace("returned light failed");
            frame.reset();
            return false;
        }
        filmTrace("frame %dx%d seed %u: returned light queued in %lld ms", width, height, seed,
                  (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
    }
    const uint32_t seed = frame.seed;
    const size_t chunkMax = plan->hasGrain ? std::min(rowStep, size_t(std::max(frame.noiseRows, 1))) : global[1];
    void *gridArg = plan->hasHalo ? frame.gridMem : frame.dummyMem;
    void *noiseArg = plan->hasGrain ? frame.noiseMem : frame.dummyMem;
    bool ok = true;
    for (size_t yOff = 0; yOff < global[1] && ok; ) {
        const size_t chunkH = std::min(global[1] - yOff, chunkMax);
        const int noiseY0 = firstRow + int(yOff);
        if (plan->hasGrain) {
            const size_t ntGlobal[2] = {size_t(((width + 15) / 16) * 16), size_t(((int(chunkH) + 15) / 16) * 16)};
            const size_t ntLocal[2] = {16, 16};
            const int pops = int(std::min<size_t>(plan->grain.populations.size(), 2));
            ok = ok && clOk(api.setArg(program->noiseTile, 0, sizeof(frame.noiseMem), &frame.noiseMem), "nt out") &&
                 setInt(api, program->noiseTile, 1, width) && setInt(api, program->noiseTile, 2, height) &&
                 setInt(api, program->noiseTile, 3, noiseY0) &&
                 clOk(api.setArg(program->noiseTile, 4, sizeof(frame.parsMem), &frame.parsMem), "nt pars") &&
                 clOk(api.setArg(program->noiseTile, 5, sizeof(frame.icdfMem), &frame.icdfMem), "nt icdf") &&
                 clOk(api.setArg(program->noiseTile, 6, sizeof(seed), &seed), "nt seed") &&
                 setInt(api, program->noiseTile, 7, pops) &&
                 clOk(api.enqueue(queue, program->noiseTile, 2, nullptr, ntGlobal, ntLocal, 0, nullptr, nullptr), "nt enqueue");
        }
        for (unsigned i = 0; i < 43 && ok; ++i)
            ok = clOk(api.setArg(program->tile, i, i == 4 ? sizeof(frame.parsMem) : args[i].size,
                                 i == 4 ? static_cast<const void *>(&frame.parsMem) : args[i].value),
                      "film set arg");
        ok = ok && clOk(api.setArg(program->tile, 43, sizeof(gridArg), &gridArg), "film grid arg") &&
             clOk(api.setArg(program->tile, 44, sizeof(noiseArg), &noiseArg), "film noise arg") &&
             setInt(api, program->tile, 45, noiseY0);
        const size_t chunkGlobal[2] = {global[0], chunkH};
        const size_t chunkOffset[2] = {offset ? offset[0] : 0, (offset ? offset[1] : 0) + yOff};
        const size_t *useOffset = (offset || yOff > 0) ? chunkOffset : nullptr;
        if (ok) ok = clOk(api.enqueue(queue, program->tile, dim, useOffset, chunkGlobal, local, 0, nullptr, nullptr), "film enqueue");
        yOff += chunkH;
    }
    if (ok && sync) ok = clOk(api.finish(queue), "film finish");
    return ok;
}
