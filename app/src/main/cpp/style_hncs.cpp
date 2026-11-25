// Hasselblad HNCS: Phocus 4.1.1 HasselbladRGB + BT.601 2D colour map, HDR film curve, HighlightStrength(50,50), CIE L*
// preset curves/saturation, and Display P3 C1 highlight shoulder + gamut preservation.
// Look "hncs" comes from hncs_engine.bin; looks/hncs_<id>.bin (kind 9) supply variants. tools/mastercolor/hncs_engine.py is the reference.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

constexpr double kHdrMaxGain = 10000.0 / 203.0;
constexpr double kGammaHass = 563.0 / 256.0;
constexpr uint32_t kFlagCompandingGamma = 1u << 0;
constexpr uint32_t kFlagIsReproduction = 1u << 1;
constexpr uint32_t kFlagHighlightStrength = 1u << 2;
constexpr uint32_t kFlagHasGradation = 1u << 3;

const Mat3 kXyzD50ToHass = {1.768049, -0.490119, -0.260191,
                             -0.805970, 1.750069, 0.032788,
                             0.034066, -0.093962, 1.286342};
constexpr double kKr601 = 0.2989, kKg601 = 0.5866, kKb601 = 0.1145;
const Mat3 kRgbToYcc = {kKr601, kKg601, kKb601,
                        -kKr601 / (2.0 * (1.0 - kKb601)), -kKg601 / (2.0 * (1.0 - kKb601)), 0.5,
                        0.5, -kKg601 / (2.0 * (1.0 - kKr601)), -kKb601 / (2.0 * (1.0 - kKr601))};

inline double hdrRgbLimit() {
    static const double v = std::pow(kHdrMaxGain, 1.0 / kGammaHass);
    return v;
}

inline double fwdLStar(double x) {
    return x <= (216.0 / 24389.0) ? x * (24389.0 / 2700.0) : 1.16 * std::cbrt(std::max(x, 0.0)) - 0.16;
}

inline double invLStar(double t) {
    return t <= 0.08 ? t * (2700.0 / 24389.0) : std::pow((t + 0.16) / 1.16, 3.0);
}

inline double eval1d(const std::vector<float> &tbl, double x) {
    double p = std::clamp(x, 0.0, 1.0) * double(tbl.size() - 1);
    int i0 = std::min(int(std::floor(p)), int(tbl.size()) - 2);
    double f = p - i0;
    return tbl[i0] * (1.0 - f) + tbl[i0 + 1] * f;
}

void buildHdrFilmCurve(const std::vector<float> &film, bool compandingGamma, bool isRepro, std::vector<float> &out) {
    out.resize(65536);
    if (isRepro) {
        for (int i = 0; i < 65536; ++i) out[i] = float(double(i) / 65535.0);
        return;
    }
    std::vector<double> baseLin(65536);
    for (int i = 0; i < 65536; ++i) {
        double v = film[i];
        baseLin[i] = compandingGamma ? std::pow(v, kGammaHass) : invLStar(v);
    }
    constexpr double n = 65535.0;
    const double xStop = n / std::sqrt(2.0);
    std::vector<double> curve(65536, 0.0);
    int joinI = 0;
    double mJoin = 1.0, bJoin = 0.0;
    for (int i = 0; i < 65536; ++i) {
        double pos = i * kHdrMaxGain;
        if (pos >= 65535.0) break;
        int lo = int(pos);
        int hi = std::min(lo + 1, 65535);
        double f = pos - lo;
        curve[i] = baseLin[lo] * (1.0 - f) + baseLin[hi] * f;
        joinI = i;
        if (pos >= 20000.0 && lo >= 20) {
            int r8 = lo - 20;
            double m = (baseLin[lo] - baseLin[r8]) / 20.0;
            double b = baseLin[r8] - m * r8;
            if (m * xStop + b < 1.0) {
                mJoin = m;
                bJoin = b;
                break;
            }
        }
    }
    for (int i = joinI + 1; i < 65536; ++i) curve[i] = mJoin * (i * kHdrMaxGain) + bJoin;
    int x1 = std::max(10, joinI);
    double y1 = curve[x1];
    double m1 = (curve[x1 + 10] - curve[x1 - 10]) / 20.0;
    double x2 = n / kHdrMaxGain;
    if (x2 > x1 + 2) {
        double dx = x2 - x1;
        double yt = std::sqrt(2.0);
        double a = (yt - y1 - m1 * dx) / (dx * dx);
        double b = m1 - 2.0 * a * x1;
        double c = y1 - a * x1 * x1 - b * x1;
        double mEnd = 2.0 * a * x2 + b;
        for (int i = x1; i < 65536; ++i) {
            curve[i] = i <= x2 ? (a * i * i + b * i + c) : (yt + mEnd * (i - x2));
        }
    }
    for (int i = 0; i < 65536; ++i) out[i] = float(curve[i] / kHdrMaxGain);
}

const std::vector<float> &defaultHdrFilm(const EngineData &d) {
    static std::once_flag once;
    static std::vector<float> cache;
    std::call_once(once, [&] { buildHdrFilmCurve(d.film, false, false, cache); });
    return cache;
}

Vec3 rgb2Hsl(const Vec3 &rgb) {
    double r = rgb[0], g = rgb[1], b = rgb[2];
    double mn = std::min({r, g, b}), mx = std::max({r, g, b});
    double l = 0.5 * (mx + mn), d = mx - mn;
    if (d <= 1e-12 || l <= 1e-12 || l >= 1.0 - 1e-12) return {0.0, 0.0, l};
    double s = std::clamp(l <= 0.5 ? 0.5 * d / l : 0.5 * d / (1.0 - l), 0.0, 1.0);
    double h = 0.0;
    if (mx == r) {
        double raw = std::fmod((g - b) / d, 6.0);
        if (raw < 0.0) raw += 6.0;
        h = 60.0 * raw;
    } else if (mx == g) {
        h = 60.0 * ((b - r) / d + 2.0);
    } else {
        h = 60.0 * ((r - g) / d + 4.0);
    }
    if (h >= 360.0) h -= 360.0;
    return {h, s, l};
}

Vec3 hsl2Rgb(const Vec3 &hsl) {
    double h = hsl[0], s = hsl[1], l = hsl[2];
    if (s <= 1e-12) return {l, l, l};
    double q = l <= 0.5 ? l * (1.0 + s) : l + s - l * s;
    double p = 2.0 * l - q;
    double hk = h / 360.0;
    hk -= std::floor(hk);
    auto ch = [&](double t) {
        t -= std::floor(t);
        if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
        if (t < 0.5) return q;
        if (t < 2.0 / 3.0) return p + (q - p) * 6.0 * (2.0 / 3.0 - t);
        return p;
    };
    return {ch(hk + 1.0 / 3.0), ch(hk), ch(hk - 1.0 / 3.0)};
}

inline Vec3 recoverHue(const Vec3 &base, const Vec3 &curved) {
    Vec3 hb = rgb2Hsl(base), hc = rgb2Hsl(curved);
    return hsl2Rgb({hb[0], hc[1], hc[2]});
}

inline double contrastS6(double x) {
    if (x < 0.5) return 2.0 * x * x - x;
    if (x < 0.9267767) return 3.0 * x - 2.0 * x * x - 1.0;
    if (x < 1.103553) return 2.0 * x * x - (3.0 + std::sqrt(2.0)) * x + 2.43566;
    return 0.0;
}

Vec3 applyHighlightStrength(const Vec3 &rgbHdrNorm) {
    constexpr double kStrength = 50.0, kDesat = 50.0;
    constexpr double x1 = 0.0083466849774929425, x2 = 0.0203;
    static const double y2 = std::pow(2.0, 0.6) * x2;
    static const double pA = (y2 - x1 - 0.01195331502250706) / 0.00014288174002729295;
    static const double pB = 1.0 - 2.0 * pA * x1;
    static const double pC = x1 - pA * x1 * x1 - pB * x1;
    constexpr double c0 = 0.020284740745262004;
    static const double xmm4 = pA * c0 * c0 + pB * c0 + pC;
    static const double lA = (y2 - xmm4) / 1.5259254737998162e-05;
    static const double phA = ((1.0 - y2) - 0.9797 * lA) / 0.95981209;
    static const double phB = lA - 2.0 * phA * x2;
    static const double phC = y2 - phA * x2 * x2 - phB * x2;
    constexpr double sb = kStrength * (kStrength * 4.5e-5 + 0.01);
    constexpr double asf = 1.0 - sb;
    constexpr double satB = kDesat / 100.0;

    Vec3 x{}, rgbHs{};
    for (int c = 0; c < 3; ++c) {
        double xc = std::clamp(rgbHdrNorm[c], 0.0, 1.0);
        x[c] = xc;
        double y = xc < x1 ? xc : (xc < x2 ? (pA * xc * xc + pB * xc + pC) : (phA * xc * xc + phB * xc + phC));
        rgbHs[c] = std::clamp(y, 0.0, 1.0);
    }
    Vec3 hi = rgb2Hsl(x), ho = rgb2Hsl(rgbHs);
    double a = ho[1] - hi[1];
    double b = hi[1] - 0.5 * a * asf;
    return hsl2Rgb({hi[0], std::clamp(a * satB + b, 0.0, 1.0), (1.0 - sb) * hi[2] + sb * ho[2]});
}

struct Plan {
    const EngineData *d = nullptr;
    Mat3 p3ToHass{}, hassToP3{}, rgbToYcc{}, yccToRgb{};
    std::vector<float> table;
    const std::vector<float> *hdrFilm = nullptr;
    const std::vector<float> *gradation = nullptr;
    bool applyHs = true;
    double satScale = 1.0, contrast = 0.0, gamma = 1.0;
};

void renderPlan(const Plan &p, const Vec3 &scene, bool hdrFull, float out[3]) {
    const EngineData &d = *p.d;
    Vec3 hass = xform(p.p3ToHass, scene);
    Vec3 ycc = xform(p.rgbToYcc, hass);
    if (ycc[0] > 1e-6) {
        double px = std::clamp(d.divFactor * ycc[1] / ycc[0] - d.cbStart, 0.0, double(d.gw - 1));
        double py = std::clamp(d.divFactor * ycc[2] / ycc[0] - d.crStart, 0.0, double(d.gh - 1));
        int x0 = std::min(std::max(int(std::floor(px)), 0), d.gw - 2);
        int y0 = std::min(std::max(int(std::floor(py)), 0), d.gh - 2);
        double fx = px - x0, fy = py - y0;
        const float *t = p.table.data();
        auto at = [&](int y, int x, int c) { return double(t[(size_t(y) * d.gw + x) * 2 + c]); };
        double mapped[2];
        for (int c = 0; c < 2; ++c) {
            double r0 = at(y0, x0, c) * (1.0 - fx) + at(y0, x0 + 1, c) * fx;
            double r1 = at(y0 + 1, x0, c) * (1.0 - fx) + at(y0 + 1, x0 + 1, c) * fx;
            mapped[c] = r0 * (1.0 - fy) + r1 * fy;
        }
        hass = xform(p.yccToRgb, {ycc[0], mapped[0] * ycc[0] / d.divFactor, mapped[1] * ycc[0] / d.divFactor});
    }
    const double limit = hdrRgbLimit();
    Vec3 perc{};
    for (int c = 0; c < 3; ++c) {
        double hdrNorm = eval1d(*p.hdrFilm, std::max(hass[c], 0.0) / kHdrMaxGain);
        perc[c] = std::clamp(fwdLStar(hdrNorm * kHdrMaxGain) / limit, 0.0, 1.0);
    }
    if (p.gradation || p.gamma != 1.0 || p.contrast != 0.0) {
        Vec3 curved{};
        for (int c = 0; c < 3; ++c) {
            double xSc = std::clamp(perc[c] * limit, 0.0, limit);
            double ySc;
            if (p.gradation) {
                ySc = xSc <= 1.0 ? eval1d(*p.gradation, xSc) : xSc;
            } else {
                double g = std::pow(std::max(xSc, 0.0), 1.0 / p.gamma);
                ySc = g + 0.5 * p.contrast * contrastS6(g);
            }
            curved[c] = std::clamp(ySc / limit, 0.0, 1.0);
        }
        perc = recoverHue(perc, curved);
    }
    if (p.satScale != 1.0) {
        Vec3 hsl = rgb2Hsl(perc);
        hsl[1] = std::clamp(hsl[1] * p.satScale, 0.0, 1.0);
        perc = hsl2Rgb(hsl);
    }
    Vec3 linHdr{};
    for (int c = 0; c < 3; ++c) linHdr[c] = invLStar(perc[c] * limit) / kHdrMaxGain;
    if (p.applyHs) linHdr = applyHighlightStrength(linHdr);
    Vec3 p3Hdr = xform(p.hassToP3, {linHdr[0] * kHdrMaxGain, linHdr[1] * kHdrMaxGain, linHdr[2] * kHdrMaxGain});
    if (hdrFull) {
        Vec3 safe = pullToP3Gamut(p3Hdr, kHncsHdrPeak);
        for (int c = 0; c < 3; ++c) out[c] = float(hlgOetf(safe[c] / kHncsHdrPeak));
        return;
    }
    double y = p3Hdr[0] * kP3Luma[0] + p3Hdr[1] * kP3Luma[1] + p3Hdr[2] * kP3Luma[2];
    if (y <= 0.0) {
        out[0] = out[1] = out[2] = 0.0f;
        return;
    }
    double mn = std::min({p3Hdr[0], p3Hdr[1], p3Hdr[2]});
    if (mn < 0.0) {
        double s = y / std::max(y - mn, 1e-12);
        for (int c = 0; c < 3; ++c) p3Hdr[c] = y + (p3Hdr[c] - y) * s;
    }
    double y2 = shoulderC1(y, 0.35);
    double r = y2 / std::max(y, 1e-12);
    Vec3 disp = pullToP3Gamut({p3Hdr[0] * r, p3Hdr[1] * r, p3Hdr[2] * r});
    for (int c = 0; c < 3; ++c) out[c] = float(disp[c]);
}

void makePlan(const EngineData &d, double cct, Plan &p) {
    p.d = &d;
    double ttab = std::floor(std::min(std::max(cct, 2000.0), double(d.tableMax)));
    std::vector<double> t = interpolate(d.tables, ttab);
    Mat3 hassToXyzD50 = inv(kXyzD50ToHass);
    p.p3ToHass = mul(kXyzD50ToHass, mul(cat(kD65, kD50), kP3ToXyz));
    p.hassToP3 = mul(mul(inv(kP3ToXyz), cat(kD50, kD65)), hassToXyzD50);
    p.rgbToYcc = kRgbToYcc;
    p.yccToRgb = inv(kRgbToYcc);
    p.table.assign(t.begin(), t.end());
    p.hdrFilm = &defaultHdrFilm(d);
    p.gradation = nullptr;
    p.applyHs = false;  //手机端 BasicTone LUT 限 [0, 1]，高光留给后续 HDRTransform 处理
    p.satScale = 1.0;
    p.contrast = 0.0;
    p.gamma = 1.0;
}

struct HncsLook : Look {
    float k = 0.0f;
    uint32_t flags = 0;
    float satScale = 1.0f, contrast = 0.0f, gamma = 1.0f;
    std::vector<float> hdrFilm;
    std::vector<Anchor> tables;
    std::vector<float> gradation;
};

class HncsRenderer : public Renderer {
public:
    std::shared_ptr<const HncsLook> look;
    Plan plan;
    Mat3 ppToP3Scene{};
    double scale = 1.0;

    void render(const Vec3 &pp, float out[3]) const override {
        Vec3 scene = xform(ppToP3Scene, pp);
        renderPlan(plan, {scene[0] * scale, scene[1] * scale, scene[2] * scale}, false, out);
    }

    bool renderHdr(const Vec3 &pp, float out[3]) const override {
        Vec3 scene = xform(ppToP3Scene, pp);
        renderPlan(plan, {scene[0] * scale, scene[1] * scale, scene[2] * scale}, true, out);
        return true;
    }
};

class HncsStyle : public Style {
public:
    const char *id() const override { return "hncs"; }
    bool ownsId(const std::string &look) const override { return look == "hncs"; }
    bool ownsKind(uint32_t kind) const override { return kind == 9; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        const EngineData &engine = engineData();
        auto look = std::make_shared<HncsLook>();
        std::vector<float> film;
        uint32_t count = 0;
        bool ok = readValue(blob, at, look->k) && readValue(blob, at, look->flags) &&
                  readValue(blob, at, look->satScale) && readValue(blob, at, look->contrast) &&
                  readValue(blob, at, look->gamma) && readUnorm16(blob, at, 65536, film) &&
                  readValue(blob, at, count) && count >= 2;
        for (uint32_t i = 0; ok && i < count; ++i) {
            Anchor anchor;
            ok = readValue(blob, at, anchor.temperature) &&
                 readFloats(blob, at, size_t(engine.gw) * engine.gh * 2, anchor.values);
            look->tables.push_back(std::move(anchor));
        }
        if (ok && (look->flags & kFlagHasGradation) != 0) ok = readUnorm16(blob, at, 65536, look->gradation);
        if (!ok || !(look->k > 0.0f) || !(look->gamma > 0.0f)) return nullptr;
        buildHdrFilmCurve(film, (look->flags & kFlagCompandingGamma) != 0,
                          (look->flags & kFlagIsReproduction) != 0, look->hdrFilm);
        return look;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &greyScene) const override {
        const EngineData &d = engineData();
        auto renderer = std::make_unique<HncsRenderer>();
        renderer->ppToP3Scene = mul(mul(inv(kP3ToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        Vec3 grey = xform(renderer->ppToP3Scene, greyScene);
        double greyY = grey[0] * kP3Luma[0] + grey[1] * kP3Luma[1] + grey[2] * kP3Luma[2];
        if (!(greyY > 0.0)) return nullptr;
        double k = file ? double(std::static_pointer_cast<const HncsLook>(file)->k) : double(d.k);
        renderer->scale = d.greyScene / greyY * k * std::exp2(double(style::exposure(key)));
        makePlan(d, key.cct, renderer->plan);
        if (file) {
            renderer->look = std::static_pointer_cast<const HncsLook>(file);
            const HncsLook &lk = *renderer->look;
            renderer->plan.hdrFilm = &lk.hdrFilm;
            renderer->plan.gradation = (lk.flags & kFlagHasGradation) != 0 ? &lk.gradation : nullptr;
            renderer->plan.applyHs = false;
            renderer->plan.satScale = double(lk.satScale);
            renderer->plan.contrast = double(lk.contrast);
            renderer->plan.gamma = double(lk.gamma);
            auto table = interpolate(lk.tables, std::floor(std::clamp(double(key.cct), 2000.0, double(d.tableMax))));
            renderer->plan.table.assign(table.begin(), table.end());
        }
        return renderer;
    }
};

}  // namespace

const Style &hncsStyle() {
    static const HncsStyle style;
    return style;
}

}  // namespace mastercolor
