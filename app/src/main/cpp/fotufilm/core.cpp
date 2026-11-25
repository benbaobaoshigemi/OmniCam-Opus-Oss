#include "internal.h"

namespace fotufilm {
namespace detail {

const M3 kXyzToP3 = {2.4934969, -0.9313836, -0.4027108, -0.8294890, 1.7626641, 0.0236247, 0.0358458, -0.0761724, 0.9568845};
const M3 kP3ToRec2020 = {0.753833034, 0.198597369, 0.047569597, 0.045743849, 0.941777220, 0.012478931, -0.001210340, 0.017601717, 0.983608623};
const M3 kRec2020ToDomain = {0.670231843, 0.152168745, 0.177599412, 0.044501114, 0.854482372, 0.101016514, 0.0, 0.025777047, 0.974222953};
const M3 kDomainToRec2020 = {1.509371119, -0.261310044, -0.248061075, -0.078854123, 1.187622946, -0.108768823, 0.002086408, -0.031423416, 1.029337008};
const M3 kBradford = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
const M3 kBradfordInv = {0.9869929, -0.1470543, 0.1599627, 0.4323053, 0.5183603, 0.0492912, -0.0085287, 0.0400428, 0.9684867};
const V3 kRec2020Luma = {0.2627002, 0.6779981, 0.0593017};
const V3 kP3Luma = {0.2289746, 0.6917385, 0.0792869};
static const M3 kProPhotoToXyzD50 = {0.7977604896723027, 0.13518583717574031, 0.0313493495815248, 0.2880711282292934, 0.7118432178101014,
                                     0.00008565396060525902, 0.0, 0.0, 0.8251046025104601};
static const M3 kBradfordD50D65 = {0.9555766, -0.0230393, 0.0631636, -0.0282895, 1.0099416, 0.0210077, 0.0122982, -0.0204830, 1.3299098};
static const M3 kXyzD65ToRec2020 = {1.7166512, -0.3556708, -0.2533663, -0.6666844, 1.6164812, 0.0157685, 0.0176399, -0.0427706, 0.9421031};
const M3 kProPhotoD50ToRec2020 = mul(kXyzD65ToRec2020, mul(kBradfordD50D65, kProPhotoToXyzD50));

bool invert(const M3 &m, M3 &o) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (!(std::fabs(det) > 1e-300)) return false;
    o = {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, (f * g - d * i) / det, (a * i - c * g) / det,
         (c * d - a * f) / det,  (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det};
    return true;
}

double curveRange(const Curve &c) { return c.dMax() - c.dMin; }

double logExposureForDensity(const Curve &c, double target) {
    double lo = c.toe - 6, hi = c.sh + 6;
    if (c.sampled) {
        lo = c.x.front();
        hi = c.x.back();
    }
    for (int i = 0; i < 60; i++) {
        const double mid = (lo + hi) / 2;
        if (c.density(mid) < target) lo = mid;
        else hi = mid;
    }
    return (lo + hi) / 2;
}

double paperKey(const V3 &x) { return 0.25 * x[0] + 0.65 * x[1] + 0.10 * x[2]; }

double detailHold(double at, double mid, double black, double knee, double white, double shadows, double highlights) {
    auto smoothstep = [](double t) {
        t = clampd(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    };
    double hold = 0.0;
    const double span = black - mid;
    if (shadows > 0 && span > 0.05) {
        const double u = (at - mid) / std::max(span, 1e-3);
        const double shape = smoothstep(u / 0.7) * (1.0 - smoothstep((u - 0.75) / (1.0 - 0.75)));
        hold = shadows * 0.22 * span * shape;
    }
    const double room = knee - white;
    if (highlights > 0 && room > 0.05) {
        const double past = std::max(knee - at, 0.0);
        hold -= highlights * past * past / (past + room);
    }
    return hold;
}

// ---------------------------------------------------------------- processing
bool applyProcessing(const Data &data, const Stock &base, const Options &o, Stock &out, std::string *error) {
    out = base;
    out.cameraPreflash = std::max(o.cameraPreflash, 0.0);
    if (o.filter >= 0) {
        if (o.filter >= int(data.filters.size())) {
            if (error) *error = "unknown lens filter";
            return false;
        }
        out.hasFilter = true;
        out.filterT = data.filters[o.filter].transmittance;
        // through-the-lens metering: exposure compensated for the filter's luminous transmittance under the scene light
        double wsum = 0, tsum = 0;
        for (int k = 0; k < N; k++) {
            const double w = base.refSpec[k] * data.ybar[k];
            wsum += w;
            tsum += w * out.filterT[k];
        }
        out.filterGain = 1.0 / std::max(wsum > 0 ? tsum / wsum : 1.0, 1e-6);
    }
    if (o.push != 0) {
        const Condition *match = nullptr;
        for (const auto &c : base.conditions)
            if (std::fabs(c.stops - o.push) <= 1e-4) {
                match = &c;
                break;
            }
        if (!match || (base.hasDonor && !match->hasDonor)) {
            if (error) *error = base.id + " has no such push/pull";
            return false;
        }
        for (int i = 0; i < 3; i++) out.curves[i] = match->curves[i];
        if (out.hasDonor) out.donor.curve = match->donor;
        if (out.reversal && o.push != 0) {
            // Reversal push/pull data is shifted to the rated EI; align back to 0 EV so mid-grey metering stays fixed.
            const double shiftStops = o.push * std::log10(2.0);
            for (int i = 0; i < 3; i++) out.curves[i].shift(shiftStops, 0.0);
            if (out.hasDonor) out.donor.curve.shift(shiftStops, 0.0);
        }
    }
    if (o.expiredYears > 0) {
        static const double weights[3] = {0.7, 1.0, 1.3};
        const double decades = o.expiredYears / 10.0;
        double fogTotal = 0;
        for (int l = 0; l < 3; l++) {
            const double lost = decades * weights[l];
            const double fog = 0.08 * lost;
            fogTotal += fog;
            out.curves[l].shift(lost * std::log10(2.0), fog);
            if (out.hasMin)
                for (int k = 0; k < N; k++) out.minDensity[k] += fog * base.dye[l][k];
        }
        // The fog is developed density: it carries grain, which is what makes aged film grainy in its thin shadows.
        out.grain.strength *= 1 + fogTotal / 3;
        out.grain.fog += fogTotal / 3;
    }
    if (out.hasReciprocity && o.shutterSeconds > 0) {
        const Reciprocity &r = out.reciprocity;
        if (o.shutterSeconds > r.threshold && r.rate > 0) {
            const double covered = std::min(o.shutterSeconds, r.through > 0 ? r.through : o.shutterSeconds);
            const double decades = std::log10(covered / r.threshold);
            static const double fallback[3] = {1.2, 0.8, 1.0};
            for (int l = 0; l < 3; l++) {
                const double w = r.hasWeights ? r.weights[l] : (out.monochrome ? 1.0 : fallback[l]);
                const double lost = r.rate * decades * w * std::log10(2.0);
                Curve &c = out.curves[l];
                c.toe += lost;
                c.sh += lost;
                if (c.sampled)
                    for (double &v : c.x) v += lost;
            }
        }
    }
    return true;
}

// ---------------------------------------------------------------- scene light -> layer exposure
Exposure::Exposure(const Data &data, const Stock &stock) : d_(data), s_(stock) {
    for (int i = 0; i < N; i++) {
        if (data.ybar[i] < 0.01) continue;
        const V3 xyz = {data.xbar[i], data.ybar[i], data.zbar[i]};
        const V3 rgb = mul(kP3ToRec2020, mul(kXyzToP3, xyz));
        bands_.push_back(i);
        chroma_.push_back({rgb[0] / data.ybar[i] - 1.0, rgb[1] / data.ybar[i] - 1.0});
    }
}

void Exposure::reflectance(const V3 &in, Spec &out) const {
    out.fill(0.0);
    const V3 rgb = {std::max(in[0], 0.0), std::max(in[1], 0.0), std::max(in[2], 0.0)};
    const double peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    if (peak <= 0) return;
    const double anchor = d_.priorAnchor, ray = peak / anchor;
    const V3 a = {rgb[0] / ray, rgb[1] / ray, rgb[2] / ray};
    int dom = 0;
    if (a[1] >= a[dom]) dom = 1;
    if (a[2] >= a[dom]) dom = 2;
    const int dim = d_.priorDim, ext = dim - 1;
    const double x = clampd(a[(dom + 1) % 3] / anchor * ext, 0, ext);
    const double y = clampd(a[(dom + 2) % 3] / anchor * ext, 0, ext);
    const int xi = std::min(int(x), dim - 2), yi = std::min(int(y), dim - 2);
    const double xf = x - xi, yf = y - yi;
    const float *base = d_.prior.data() + size_t(dom) * dim * dim * N;
    auto at = [&](int yy, int xx) { return base + (size_t(yy) * dim + xx) * N; };
    const float *p00 = at(yi, xi), *p01 = at(yi, xi + 1), *p10 = at(yi + 1, xi), *p11 = at(yi + 1, xi + 1);
    for (int k = 0; k < N; k++) {
        const double lo = p00[k] + xf * (double(p01[k]) - p00[k]);
        const double hi = p10[k] + xf * (double(p11[k]) - p10[k]);
        out[k] = std::max((lo + yf * (hi - lo)) * ray, 0.0);
    }
}

V3 Exposure::spectral(const V3 &rgb) const {
    Spec refl;
    reflectance(rgb, refl);
    const Spec &ref = s_.refSpec;
    const double ly = dot(ref, d_.ybar);
    V3 out{};
    for (int l = 0; l < 3; l++) {
        double num = 0, den = 0;
        for (int k = 0; k < N; k++) {
            const double light = ref[k] / ly;
            num += s_.sens[l][k] * (refl[k] * light * filterAt(k));
            den += s_.sens[l][k] * (ref[k] / ly);
        }
        out[l] = filterGain() * num / std::max(den, 1e-12);
    }
    return out;
}

V3 Exposure::anchored(const V3 &rgb) const {
    const double peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    if (peak <= 0) return {0, 0, 0};
    const double radiance = peak / kReconAnchor;
    const V3 e = spectral({rgb[0] / radiance, rgb[1] / radiance, rgb[2] / radiance});
    return {e[0] * radiance, e[1] * radiance, e[2] * radiance};
}

double Exposure::donorAnchored(const V3 &rgb) const {
    const double peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    if (!(peak > 0)) return 0.0;
    const double radiance = peak / kReconAnchor;
    Spec refl;
    reflectance({rgb[0] / radiance, rgb[1] / radiance, rgb[2] / radiance}, refl);
    const Spec &ref = s_.refSpec;
    const double ly = dot(ref, d_.ybar);
    double num = 0, den = 0;
    for (int k = 0; k < N; k++) {
        num += refl[k] * filterAt(k) * (ref[k] / ly) * s_.donor.sens[k];
        den += (ref[k] / ly) * s_.donor.sens[k];
    }
    return filterGain() * num / std::max(den, 1e-12) * radiance;
}

bool Exposure::boundary(double dx, double dy, Hit &best) const {
    bool found = false;
    const size_t n = chroma_.size();
    for (size_t lower = 0; lower < n; lower++) {
        const size_t upper = (lower + 1) % n;
        const auto &frm = chroma_[lower];
        const double ex = chroma_[upper][0] - frm[0], ey = chroma_[upper][1] - frm[1];
        const double det = -dx * ey + ex * dy;
        if (std::fabs(det) <= 1e-18) continue;
        const double alpha = (-frm[0] * ey + ex * frm[1]) / det;
        const double beta = (dx * frm[1] - dy * frm[0]) / det;
        if (!(alpha > 0 && beta >= -1e-9 && beta <= 1 + 1e-9)) continue;
        if (!found || alpha < best.alpha) {
            best = {alpha, clampd(beta, 0.0, 1.0), bands_[lower], bands_[upper]};
            found = true;
        }
    }
    return found;
}

Exposure::Light Exposure::classify(const V3 &rgb) const {
    Light out;
    const double peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    const double binding = std::min(rgb[0], std::min(rgb[1], rgb[2]));
    if (!(binding < -1e-6 * std::max(peak, 1.0))) {
        out.kind = Light::Reflectance;
        out.colour = {std::max(rgb[0], 0.0), std::max(rgb[1], 0.0), std::max(rgb[2], 0.0)};
        return out;
    }
    const double lum = kRec2020Luma[0] * rgb[0] + kRec2020Luma[1] * rgb[1] + kRec2020Luma[2] * rgb[2];
    if (!(lum > 0)) return out;
    double cube = 1.0;
    int bindingCh = 0, peakCh = 0;
    for (int ch = 0; ch < 3; ch++) {
        if (rgb[ch] < 0) {
            const double reach = lum / (lum - rgb[ch]);
            if (reach < cube) {
                cube = reach;
                bindingCh = ch;
            }
        }
        if (rgb[ch] > rgb[peakCh]) peakCh = ch;
    }
    V3 face{};
    for (int c = 0; c < 3; c++) face[c] = std::max(lum + cube * (rgb[c] - lum), 0.0);
    auto mirrored = [&](const V3 &p) { return V3{std::max(face[0] * 2 - p[0], 0.0), std::max(face[1] * 2 - p[1], 0.0), std::max(face[2] * 2 - p[2], 0.0)}; };
    out.face = face;
    out.luminance = lum;
    Hit hit{};
    if (!(binding < -kProjectionMargin * peak) || !boundary(rgb[0] / lum - 1.0, rgb[1] / lum - 1.0, hit)) {
        out.kind = Light::Extrapolated;
        out.mirror = mirrored(rgb);
        return out;
    }
    const double depth = lum - rgb[bindingCh], rise = rgb[peakCh] - lum;
    const double start = lum * (1 + kProjectionMargin) / (depth - kProjectionMargin * rise);
    V3 marginPoint{};
    for (int c = 0; c < 3; c++) marginPoint[c] = lum + start * (rgb[c] - lum);
    const double reach = hit.alpha;
    out.share = reach - start > 1e-6 ? clampd((1 - start) / (reach - start), 0.0, 1.0) : 1.0;
    out.kind = Light::Locus;
    out.mirror = mirrored(marginPoint);
    out.lower = hit.lower;
    out.upper = hit.upper;
    out.upperShare = hit.beta;
    return out;
}

V3 Exposure::monochromatic(const Light &l) const {
    const Spec &ill = s_.refSpec;
    const double sceneY = dot(ill, d_.ybar), refY = sceneY;
    double white = 0;
    for (int k = 0; k < N; k++) white += ill[k] / sceneY * d_.ybar[k];
    const double target = l.luminance * white;
    const double lowerPower = (1 - l.upperShare) * target / std::max(d_.ybar[l.lower], 1e-12);
    const double upperPower = l.upperShare * target / std::max(d_.ybar[l.upper], 1e-12);
    V3 out{};
    for (int r = 0; r < 3; r++) {
        const double e = lowerPower * filterAt(l.lower) * s_.sens[r][l.lower] + upperPower * filterAt(l.upper) * s_.sens[r][l.upper];
        double ref = 0;
        for (int k = 0; k < N; k++) ref += s_.refSpec[k] / refY * s_.sens[r][k];
        out[r] = filterGain() * e / std::max(ref, 1e-12);
    }
    return out;
}

double Exposure::donorMonochromatic(const Light &l) const {
    const Spec &ill = s_.refSpec;
    const double sceneY = dot(ill, d_.ybar);
    double white = 0;
    for (int k = 0; k < N; k++) white += ill[k] / sceneY * d_.ybar[k];
    const double target = l.luminance * white;
    const double lowerPower = (1 - l.upperShare) * target / std::max(d_.ybar[l.lower], 1e-12);
    const double upperPower = l.upperShare * target / std::max(d_.ybar[l.upper], 1e-12);
    const double e = lowerPower * filterAt(l.lower) * s_.donor.sens[l.lower] + upperPower * filterAt(l.upper) * s_.donor.sens[l.upper];
    double ref = 0;
    for (int k = 0; k < N; k++) ref += s_.refSpec[k] / sceneY * s_.donor.sens[k];
    return filterGain() * e / std::max(ref, 1e-12);
}

void Exposure::domain(const V3 &point, double out[4]) const {
    const V3 rgb = mul(kDomainToRec2020, point);
    const Light l = classify(rgb);
    V3 e{};
    double ed = 0;
    auto extrap = [](const V3 &face, const V3 &mirror) {
        return V3{std::max(face[0] * 2 - mirror[0], 0.0), std::max(face[1] * 2 - mirror[1], 0.0), std::max(face[2] * 2 - mirror[2], 0.0)};
    };
    switch (l.kind) {
    case Light::None:
        break;
    case Light::Reflectance:
        e = anchored(l.colour);
        if (s_.hasDonor) ed = donorAnchored(l.colour);
        break;
    case Light::Extrapolated:
        e = extrap(anchored(l.face), anchored(l.mirror));
        if (s_.hasDonor) ed = std::max(donorAnchored(l.face) * 2 - donorAnchored(l.mirror), 0.0);
        break;
    case Light::Locus: {
        const V3 edge = extrap(anchored(l.face), anchored(l.mirror));
        const V3 mono = monochromatic(l);
        for (int c = 0; c < 3; c++) e[c] = (1 - l.share) * edge[c] + l.share * mono[c];
        if (s_.hasDonor) {
            const double edged = std::max(donorAnchored(l.face) * 2 - donorAnchored(l.mirror), 0.0);
            ed = (1 - l.share) * edged + l.share * donorMonochromatic(l);
        }
        break;
    }
    }
    out[0] = e[0];
    out[1] = e[1];
    out[2] = e[2];
    out[3] = ed;
}

int Exposure::layer(const V3 &s, double gain, double out[4]) const {
    const int n = s_.hasDonor ? 4 : 3;
    for (int i = 0; i < 4; i++) out[i] = s_.cameraPreflash;
    const double y = kRec2020Luma[0] * s[0] + kRec2020Luma[1] * s[1] + kRec2020Luma[2] * s[2];
    if (!(y > 0)) return n;
    const V3 dom = mul(kRec2020ToDomain, s);
    V3 projected = dom;
    if (dom[0] < 0 || dom[1] < 0 || dom[2] < 0) {
        double k = 1.0;
        for (int c = 0; c < 3; c++)
            if (dom[c] < 0) k = std::min(k, y / (y - dom[c]));
        for (int c = 0; c < 3; c++) projected[c] = y + k * (dom[c] - y);
    }
    V3 rgb = {std::max(projected[0], 0.0), std::max(projected[1], 0.0), std::max(projected[2], 0.0)};
    const double radiance = std::max(1e-6, std::max(rgb[0], std::max(rgb[1], rgb[2])));
    double sampled[4];
    domain({rgb[0] / radiance, rgb[1] / radiance, rgb[2] / radiance}, sampled);
    for (int i = 0; i < n; i++) out[i] = std::max(sampled[i] * radiance * gain, 0.0) + s_.cameraPreflash;
    return n;
}

// ---------------------------------------------------------------- developer
double inhibitorRelease(double a, double gamma) {
    a = clampd(a, 0.0, 1.0);
    if (gamma == 1) return a;
    const double r = std::pow(a, gamma), t = std::pow(1 - a, gamma);
    return r / std::max(r + t, std::numeric_limits<double>::min());
}

static double effectiveScale(double requested) {
    const double v = std::max(requested, 0.0);
    if (v <= 1) return v;
    const double over = v - 1;
    return 1 + over / (1 + over);
}

Developer::Developer(const Stock &film, double couplerScale) : s_(film) {
    scale_ = effectiveScale(couplerScale);
    if (film.geometry) {
        for (int r = 0; r < 3; r++)
            for (int d = 0; d < 3; d++) {
                if (r == d) {
                    K_[r * 3 + d] = film.release[r] * film.selfRetention;
                } else {
                    double c = 1.0;
                    for (int gap = std::min(r, d); gap < std::max(r, d); gap++) c *= film.transmission[gap];
                    K_[r * 3 + d] = film.release[d] * c;
                }
            }
    } else {
        K_ = film.inhibition;
    }
    gamma_ = film.releaseGamma;
    for (int c = 0; c < 3; c++) {
        dMin_[c] = film.curves[c].dMin;
        dRng_[c] = curveRange(film.curves[c]);
        dMax_[c] = film.curves[c].dMax();
    }
    bool any = false;
    for (double v : K_) any = any || v != 0;
    if (film.hasDonor)
        for (double v : film.donor.inhibition) any = any || v != 0;
    hasInhibition_ = any;
    nonlinear_ = gamma_[0] != 1 || gamma_[1] != 1 || gamma_[2] != 1 || (film.hasDonor && film.donor.releaseGamma != 1);
    if (scale_ > 0 && hasInhibition_) buildWarp();
}

V3 Developer::activation(const V3 &logE) const {
    V3 out{};
    for (int j = 0; j < 3; j++) out[j] = (s_.curves[j].density(logE[j]) - dMin_[j]) / dRng_[j];
    return out;
}

double Developer::donorActivation(double logE) const {
    const Curve &c = s_.donor.curve;
    return (c.density(logE) - c.dMin) / std::max(curveRange(c), 1e-6);
}

void Developer::buildWarp() {
    constexpr double kMin = -4.0, kMax = 4.0;
    constexpr int n = 4 * kWarp;
    const double low = kMin - 1, step = ((kMax + 1) - low) / (n - 1);
    std::vector<double> l(n), act[3], donorAct, u(n), offset(n);
    for (int i = 0; i < n; i++) l[i] = low + i * step;
    for (int layer = 0; layer < 3; layer++) {
        act[layer].resize(n);
        for (int i = 0; i < n; i++)
            act[layer][i] = inhibitorRelease((s_.curves[layer].density(l[i]) - dMin_[layer]) / dRng_[layer], gamma_[layer]);
    }
    const bool donor = s_.hasDonor && curveRange(s_.donor.curve) > 0;
    if (donor) {
        donorAct.resize(n);
        const Curve &c = s_.donor.curve;
        for (int i = 0; i < n; i++) donorAct[i] = inhibitorRelease((c.density(l[i]) - c.dMin) / curveRange(c), s_.donor.releaseGamma);
    }
    for (int ch = 0; ch < 3; ch++) {
        for (int i = 0; i < n; i++) {
            double released = 0;
            for (int d = 0; d < 3; d++) released += K_[ch * 3 + d] * act[d][i];
            if (donor) released += s_.donor.inhibition[ch] * donorAct[i];
            offset[i] = scale_ * released;
            u[i] = l[i] - offset[i];
        }
        for (int i = 1; i < n; i++)
            if (u[i] <= u[i - 1]) u[i] = u[i - 1] + 1e-6;
        int cursor = 0;
        for (int k = 0; k < kWarp; k++) {
            const double target = kMin + (kMax - kMin) * k / (kWarp - 1);
            while (cursor + 2 < n && u[cursor + 1] < target) cursor++;
            const double span = u[cursor + 1] - u[cursor];
            const double t = span > 0 ? clampd((target - u[cursor]) / span, 0.0, 1.0) : 0.0;
            warp_[ch][k] = offset[cursor] + t * (offset[cursor + 1] - offset[cursor]);
        }
    }
}

double Developer::warp(int ch, double u) const {
    const double q = clampd((u + 4.0) * ((kWarp - 1) / 8.0), 0.0, kWarp - 1);
    const int i = std::min(int(q), kWarp - 2);
    const double f = q - i;
    const double *b = warp_[ch];
    const double lo = b[i], hi = b[i + 1];
    const double linear = lo + f * (hi - lo);
    if (!nonlinear_) return linear;
    const double prev = b[std::max(i - 1, 0)], next = b[std::min(i + 2, kWarp - 1)];
    const double delta = std::max(hi - lo, 0.0);
    const double ls = clampd(0.5 * (hi - prev), 0.0, 3 * delta);
    const double hs = clampd(0.5 * (next - lo), 0.0, 3 * delta);
    const double f2 = f * f, f3 = f * f * f;
    return (2 * f3 - 3 * f2 + 1) * lo + (f3 - 2 * f2 + f) * ls + (-2 * f3 + 3 * f2) * hi + (f3 - f2) * hs;
}

V3 Developer::density(const double exposure[4], int count) const {
    V3 logE{};
    for (int c = 0; c < 3; c++) logE[c] = std::log10(std::max(exposure[c], 1e-6));
    V3 eff = logE;
    if (scale_ > 0 && hasInhibition_) {
        const V3 act = activation(logE);
        V3 released{};
        for (int c = 0; c < 3; c++) released[c] = inhibitorRelease(act[c], gamma_[c]);
        V3 inhibition{};
        for (int c = 0; c < 3; c++) inhibition[c] = scale_ * (K_[c * 3] * released[0] + K_[c * 3 + 1] * released[1] + K_[c * 3 + 2] * released[2]);
        if (s_.hasDonor && count > 3) {
            const double dlog = std::log10(std::max(exposure[3], 1e-6));
            const double dr = inhibitorRelease(donorActivation(dlog), s_.donor.releaseGamma);
            for (int c = 0; c < 3; c++) inhibition[c] += s_.donor.inhibition[c] * dr * scale_;
        }
        for (int c = 0; c < 3; c++) {
            const double u = logE[c] - inhibition[c];
            eff[c] = u + warp(c, u);
        }
    }
    V3 out{};
    for (int c = 0; c < 3; c++) {
        out[c] = s_.curves[c].density(eff[c]);
        if (s_.reversal) out[c] = dMin_[c] + dMax_[c] - out[c];
    }
    return out;
}

}  // namespace detail
}  // namespace fotufilm
