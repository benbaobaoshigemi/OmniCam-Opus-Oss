// Hue block of the Fujifilm X100VI engine (Nostalgic Neg., Bleach Bypass), after the 3D LUT, on Adobe-encoded Rec.601 YCC. The operation is silicon in the camera,
// so both tables are measured on its renders (tools/mastercolor/x100vi_engine.py, apply_hue_gain, is the reference).
//   hg[0]                     flag (0: block off)
//   hg[1 .. 180]              chroma gain over 36 hue cells (10 degrees, centred at 5, 15, ...) x 5 Y knots (0.075 + 0.18125 k)
//   hg[181 .. 181 + 5184)     correction (dY, chroma ratio - 1, hue shift in degrees) over component x 36 hue cells x 6 Y knots x 8 chroma knots
#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>

namespace mastercolor {
namespace x100vi {

constexpr int kHueCells = 36, kGainYs = 5, kResYs = 6, kResCs = 8;
constexpr size_t kHueFloats = 1 + kHueCells * kGainYs + 3 * kHueCells * kResYs * kResCs;

namespace hue_block_detail {

constexpr double kPi = 3.14159265358979323846;
constexpr double kResYKnots[kResYs] = {0.10, 0.25, 0.35, 0.45, 0.55, 0.70};
constexpr double kResCKnots[kResCs] = {0.0, 0.08, 0.14, 0.20, 0.24, 0.27, 0.30, 0.34};

struct Ycc {
    double y, cb, cr;
};

inline Ycc toYcc(const double e[3]) {
    return {(306.0 * e[0] + 601.0 * e[1] + 117.0 * e[2]) / 1024.0, (-173.0 * e[0] - 339.0 * e[1] + 512.0 * e[2]) / 1024.0, (512.0 * e[0] - 429.0 * e[1] - 83.0 * e[2]) / 1024.0};
}

inline double hueOf(const Ycc &c) {
    double h = std::atan2(-c.cr, -c.cb) * 180.0 / kPi;
    return h < 0.0 ? h + 360.0 : h;
}

// The inverse of the Rec.601 matrix above (rows: R, G, B from Y, Cb, Cr).
inline const double *inverse() {
    static double m[9];
    static bool ready = false;
    if (!ready) {
        const double a[9] = {306, 601, 117, -173, -339, 512, 512, -429, -83};
        const double det = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6]);
        m[0] = (a[4] * a[8] - a[5] * a[7]) / det;
        m[1] = (a[2] * a[7] - a[1] * a[8]) / det;
        m[2] = (a[1] * a[5] - a[2] * a[4]) / det;
        m[3] = (a[5] * a[6] - a[3] * a[8]) / det;
        m[4] = (a[0] * a[8] - a[2] * a[6]) / det;
        m[5] = (a[2] * a[3] - a[0] * a[5]) / det;
        m[6] = (a[3] * a[7] - a[4] * a[6]) / det;
        m[7] = (a[1] * a[6] - a[0] * a[7]) / det;
        m[8] = (a[0] * a[4] - a[1] * a[3]) / det;
        for (double &v : m) v *= 1024.0;
        ready = true;
    }
    return m;
}

inline void cells(double hue, int &i0, int &i1, double &fh) {
    const double hp = hue / 10.0 - 0.5, f = std::floor(hp);
    fh = hp - f;
    i0 = ((int(f) % kHueCells) + kHueCells) % kHueCells;
    i1 = (i0 + 1) % kHueCells;
}

inline void knot(const double *knots, int n, double v, int &k0, double &f) {
    double p = n - 1.0;
    if (v <= knots[0]) p = 0.0;
    else if (v < knots[n - 1]) {
        int k = 0;
        while (v > knots[k + 1]) ++k;
        p = k + (v - knots[k]) / (knots[k + 1] - knots[k]);
    }
    k0 = std::min(int(std::floor(p)), n - 2);
    f = p - k0;
}

}  // namespace hue_block_detail

inline void applyHueBlock(const float *hg, double enc[3]) {
    using namespace hue_block_detail;
    if (hg[0] == 0.0f) return;
    Ycc c = toYcc(enc);
    int i0, i1;
    double fh;
    cells(hueOf(c), i0, i1, fh);
    const double yp = std::min(std::max((c.y - 0.075) / 0.18125, 0.0), kGainYs - 1.0);
    const int j0 = std::min(int(std::floor(yp)), kGainYs - 2);
    const double fy = yp - j0;
    const float *t = hg + 1;
    const double g0 = t[i0 * kGainYs + j0] * (1 - fh) * (1 - fy) + t[i1 * kGainYs + j0] * fh * (1 - fy) + t[i0 * kGainYs + j0 + 1] * (1 - fh) * fy + t[i1 * kGainYs + j0 + 1] * fh * fy;
    const double w0 = std::min(std::max((std::hypot(c.cb, c.cr) - 0.03) / 0.05, 0.0), 1.0);
    const double g = 1.0 + w0 * (g0 - 1.0);
    for (int k = 0; k < 3; ++k) enc[k] = c.y + g * (enc[k] - c.y);

    c = toYcc(enc);
    const double chroma = std::hypot(c.cb, c.cr), hue = hueOf(c);
    cells(hue, i0, i1, fh);
    int jy, kc;
    double fyy, fc;
    knot(kResYKnots, kResYs, c.y, jy, fyy);
    knot(kResCKnots, kResCs, chroma, kc, fc);
    const float *res = hg + 1 + kHueCells * kGainYs;
    double delta[3] = {0, 0, 0};
    for (int comp = 0; comp < 3; ++comp) {
        const float *r = res + size_t(comp) * kHueCells * kResYs * kResCs;
        for (int a = 0; a < 2; ++a)
            for (int b = 0; b < 2; ++b)
                for (int d = 0; d < 2; ++d) {
                    const double wt = (a ? fh : 1 - fh) * (b ? fyy : 1 - fyy) * (d ? fc : 1 - fc);
                    delta[comp] += r[((a ? i1 : i0) * kResYs + jy + b) * kResCs + kc + d] * wt;
                }
    }
    const double w = std::min(std::max((chroma - 0.03) / 0.05, 0.0), 1.0);
    const double angle = (hue + delta[2] * w) * kPi / 180.0, cc = chroma * (1.0 + delta[1] * w), yy = c.y + delta[0] * w;
    const double cb = -cc * std::cos(angle), cr = -cc * std::sin(angle), *m = inverse();
    for (int k = 0; k < 3; ++k) enc[k] = m[3 * k] * yy + m[3 * k + 1] * cb + m[3 * k + 2] * cr;

}

}  // namespace x100vi
}  // namespace mastercolor
