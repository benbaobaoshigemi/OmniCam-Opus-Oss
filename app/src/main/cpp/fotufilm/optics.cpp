// Spectra to display colour, dye densitometry.
#include "internal.h"

namespace fotufilm {
namespace detail {

static const double kLn10 = std::log(10.0);

Display::Display(const Data &data, const Spec &ill) {
    double white[3] = {0, 0, 0}, d65[3] = {0, 0, 0};
    for (int k = 0; k < N; k++) {
        wx_[k] = ill[k] * data.xbar[k];
        wy_[k] = ill[k] * data.ybar[k];
        wz_[k] = ill[k] * data.zbar[k];
        white[0] += wx_[k];
        white[1] += wy_[k];
        white[2] += wz_[k];
        d65[0] += data.d65[k] * data.xbar[k];
        d65[1] += data.d65[k] * data.ybar[k];
        d65[2] += data.d65[k] * data.zbar[k];
    }
    whiteY_ = std::max(white[1], 1e-12);
    V3 w = {white[0] / whiteY_, white[1] / whiteY_, white[2] / whiteY_};
    const double d65y = std::max(d65[1], 1e-12);
    V3 dw = {d65[0] / d65y, d65[1] / d65y, d65[2] / d65y};
    const V3 src = mul(kBradford, w), dst = mul(kBradford, dw);
    M3 diag{};
    for (int c = 0; c < 3; c++) diag[c * 4] = dst[c] / std::max(src[c], 1e-12);
    const M3 chain = mul(kXyzToP3, mul(kBradfordInv, mul(diag, kBradford)));
    const V3 p3White = mul(kXyzToP3, dw);
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) m_[r * 3 + c] = chain[r * 3 + c] / p3White[r];
}

V3 Display::rgb(const Spec &refl) const {
    double x = 0, y = 0, z = 0;
    for (int k = 0; k < N; k++) {
        x += wx_[k] * refl[k];
        y += wy_[k] * refl[k];
        z += wz_[k] * refl[k];
    }
    return mul(m_, V3{x / whiteY_, y / whiteY_, z / whiteY_});
}

void transmittance(const V3 &a, const Spec dyes[3], double neutral, const Spec *offset, Spec &out) {
    for (int k = 0; k < N; k++) {
        double d = a[0] * dyes[0][k] + a[1] * dyes[1][k] + a[2] * dyes[2][k] + neutral;
        if (offset) d += (*offset)[k];
        out[k] = std::exp(-d * kLn10);
    }
}

bool filmDensityOffset(const Stock &film, Spec &out) {
    if (!film.hasMin) return false;
    for (int k = 0; k < N; k++)
        out[k] = film.minDensity[k] - (film.curves[0].dMin * film.dye[0][k] + film.curves[1].dMin * film.dye[1][k] + film.curves[2].dMin * film.dye[2][k]);
    return true;
}

double retainedSilverDensity(const V3 &density, const V3 &dMin, double fraction) {
    if (!(fraction > 0)) return 0.0;
    double s = 0;
    for (int l = 0; l < 3; l++) s += std::max(density[l] - dMin[l], 0.0);
    return fraction * 1.0 * s;
}

// ---------------------------------------------------------------- Status A
StatusAUnmix::StatusAUnmix(const Data &data, const Spec dyes[3]) : d_(data) {
    for (int i = 0; i < 3; i++) dyes_[i] = dyes[i];
}

V3 StatusAUnmix::read(const V3 &) const { return {}; }

static double statusIntegral(const Spec &s) {
    double v = 0;
    for (double x : s) v += x;
    return v;
}

// Newton steps with a non-negative least-squares step (active sets), as in the engine's StatusADyeUnmix.
V3 StatusAUnmix::amounts(const V3 &target) const {
    double integral[3];
    for (int c = 0; c < 3; c++) integral[c] = statusIntegral(d_.statusA[c]);
    auto measure = [&](const V3 &a, V3 &density, double jac[3][3]) {
        double s[3] = {0, 0, 0}, mom[3][3] = {};
        for (int k = 0; k < N; k++) {
            const double dd = a[0] * dyes_[0][k] + a[1] * dyes_[1][k] + a[2] * dyes_[2][k];
            const double t = std::exp(-dd * kLn10);
            for (int c = 0; c < 3; c++) {
                const double w = t * d_.statusA[c][k] / integral[c];
                s[c] += w;
                for (int j = 0; j < 3; j++) mom[c][j] += dyes_[j][k] * w;
            }
        }
        for (int c = 0; c < 3; c++) {
            const double e = std::max(s[c], 1e-20);
            for (int j = 0; j < 3; j++) jac[c][j] = mom[c][j] / e;
            density[c] = -std::log10(std::max(s[c], 1e-20));
        }
    };
    auto solve = [](const double a0[3][3], const double b0[3], const int *active, int count, double out[3]) {
        double a[3][3], b[3];
        for (int i = 0; i < 3; i++) {
            b[i] = b0[i];
            for (int j = 0; j < 3; j++) a[i][j] = a0[i][j];
        }
        for (int idx = 0; idx < count; idx++) {
            const int col = active[idx];
            if (!(a[col][col] > 1e-10)) return false;
            for (int r = idx + 1; r < count; r++) {
                const int row = active[r];
                const double factor = a[row][col] / a[col][col];
                for (int ki = idx; ki < count; ki++) a[row][active[ki]] -= factor * a[col][active[ki]];
                b[row] -= factor * b[col];
            }
        }
        out[0] = out[1] = out[2] = 0;
        for (int idx = count - 1; idx >= 0; idx--) {
            const int row = active[idx];
            double v = b[row];
            for (int ki = 0; ki < count; ki++)
                if (active[ki] > row) v -= a[row][active[ki]] * out[active[ki]];
            out[row] = v / a[row][row];
        }
        return true;
    };
    V3 amounts = {0, 0, 0};
    for (int iter = 0; iter < 24; iter++) {
        V3 density;
        double jac[3][3];
        measure(amounts, density, jac);
        V3 res = {density[0] - target[0], density[1] - target[1], density[2] - target[2]};
        if (std::max(std::fabs(res[0]), std::max(std::fabs(res[1]), std::fabs(res[2]))) < 1e-6) break;
        double b[3];
        for (int c = 0; c < 3; c++) b[c] = jac[c][0] * amounts[0] + jac[c][1] * amounts[1] + jac[c][2] * amounts[2] - res[c];
        const double reg = 1e-7;
        double normal[3][3], rhs[3];
        for (int j = 0; j < 3; j++) {
            rhs[j] = reg * amounts[j];
            for (int k = 0; k < 3; k++) {
                normal[j][k] = j == k ? reg : 0.0;
                for (int c = 0; c < 3; c++) normal[j][k] += jac[c][j] * jac[c][k];
            }
            for (int c = 0; c < 3; c++) rhs[j] += jac[c][j] * b[c];
        }
        double best[3] = {0, 0, 0};
        double bestErr = b[0] * b[0] + b[1] * b[1] + b[2] * b[2] + reg * (amounts[0] * amounts[0] + amounts[1] * amounts[1] + amounts[2] * amounts[2]);
        for (int mask = 1; mask < 8; mask++) {
            int active[3], count = 0;
            for (int i = 0; i < 3; i++)
                if (mask & (1 << i)) active[count++] = i;
            double cand[3];
            if (!solve(normal, rhs, active, count, cand)) continue;
            if (cand[0] < 0 || cand[1] < 0 || cand[2] < 0) continue;
            double err = 0, dist = 0;
            for (int c = 0; c < 3; c++) {
                const double e = jac[c][0] * cand[0] + jac[c][1] * cand[1] + jac[c][2] * cand[2] - b[c];
                err += e * e;
                const double dd = cand[c] - amounts[c];
                dist += dd * dd;
            }
            const double score = err + reg * dist;
            if (score < bestErr) {
                bestErr = score;
                for (int c = 0; c < 3; c++) best[c] = cand[c];
            }
        }
        double step[3] = {best[0] - amounts[0], best[1] - amounts[1], best[2] - amounts[2]};
        if (std::max(std::fabs(step[0]), std::max(std::fabs(step[1]), std::fabs(step[2]))) < 2e-7) break;
        double scale = 1.0;
        const double err0 = res[0] * res[0] + res[1] * res[1] + res[2] * res[2];
        bool accepted = false;
        for (int tries = 0; tries < 12; tries++) {
            const V3 cand = {amounts[0] + scale * step[0], amounts[1] + scale * step[1], amounts[2] + scale * step[2]};
            V3 dn;
            double jj[3][3];
            measure(cand, dn, jj);
            double e2 = 0;
            for (int c = 0; c < 3; c++) e2 += (dn[c] - target[c]) * (dn[c] - target[c]);
            if (e2 < err0) {
                amounts = cand;
                accepted = true;
                break;
            }
            scale *= 0.5;
        }
        if (!accepted) break;
    }
    return amounts;
}

namespace {

// Solves a 4x4 symmetric normal-equations system (Gaussian elimination, partial pivoting); the identity fallback (0,1,0,0)
// on a singular system, matching fotufilm's _solveNormal.
void solve4(double a[4][4], double b[4], double out[4]) {
    for (int col = 0; col < 4; col++) {
        int pivot = col;
        for (int row = col + 1; row < 4; row++)
            if (std::fabs(a[row][col]) > std::fabs(a[pivot][col])) pivot = row;
        if (!(std::fabs(a[pivot][col]) > 1e-12)) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            out[3] = 0;
            return;
        }
        if (pivot != col) {
            std::swap(a[col], a[pivot]);
            std::swap(b[col], b[pivot]);
        }
        for (int row = col + 1; row < 4; row++) {
            const double factor = a[row][col] / a[col][col];
            if (factor == 0) continue;
            for (int k = col; k < 4; k++) a[row][k] -= factor * a[col][k];
            b[row] -= factor * b[col];
        }
    }
    for (int row = 3; row >= 0; row--) {
        double v = b[row];
        for (int k = row + 1; k < 4; k++) v -= a[row][k] * out[k];
        out[row] = v / a[row][row];
    }
}

}  // namespace

DensityBasis::DensityBasis(const Data &data, const Stock &film) {
    if (film.hasMin) {
        kind_ = Kind::Identity;
    } else if (!film.reflectionPrint) {
        kind_ = Kind::StatusA;
        unmix_ = std::make_unique<StatusAUnmix>(data, film.dye);
    } else {
        kind_ = Kind::ReflectionFit;
        double normal[3][4][4] = {}, moment[3][4] = {};
        for (double le = -1.5; le <= 0.75 + 1e-9; le += 0.025) {
            double dens[3], neutral = 0;
            for (int l = 0; l < 3; l++) {
                dens[l] = film.curves[l].dMin + film.curves[l].dMax() - film.curves[l].density(le);
                neutral += dens[l];
            }
            neutral /= 3.0;
            const double weight = std::pow(10.0, -neutral);
            for (int layer = 0; layer < 3; layer++) {
                const double d = dens[layer];
                const double terms[4] = {1.0, d, d * d, d * d * d};
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) normal[layer][r][c] += weight * terms[r] * terms[c];
                    moment[layer][r] += weight * terms[r] * neutral;
                }
            }
        }
        for (int layer = 0; layer < 3; layer++) solve4(normal[layer], moment[layer], fit_[layer]);
    }
}

V3 DensityBasis::operator()(const V3 &d) const {
    if (kind_ == Kind::Identity) return {std::max(d[0], 0.0), std::max(d[1], 0.0), std::max(d[2], 0.0)};
    if (kind_ == Kind::StatusA) return unmix_->amounts(d);
    V3 out{};
    for (int layer = 0; layer < 3; layer++) {
        const double *c = fit_[layer];
        const double v = d[layer];
        out[layer] = std::max(c[0] + c[1] * v + c[2] * v * v + c[3] * v * v * v, 0.0);
    }
    return out;
}

}  // namespace detail
}  // namespace fotufilm
