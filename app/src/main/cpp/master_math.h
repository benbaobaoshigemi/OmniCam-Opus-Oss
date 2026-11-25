// Shared maths and binary readers of the master colour baker and its style plugins.
#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace mastercolor {
namespace detail {

using Mat3 = std::array<double, 9>;
using Vec3 = std::array<double, 3>;

inline Mat3 mul(const Mat3 &a, const Mat3 &b) {
    Mat3 r{};
    for (int i = 0; i < 3; i++)
        for (int j = 0; j < 3; j++)
            r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
    return r;
}

inline Mat3 inv(const Mat3 &m) {
    double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    double det = a * A + b * B + c * C;
    return {A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
            B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
            C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
}

inline Vec3 xform(const Mat3 &m, const Vec3 &v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}

inline Mat3 diag(double a, double b, double c) { return {a, 0, 0, 0, b, 0, 0, 0, c}; }

const Mat3 kP3ToXyz = {0.4865709, 0.2656677, 0.1982173, 0.2289746, 0.6917385, 0.0792869,
                       0.0000000, 0.0451134, 1.0439444};
const Mat3 kBradford = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
const Vec3 kD65 = {0.95047, 1.0, 1.08883};
const Vec3 kD50 = {0.96422, 1.0, 0.82521};

const Vec3 kP3Luma = {0.2289746, 0.6917385, 0.0792869};
const Vec3 kW601 = {0.299, 0.587, 0.114};

inline Mat3 cat(const Vec3 &src, const Vec3 &dst) {
    auto s = xform(kBradford, src), d = xform(kBradford, dst);
    return mul(mul(inv(kBradford), diag(d[0] / s[0], d[1] / s[1], d[2] / s[2])), kBradford);
}

// Scene white: Planckian locus below 4000 K, CIE daylight locus from 4000 K (hncs_engine.white_xyz).
inline Vec3 whiteXyz(double t) {
    double x, y;
    if (t < 4000) {
        x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        y = t < 2222 ? -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                     : -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    } else {
        x = t <= 7000 ? -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 0.09911e3 / t + 0.244063
                      : -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 0.24748e3 / t + 0.237040;
        y = -3.0 * x * x + 2.87 * x - 0.275;
    }
    return {x / y, 1.0, (1 - x - y) / y};
}

struct Anchor {
    float temperature;
    std::vector<float> values;
};

template <typename T>
inline bool readValue(const std::vector<uint8_t> &blob, size_t &at, T &out) {
    if (at + sizeof(T) > blob.size()) return false;
    std::memcpy(&out, blob.data() + at, sizeof(T));
    at += sizeof(T);
    return true;
}

inline bool readFloats(const std::vector<uint8_t> &blob, size_t &at, size_t count, std::vector<float> &out) {
    if (at + count * 4 > blob.size()) return false;
    out.resize(count);
    std::memcpy(out.data(), blob.data() + at, count * 4);
    at += count * 4;
    return true;
}

inline bool readMat(const std::vector<uint8_t> &blob, size_t &at, Mat3 &m) {
    std::vector<float> v;
    if (!readFloats(blob, at, 9, v)) return false;
    for (int i = 0; i < 9; i++) m[i] = v[i];
    return true;
}

inline std::vector<double> interpolate(const std::vector<Anchor> &anchors, double t) {
    std::vector<const Anchor *> sorted;
    for (auto &a : anchors) sorted.push_back(&a);
    std::sort(sorted.begin(), sorted.end(), [](auto *a, auto *b) { return a->temperature < b->temperature; });
    auto pick = [](const Anchor *a) { return std::vector<double>(a->values.begin(), a->values.end()); };
    if (t <= sorted.front()->temperature) return pick(sorted.front());
    if (t >= sorted.back()->temperature) return pick(sorted.back());
    for (size_t i = 0; i + 1 < sorted.size(); i++) {
        double t0 = sorted[i]->temperature, t1 = sorted[i + 1]->temperature;
        if (t >= t0 && t <= t1) {
            double w = (t - t0) / (t1 - t0);
            std::vector<double> r(sorted[i]->values.size());
            for (size_t j = 0; j < r.size(); j++) r[j] = sorted[i]->values[j] * (1 - w) + sorted[i + 1]->values[j] * w;
            return r;
        }
    }
    return pick(sorted.back());
}

inline double srgbEncode(double x) {
    x = std::max(x, 0.0);
    return x < 0.0030412825601275209 ? 12.92 * x : 1.0550107189475866 * std::pow(x, 1 / 2.4) - 0.0550107189475866;
}

const Mat3 kProPhotoToXyz = {0.7976749, 0.1351917, 0.0313534, 0.2880402, 0.7118741, 0.0000857,
                             0.0, 0.0, 0.8252100};
// From the BT.2020 primaries and D65 white (as ColorSpace.BT2020 / DngSdkColorSpec).
const Mat3 kBt2020ToXyz = {0.6369580483, 0.1446169036, 0.1688809752, 0.2627002120, 0.6779980715, 0.0593017165,
                           0.0000000000, 0.0280726930, 1.0609850577};
const Mat3 kSrgbToXyz = {0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750,
                         0.0193339, 0.1191920, 0.9503041};

inline bool readInts(const std::vector<uint8_t> &blob, size_t &at, size_t count, std::vector<int32_t> &out) {
    if (at + count * 4 > blob.size()) return false;
    out.resize(count);
    std::memcpy(out.data(), blob.data() + at, count * 4);
    at += count * 4;
    return true;
}

inline float halfToFloat(uint16_t h) {
    uint32_t sign = uint32_t(h & 0x8000) << 16, exp = (h >> 10) & 0x1f, man = h & 0x3ff, bits;
    if (exp == 0) {
        if (man == 0) {
            bits = sign;
        } else {
            exp = 127 - 15 + 1;
            while (!(man & 0x400)) {
                man <<= 1;
                exp--;
            }
            bits = sign | (exp << 23) | ((man & 0x3ff) << 13);
        }
    } else if (exp == 31) {
        bits = sign | 0x7f800000 | (man << 13);
    } else {
        bits = sign | ((exp + 127 - 15) << 23) | (man << 13);
    }
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
}

inline bool readHalves(const std::vector<uint8_t> &blob, size_t &at, size_t count, std::vector<float> &out) {
    if (at + count * 2 > blob.size()) return false;
    out.resize(count);
    for (size_t i = 0; i < count; i++) {
        uint16_t v;
        std::memcpy(&v, blob.data() + at + i * 2, 2);
        out[i] = halfToFloat(v);
    }
    at += count * 2;
    return true;
}

inline bool readUnorm16(const std::vector<uint8_t> &blob, size_t &at, size_t count, std::vector<float> &out) {
    if (at + count * 2 > blob.size()) return false;
    out.resize(count);
    for (size_t i = 0; i < count; i++) {
        uint16_t v;
        std::memcpy(&v, blob.data() + at + i * 2, 2);
        out[i] = v / 65535.0f;
    }
    at += count * 2;
    return true;
}

// Trilinear lookup of an n^3 [B][G][R][3] table at v (clamped to [0, 1]).
inline Vec3 cube(const std::vector<float> &t, int n, const Vec3 &v) {
    double p[3], f[3];
    int i0[3];
    for (int c = 0; c < 3; c++) {
        p[c] = std::min(std::max(v[c], 0.0), 1.0) * (n - 1);
        i0[c] = std::min(int(std::floor(p[c])), n - 2);
        f[c] = p[c] - i0[c];
    }
    Vec3 out = {0, 0, 0};
    for (int db = 0; db < 2; db++)
        for (int dg = 0; dg < 2; dg++)
            for (int dr = 0; dr < 2; dr++) {
                double w = (dr ? f[0] : 1 - f[0]) * (dg ? f[1] : 1 - f[1]) * (db ? f[2] : 1 - f[2]);
                size_t at = ((size_t(i0[2] + db) * n + (i0[1] + dg)) * n + (i0[0] + dr)) * 3;
                for (int c = 0; c < 3; c++) out[c] += w * t[at + c];
            }
    return out;
}

// ---------------------------------------------------------------- Adobe HueSatMap (DNG profile) on ProPhoto light
inline void dcpRgbToHsv(const Vec3 &c, double hsv[3]) {
    double mx = std::max(c[0], std::max(c[1], c[2])), mn = std::min(c[0], std::min(c[1], c[2])), d = mx - mn;
    double h = 0;
    if (d > 1e-6) {
        if (mx == c[0]) h = std::fmod((c[1] - c[2]) / d, 6.0), h = h < 0 ? h + 6.0 : h;
        else if (mx == c[1]) h = (c[2] - c[0]) / d + 2.0;
        else h = (c[0] - c[1]) / d + 4.0;
    }
    hsv[0] = h, hsv[1] = mx > 1e-6 ? d / mx : 0.0, hsv[2] = mx;
}

inline double glslMod(double x, double y) { return x - y * std::floor(x / y); }

inline Vec3 dcpHsvToRgb(const double hsv[3]) {
    double h = glslMod(hsv[0], 6.0), s = std::max(hsv[1], 0.0), v = std::max(hsv[2], 0.0);
    double c = v * s, x = c * (1.0 - std::fabs(glslMod(h, 2.0) - 1.0)), m = v - c;
    Vec3 rgb;
    if (h < 1.0) rgb = {c, x, 0};
    else if (h < 2.0) rgb = {x, c, 0};
    else if (h < 3.0) rgb = {0, c, x};
    else if (h < 4.0) rgb = {0, x, c};
    else if (h < 5.0) rgb = {x, 0, c};
    else rgb = {c, 0, x};
    return {rgb[0] + m, rgb[1] + m, rgb[2] + m};
}

inline Vec3 dcpSampleDims(int vd, int hd, int sd, const std::vector<float> &t, const double hsv[3]) {
    double hs = hsv[0] * hd / 6.0, ss = hsv[1] * std::max(sd - 1, 0), vs = hsv[2] * std::max(vd - 1, 0);
    int h0 = int(std::floor(hs)), s0 = std::min(int(std::floor(ss)), std::max(sd - 2, 0));
    int v0 = std::min(int(std::floor(vs)), std::max(vd - 2, 0)), h1 = h0 + 1;
    if (h0 >= hd - 1) h0 = hd - 1, h1 = 0;
    double hf = hs - h0, sf = ss - s0, vf = vs - v0;
    int s1 = std::min(s0 + 1, sd - 1), v1 = std::min(v0 + 1, vd - 1);
    auto at = [&](int v, int h, int s, int c) { return double(t[((size_t(v) * hd + h) * sd + s) * 3 + c]); };
    Vec3 out;
    for (int c = 0; c < 3; c++) {
        double a000 = at(v0, h0, s0, c), a001 = at(v0, h1, s0, c), a010 = at(v0, h0, s1, c), a011 = at(v0, h1, s1, c);
        if (vd > 1) {
            a000 = a000 * (1 - vf) + at(v1, h0, s0, c) * vf;
            a001 = a001 * (1 - vf) + at(v1, h1, s0, c) * vf;
            a010 = a010 * (1 - vf) + at(v1, h0, s1, c) * vf;
            a011 = a011 * (1 - vf) + at(v1, h1, s1, c) * vf;
        }
        double e0 = a000 * (1 - hf) + a001 * hf, e1 = a010 * (1 - hf) + a011 * hf;
        out[c] = e0 * (1 - sf) + e1 * sf;
    }
    return out;
}

// The profile's HueSatMap (one value division, table [hue][sat][3]) on linear ProPhoto: hue shift, saturation and value scale.
inline Vec3 hueSat(int hd, int sd, const std::vector<float> &table, const Vec3 &pp) {
    double hsv[3];
    dcpRgbToHsv(pp, hsv);
    const double lookup[3] = {hsv[0], hsv[1], 0.0};
    const Vec3 mod = dcpSampleDims(1, hd, sd, table, lookup);
    hsv[0] = glslMod(hsv[0] + mod[0] * 6.0 / 360.0, 6.0);
    hsv[1] = std::min(std::max(hsv[1] * mod[1], 0.0), 1.0);
    hsv[2] = std::max(hsv[2] * mod[2], 0.0);
    return dcpHsvToRgb(hsv);
}

// The colorimetric scene a with hueSat(a) = s: fixed point on the map's hue shift and scales (the least saturated a where the
// map saturates). tools/mastercolor/bake_adobe_transfer.py is the reference.
inline Vec3 inverseHueSat(int hd, int sd, const std::vector<float> &table, const Vec3 &s) {
    const Vec3 target = {std::max(s[0], 0.0), std::max(s[1], 0.0), std::max(s[2], 0.0)};
    double goal[3];
    dcpRgbToHsv(target, goal);
    Vec3 a = target;
    for (int round = 0; round < 16; ++round) {
        double ha[3], hf[3];
        dcpRgbToHsv(a, ha);
        dcpRgbToHsv(hueSat(hd, sd, table, a), hf);
        const double dh = glslMod(hf[0] - ha[0] + 3.0, 6.0) - 3.0;
        double ms = ha[1] > 1e-4 ? hf[1] / std::max(ha[1], 1e-4) : 1.0;
        if (!(ms > 1e-3)) ms = 1.0;
        double mv = ha[2] > 1e-6 ? hf[2] / std::max(ha[2], 1e-6) : 1.0;
        if (!(mv > 1e-3)) mv = 1.0;
        const double next[3] = {goal[0] - dh, std::min(std::max(goal[1] / ms, 0.0), 1.0), goal[2] / mv};
        a = dcpHsvToRgb(next);
    }
    return a;
}

inline double srgbDecode(double e) { return e < 0.04045 ? e / 12.92 : std::pow((e + 0.055) / 1.055, 2.4); }
inline double srgbEncodeLin(double x) {
    x = std::clamp(x, 0.0, 1.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * std::pow(x, 1.0 / 2.4) - 0.055;
}

// HNCS full-range HDR peak in linear SDR-white units (1.0 = 203 nit SDR white, 10.23 = Phocus HDR max).
constexpr double kHncsHdrPeak = 10.23;

inline double hlgOetf(double e) {
    e = std::clamp(e, 0.0, 1.0);
    if (e <= 1.0 / 12.0) return std::sqrt(3.0 * e);
    constexpr double a = 0.17883277, b = 1.0 - 4.0 * a, c = 0.55991073;
    return a * std::log(12.0 * e - b) + c;
}

inline double hlgInv(double h) {
    h = std::clamp(h, 0.0, 1.0);
    if (h <= 0.5) return (h * h) / 3.0;
    constexpr double a = 0.17883277, b = 1.0 - 4.0 * a, c = 0.55991073;
    return (std::exp((h - c) / a) + b) / 12.0;
}

// Smooth C1 highlight shoulder compression above yk toward maxOut (default 1.0).
inline double shoulderC1(double y, double yk, double maxOut = 1.0) {
    y = std::max(y, 0.0);
    if (y <= yk) return y;
    double u = y - yk, r = maxOut - yk;
    return yk + r * (u / (u + r));
}

// Preserves luma and hue when bringing out-of-gamut P3 RGB inside [0, maxVal].
inline Vec3 pullToP3Gamut(Vec3 rgb, double maxVal = 1.0) {
    double y = rgb[0] * kP3Luma[0] + rgb[1] * kP3Luma[1] + rgb[2] * kP3Luma[2];
    if (y <= 0.0) return {0.0, 0.0, 0.0};
    if (y >= maxVal) return {maxVal, maxVal, maxVal};
    double mn = std::min({rgb[0], rgb[1], rgb[2]});
    if (mn < 0.0) {
        double s = y / std::max(y - mn, 1e-12);
        for (int c = 0; c < 3; ++c) rgb[c] = y + (rgb[c] - y) * s;
    }
    double mx = std::max({rgb[0], rgb[1], rgb[2]});
    if (mx > maxVal) {
        double s = std::max(maxVal - y, 0.0) / std::max(mx - y, 1e-12);
        for (int c = 0; c < 3; ++c) rgb[c] = y + (rgb[c] - y) * s;
    }
    for (int c = 0; c < 3; ++c) rgb[c] = std::clamp(rgb[c], 0.0, maxVal);
    return rgb;
}
inline double ppEncode(double x) {
    x = std::max(x, 0.0);
    return x >= 0.001953125 ? std::pow(x, 1 / 1.8) : 16 * x;
}
inline double ppDecode(double x) {
    x = std::min(std::max(x, 0.0), 1.0);
    return x >= 0.03125 ? std::pow(x, 1.8) : x / 16;
}

// Linear ProPhoto (D50) -> white-balanced camera RGB at the scene white, for a camera whose ColorMatrix pair is
// (cm1 at il1 Kelvin, cm2 at il2 Kelvin) interpolated in inverse Kelvin.
inline Mat3 prophotoToCamera(float il1, float il2, const Mat3 &cm1, const Mat3 &cm2, double cct) {
    double g = std::min(std::max((1 / cct - 1 / double(il1)) / (1 / double(il2) - 1 / double(il1)), 0.0), 1.0);
    Mat3 cm;
    for (int i = 0; i < 9; i++) cm[i] = cm1[i] * (1 - g) + cm2[i] * g;
    Vec3 white = whiteXyz(cct);
    Vec3 camWhite = xform(cm, white);
    Mat3 camToXyz = mul(mul(cat(white, kD50), inv(cm)), diag(camWhite[0], camWhite[1], camWhite[2]));
    return mul(inv(camToXyz), kProPhotoToXyz);
}

// Look and table names are file names: lower case, digits, underscore.
inline bool validLookName(const std::string &name) {
    if (name.empty() || name.size() > 64) return false;
    for (char c : name)
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return false;
    return true;
}

// Whole file; false if it cannot be opened.
inline bool readFile(const std::string &path, std::vector<uint8_t> &blob) {
    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    uint8_t chunk[65536];
    size_t got;
    blob.clear();
    while ((got = std::fread(chunk, 1, sizeof(chunk), file)) > 0) blob.insert(blob.end(), chunk, chunk + got);
    std::fclose(file);
    return true;
}

}  // namespace detail
}  // namespace mastercolor
