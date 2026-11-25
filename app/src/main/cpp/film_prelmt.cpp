// Film grain laid before the LMT, in the capture's ProProc pass (film_proproc.cpp): the film's clump field and its gain from the
// developed density back onto the LMT's input code.
//
// The master LMT is the whole film at once: input code -> scene light -> layer exposures -> developed densities -> print -> display.
// Grain belongs in the developed densities (fotufilm clump field, FilmEngine and Stages/Grain.h). A density change dD at an input
// code c is a code change dc = (dD/dc)^-1 dD, so the plan carries, per node of a code lattice, M = (dD/dc)^-1 diag(m(D)): the
// Jacobian of the film's development with respect to the input code (m is the film's granularity-against-density law). The
// pixel's grain n (a unit clump field scaled to the film's published RMS granularity) then moves its code by M n, and the LMT
// prints it as density grain.
#include "film_prelmt.h"
#include "film_proproc.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <thread>
#include <unordered_set>

namespace {

constexpr const char *TAG = "FilmPreLmt";
constexpr double kApertureRadiusMM = 0.024;       // RMS granularity is read through a 48 um aperture
constexpr double kSigmaFloor = 0.151;             // narrowest grain blur, pixels (FilmEngine.grainSigmaFloorPixels)

// ---------------------------------------------------------------- grain (FilmEngine.swift, FilmStock.swift, Stages/Grain.h)
int gaussianRadius(double sigma) {
    if (sigma <= 0.15) return 0;
    const double variance = 2 * sigma * sigma;
    const int limit = int(std::ceil(4 * sigma)) + 1;
    std::vector<double> tail(limit);
    double total = 1;
    for (int k = 1; k <= limit; k++) total += 2 * (tail[k - 1] = std::exp(-double(k * k) / variance));
    double dropped = 0;
    for (int r = limit; r >= 1; r--) {
        dropped += 2 * tail[r - 1];
        if (dropped > 0.0027 * total) return r;
    }
    return 1;
}

double sampledCorrelation(double sigma) {
    const int radius = gaussianRadius(sigma);
    if (radius <= 0 || sigma <= 0) return 0;
    std::vector<double> taps;
    for (int k = -radius; k <= radius; k++) taps.push_back(std::exp(-double(k * k) / (2 * sigma * sigma)));
    double zero = 0, one = 0;
    for (size_t i = 0; i < taps.size(); i++) zero += taps[i] * taps[i];
    for (size_t i = 0; i + 1 < taps.size(); i++) one += taps[i] * taps[i + 1];
    return zero > 0 ? one / zero : 0;
}

// R(t) of the pixel box read through a Gaussian field of sigma `sigma` (pixels).
double latticeCovariance(double t, double sigma) {
    const double s = std::sqrt(std::max(2 * sigma * sigma, 1e-8));
    auto phi = [](double x) { return std::exp(-0.5 * x * x) / std::sqrt(2 * M_PI); };
    auto cdf = [](double x) { return 0.5 * (1 + std::erf(x / std::sqrt(2.0))); };
    auto z = [&](double x) { return (x - t) / s; };
    return (1 + t) * (cdf(z(0)) - cdf(z(-1))) + (1 - t) * (cdf(z(1)) - cdf(z(0))) + s * (phi(z(-1)) - 2 * phi(z(0)) + phi(z(1)));
}

// The discrete blur whose lag-1 correlation is a continuous clump's once integrated over the pixel.
double discreteGrainSigma(double clumpPixels) {
    const double r0 = latticeCovariance(0, clumpPixels);
    const double target = r0 > 0 ? latticeCovariance(1, clumpPixels) / r0 : 0;
    if (target <= 0) return kSigmaFloor;
    double high = 1;
    while (sampledCorrelation(high) < target && high < 64) high *= 2;
    double low = kSigmaFloor;
    if (sampledCorrelation(low) >= target) return low;
    for (int i = 0; i < 40; i++) {
        double mid = 0.5 * (low + high);
        if (sampledCorrelation(mid) < target) low = mid; else high = mid;
    }
    return 0.5 * (low + high);
}

double scaledI0(double x) {
    double ax = std::fabs(x);
    if (ax < 3.75) {
        double t = (ax / 3.75) * (ax / 3.75);
        return (1.0 + t * (3.5156229 + t * (3.0899424 + t * (1.2067492 + t * (0.2659732 + t * (0.0360768 + t * 0.0045813)))))) * std::exp(-ax);
    }
    double t = 3.75 / ax;
    return (0.39894228 + t * (0.01328592 + t * (0.00225319 + t * (-0.00157565 + t * (0.00916281 + t * (-0.02057706 +
            t * (0.02635537 + t * (-0.01647633 + t * 0.00392377)))))))) / std::sqrt(ax);
}
double scaledI1(double x) {
    double ax = std::fabs(x);
    if (ax < 3.75) {
        double t = (ax / 3.75) * (ax / 3.75);
        return ax * (0.5 + t * (0.87890594 + t * (0.51498869 + t * (0.15084934 + t * (0.02658733 + t * (0.00301532 + t * 0.00032411)))))) *
               std::exp(-ax);
    }
    double t = 3.75 / ax;
    double tail = 0.02282967 + t * (-0.02895312 + t * (0.01787654 - t * 0.00420059));
    return (0.39894228 + t * (-0.03988024 + t * (-0.00362018 + t * (0.00163801 + t * (-0.01031555 + t * tail))))) / std::sqrt(ax);
}
// Fraction of a Gaussian clump field's granularity a 48 um aperture keeps.
double apertureResponse(double sigmaMM) {
    if (sigmaMM <= 0) return 1;
    const double z = kApertureRadiusMM * kApertureRadiusMM / (2 * sigmaMM * sigmaMM);
    return std::sqrt(std::max(1 - (scaledI0(z) + scaledI1(z)), 1e-4));
}

// One clump population of the frame: the noise draws, the per-record blur, the amplitude at the read density.
using GrainField = FilmGrainPopulation;

struct GrainSetup {
    std::vector<GrainField> fields;
    int radius = 0;
    double anchor[3] = {0, 0, 0};      // read density + fog
    double dMin[3] = {0, 0, 0};
};

double variance(const fotufilm::Grain &g, int layer, double d) {
    switch (g.law) {
        case 1: return d * std::pow(10.0, 0.21004 * d + 0.06114 * d * d);
        case 2: return d;
        case 3: {
            double p = std::pow(d / std::max(g.reversal[1], 1e-4), 2 * g.reversal[0]);
            return p / (1 + p);
        }
        default: {
            const double *r = g.profile[layer];
            double off = (d - r[4]) / std::max(r[5], 1e-4);
            return (1 - std::exp(-d / std::max(r[1], 1e-4))) * (1 + r[0] * std::exp(-d / std::max(r[2], 1e-4)) + r[3] * std::exp(-0.5 * off * off));
        }
    }
}

GrainSetup grainSetup(const fotufilm::Stock &film, double pxPerMM) {
    GrainSetup s;
    const fotufilm::Grain &g = film.grain;
    for (int c = 0; c < 3; c++) {
        const fotufilm::Curve &cv = film.curves[c];
        double range = cv.dMax() - cv.dMin;
        double net = g.readGross ? 1 - cv.dMin : 1;
        double read = range > 0 ? std::min(std::max(net, 0.05 * range), 0.9 * range) : 0.5;
        s.anchor[c] = read + g.fog;
        s.dMin[c] = cv.dMin;
    }
    if (!(g.strength > 0) || !(g.sizeMM > 0)) return s;
    const double coverage = std::min(std::max(1 - std::pow(10.0, -std::max(s.anchor[1], 1e-3)), 1e-4), 0.99);
    const double clumpsPerMM2 = -std::log(1 - coverage) / (M_PI * g.sizeMM * g.sizeMM);
    const double rho = std::min(std::max(g.luma, 0.0), 1.0);
    const double share = std::min(std::max(g.mottleShare, 0.0), 0.9);
    for (int population = 0; population < (share > 0 ? 2 : 1); population++) {
        const double ratio = population ? g.mottleRatio : 1;
        GrainField f;
        f.lambda = float(clumpsPerMM2 / (pxPerMM * pxPerMM) / (ratio * ratio));
        f.limit = std::exp(-f.lambda);
        f.norm = 1.0f / std::sqrt(std::max(f.lambda, 1e-4f));
        f.own = float(std::sqrt(1 - rho));
        f.shared = float(std::sqrt(rho));
        f.layerBase = population ? 4 : 0;
        f.sharedLayer = population ? 7 : 3;
        double sigma[3];
        for (int c = 0; c < 3; c++) {
            const double clumpMM = g.sizeMM / 2 * g.layerSizeRatio[c] * ratio;
            // The clump as the pixel lattice carries it. The enlarger's own blur is not added: the image does not pass through it, and
            // blurring only the grain would make it coarser than the picture it sits in.
            sigma[c] = discreteGrainSigma(clumpMM * pxPerMM);
            const double aperture = std::sqrt(M_PI) * kApertureRadiusMM * pxPerMM / apertureResponse(clumpMM);
            f.amp[c] = float(g.strength * g.layerWeights[c] * aperture * std::sqrt(population ? share : 1 - share));
        }
        f.radius = gaussianRadius(std::max({sigma[0], sigma[1], sigma[2]}));
        for (int c = 0; c < 3; c++) {
            f.taps[c].resize(2 * f.radius + 1);
            double sum = 0;
            for (int k = -f.radius; k <= f.radius; k++) sum += f.taps[c][k + f.radius] = float(std::exp(-double(k * k) / (2 * sigma[c] * sigma[c])));
            for (float &v : f.taps[c]) v = float(v / sum);
        }
        s.radius = std::max(s.radius, f.radius);
        filmTrace("grain %s: lambda %.3f/px, sigma %.2f/%.2f/%.2f px, radius %d, amplitude %.4f/%.4f/%.4f",
                            population ? "mottle" : "clump", double(f.lambda), sigma[0], sigma[1], sigma[2], f.radius, double(f.amp[0]),
                            double(f.amp[1]), double(f.amp[2]));
        s.fields.push_back(std::move(f));
    }
    return s;
}

inline double modulation(const fotufilm::Grain &g, const GrainSetup &s, int c, double density) {
    const double here = std::max(density - s.dMin[c], 0.0) + g.fog;
    const double ratio = variance(g, c, here) / std::max(variance(g, c, s.anchor[c]), g.law == 3 ? 1e-20 : 1e-6);
    return std::sqrt(std::max(ratio, 0.0));
}


bool inverse3(const double a[3][3], double out[3][3]) {
    const double c00 = a[1][1] * a[2][2] - a[1][2] * a[2][1], c01 = a[1][2] * a[2][0] - a[1][0] * a[2][2],
                 c02 = a[1][0] * a[2][1] - a[1][1] * a[2][0];
    const double det = a[0][0] * c00 + a[0][1] * c01 + a[0][2] * c02;
    if (std::fabs(det) < 1e-18) return false;
    out[0][0] = c00 / det;
    out[1][0] = c01 / det;
    out[2][0] = c02 / det;
    out[0][1] = (a[0][2] * a[2][1] - a[0][1] * a[2][2]) / det;
    out[1][1] = (a[0][0] * a[2][2] - a[0][2] * a[2][0]) / det;
    out[2][1] = (a[0][1] * a[2][0] - a[0][0] * a[2][1]) / det;
    out[0][2] = (a[0][1] * a[1][2] - a[0][2] * a[1][1]) / det;
    out[1][2] = (a[0][2] * a[1][0] - a[0][0] * a[1][2]) / det;
    out[2][2] = (a[0][0] * a[1][1] - a[0][1] * a[1][0]) / det;
    return true;
}

}  // namespace

bool buildGrainPlan(const fotufilm::Engine &engine, const fotufilm::Data &data, const fotufilm::Options &options,
                    const std::vector<double> &scene, int n, double axisMax, int width, int height, double frameMM, FilmGrainPlan &out) {
    const fotufilm::Stock &film = engine.film();
    const fotufilm::Grain &g = film.grain;
    if (!(g.strength > 0) || n < 3 || scene.size() != size_t(n) * n * n * 3) return false;
    const double pxPerMM = std::hypot(double(width), double(height)) / frameMM;
    GrainSetup gs = grainSetup(film, pxPerMM);
    if (gs.fields.empty()) return false;
    const size_t nodes = size_t(n) * n * n;
    std::vector<double> density(nodes * 3);
    const unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    auto parallel = [&](auto &&body) {
        std::atomic<int> next{0};
        std::vector<std::thread> pool;
        auto worker = [&]() {
            for (int b; (b = next.fetch_add(1)) < n;) body(b);
        };
        for (unsigned t = 1; t < threads; t++) pool.emplace_back(worker);
        worker();
        for (auto &t : pool) t.join();
    };
    parallel([&](int b) {
        for (int gi = 0; gi < n; gi++)
            for (int r = 0; r < n; r++) {
                const size_t at = (size_t(b) * n + gi) * n + r;
                const fotufilm::V3 pp = {scene[at * 3], scene[at * 3 + 1], scene[at * 3 + 2]};
                const fotufilm::V3 d = engine.develop(engine.exposure(pp));
                for (int c = 0; c < 3; c++) density[at * 3 + c] = d[c];
            }
    });
    out.axisMax = float(axisMax);
    out.gain.assign(nodes * 9, 0.0f);
    const double step = axisMax / (n - 1);
    constexpr double kRidge = 2e-3;  // keeps the inverse bounded where the film no longer answers (toe, shoulder)
    parallel([&](int b) {
        for (int gi = 0; gi < n; gi++)
            for (int r = 0; r < n; r++) {
                const int idx[3] = {r, gi, b};
                double J[3][3];  // J[density channel][code channel]
                for (int k = 0; k < 3; k++) {
                    int lo[3] = {idx[0], idx[1], idx[2]}, hi[3] = {idx[0], idx[1], idx[2]};
                    lo[k] = std::max(idx[k] - 1, 0);
                    hi[k] = std::min(idx[k] + 1, n - 1);
                    const size_t a = (size_t(lo[2]) * n + lo[1]) * n + lo[0], z = (size_t(hi[2]) * n + hi[1]) * n + hi[0];
                    const double span = (hi[k] - lo[k]) * step;
                    for (int c = 0; c < 3; c++) J[c][k] = (density[z * 3 + c] - density[a * 3 + c]) / span;
                }
                const size_t at = (size_t(b) * n + gi) * n + r;
                double A[3][3], inv[3][3];
                for (int p = 0; p < 3; p++)
                    for (int q = 0; q < 3; q++) {
                        A[p][q] = (p == q ? kRidge : 0.0);
                        for (int c = 0; c < 3; c++) A[p][q] += J[c][p] * J[c][q];
                    }
                if (!inverse3(A, inv)) continue;
                double m[3];
                for (int c = 0; c < 3; c++) m[c] = modulation(g, gs, c, density[at * 3 + c]);
                for (int p = 0; p < 3; p++)
                    for (int c = 0; c < 3; c++) {
                        double v = 0;
                        for (int q = 0; q < 3; q++) v += inv[p][q] * J[c][q];
                        out.gain[at * 9 + p * 3 + c] = float(v * m[c]);
                    }
            }
    });
    out.populations = gs.fields;
    // the kernel reads at most this many taps per side
    for (FilmGrainPopulation &f : out.populations) {
        if (f.radius > kFilmMaxRadius) {
            for (auto &taps : f.taps) {
                const int cut = f.radius - kFilmMaxRadius;
                taps.erase(taps.begin(), taps.begin() + cut);
                taps.resize(2 * kFilmMaxRadius + 1);
                double sum = 0;
                for (float v : taps) sum += v;
                for (float &v : taps) v = float(v / sum);
            }
            f.radius = kFilmMaxRadius;
        }
    }
    bool shouldTrace = false;
    {
        static std::mutex traceMu;
        static std::unordered_set<std::string> traced;
        std::lock_guard<std::mutex> lock(traceMu);
        shouldTrace = traced.insert(film.id).second;
    }
    if (shouldTrace) {
        // What the plan amounts to at 18 % grey: the developed density there, its published RMS granularity (a 48 um aperture) and the
        // per-pixel density deviation the clump fields give, then the display deviation of that in 8-bit sRGB.
        const fotufilm::V3 grey = engine.develop({1.0, 1.0, 1.0});
        double pixelD[3], apertureD[3], shown[3];
        for (int c = 0; c < 3; c++) {
            const double m = modulation(g, gs, c, grey[c]);
            double variance = 0;
            for (const FilmGrainPopulation &f : gs.fields) {
                double taps = 0;
                for (float t : f.taps[c]) taps += double(t) * t;
                variance += double(f.amp[c]) * f.amp[c] * taps * taps;  // two separable passes
            }
            pixelD[c] = m * std::sqrt(variance);
            apertureD[c] = m * g.strength * g.layerWeights[c];
            fotufilm::V3 up = grey, down = grey;
            up[c] += pixelD[c];
            down[c] -= pixelD[c];
            auto srgb = [](double v) {
                v = std::min(std::max(v, 0.0), 1.0);
                return 255.0 * (v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1 / 2.4) - 0.055);
            };
            shown[c] = 0.5 * std::fabs(srgb(engine.display(up)[c]) - srgb(engine.display(down)[c]));
        }
        {
            // the same along the neutral axis: display level (8-bit sRGB, green) and the per-pixel deviation of each channel, for 4x under to 4x over
            auto srgb8 = [](double v) {
                v = std::min(std::max(v, 0.0), 1.0);
                return 255.0 * (v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1 / 2.4) - 0.055);
            };
            char line[300];
            int at = std::snprintf(line, sizeof(line), "grain along grey (level: sigma R/G/B):");
            for (double stops : {-3.0, -2.0, -1.0, 0.0, 1.0, 2.0}) {
                const double e = std::pow(2.0, stops);
                const fotufilm::V3 d = engine.develop({e, e, e});
                const fotufilm::V3 lit = engine.display(d);
                double sigma[3];
                for (int c = 0; c < 3; c++) {
                    const double m = modulation(g, gs, c, d[c]);
                    double variance = 0;
                    for (const FilmGrainPopulation &f : gs.fields) {
                        double taps = 0;
                        for (float t : f.taps[c]) taps += double(t) * t;
                        variance += double(f.amp[c]) * f.amp[c] * taps * taps;
                    }
                    fotufilm::V3 up = d, down = d;
                    up[c] += m * std::sqrt(variance);
                    down[c] -= m * std::sqrt(variance);
                    sigma[c] = 0.5 * std::fabs(srgb8(engine.display(up)[c]) - srgb8(engine.display(down)[c]));
                }
                at += std::snprintf(line + at, sizeof(line) - size_t(at), " %.0f: %.1f/%.1f/%.1f;", srgb8(lit[1]), sigma[0], sigma[1], sigma[2]);
            }
            filmTrace("%s", line);
        }
        filmTrace(
                            "%s grain at grey: published RMS %.4f (size %.4f mm), density sigma per pixel %.4f/%.4f/%.4f vs 48 um aperture %.4f/%.4f/%.4f, display 8-bit sigma per pixel %.1f/%.1f/%.1f",
                            film.id.c_str(), g.strength, g.sizeMM, pixelD[0], pixelD[1], pixelD[2], apertureD[0], apertureD[1], apertureD[2], shown[0],
                            shown[1], shown[2]);
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s grain plan: %dx%d (%.1f px/mm), %zu population(s), gain lattice %d^3", film.id.c_str(), width,
                        height, pxPerMM, out.populations.size(), n);
    return true;
}

bool buildHalationPlan(const fotufilm::Engine &engine, const std::vector<double> &scene, int n, int width, int height, double frameMM,
                       FilmHalationPlan &out) {
    const fotufilm::Halation &hk = engine.film().halation;
    bool any = false;
    for (int j = 0; j < 3; j++) {
        out.source[j] = 0;
        for (int c = 0; c < 3; c++)
            if (hk.matrix[c][j] > 0) out.source[j] = 1;
        any = any || out.source[j] > 0;
    }
    if (!any || n < 3 || scene.size() != size_t(n) * n * n * 3) return false;
    const double pxPerMM = std::hypot(double(width), double(height)) / frameMM;
    const size_t nodes = size_t(n) * n * n;
    std::vector<double> exposure(nodes * 3);
    const unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    auto parallel = [&](auto &&body) {
        std::atomic<int> next{0};
        std::vector<std::thread> pool;
        auto worker = [&]() {
            for (int b; (b = next.fetch_add(1)) < n;) body(b);
        };
        for (unsigned t = 1; t < threads; t++) pool.emplace_back(worker);
        worker();
        for (auto &t : pool) t.join();
    };
    parallel([&](int b) {
        for (int g = 0; g < n; g++)
            for (int r = 0; r < n; r++) {
                const size_t at = (size_t(b) * n + g) * n + r;
                const fotufilm::V3 e = engine.exposure({scene[at * 3], scene[at * 3 + 1], scene[at * 3 + 2]});
                for (int c = 0; c < 3; c++) exposure[at * 3 + c] = e[c];
            }
    });
    out.exposure.assign(nodes * 3, 0.0f);
    out.inverse.assign(nodes * 9, 0.0f);
    std::vector<double> lin(n);
    for (int i = 0; i < n; i++) lin[i] = std::pow(i / double(n - 1), kFilmLinearExponent);
    parallel([&](int b) {
        for (int g = 0; g < n; g++)
            for (int r = 0; r < n; r++) {
                const int idx[3] = {r, g, b};
                const size_t at = (size_t(b) * n + g) * n + r;
                for (int c = 0; c < 3; c++) out.exposure[at * 3 + c] = float(std::log10(std::max(exposure[at * 3 + c], 1e-6)));
                double J[3][3];  // J[layer][light channel]
                for (int k = 0; k < 3; k++) {
                    int lo[3] = {idx[0], idx[1], idx[2]}, hi[3] = {idx[0], idx[1], idx[2]};
                    lo[k] = std::max(idx[k] - 1, 0);
                    hi[k] = std::min(idx[k] + 1, n - 1);
                    const size_t a = (size_t(lo[2]) * n + lo[1]) * n + lo[0], z = (size_t(hi[2]) * n + hi[1]) * n + hi[0];
                    const double span = lin[hi[k]] - lin[lo[k]];
                    for (int c = 0; c < 3; c++) J[c][k] = (exposure[z * 3 + c] - exposure[a * 3 + c]) / span;
                }
                double A[3][3], inv[3][3];
                for (int p = 0; p < 3; p++)
                    for (int q = 0; q < 3; q++) {
                        A[p][q] = (p == q ? 1e-6 : 0.0);
                        for (int c = 0; c < 3; c++) A[p][q] += J[c][p] * J[c][q];
                    }
                if (!inverse3(A, inv)) continue;
                for (int p = 0; p < 3; p++)
                    for (int c = 0; c < 3; c++) {
                        double v = 0;
                        for (int q = 0; q < 3; q++) v += inv[p][q] * J[c][q];
                        out.inverse[at * 9 + p * 3 + c] = float(v);
                    }
            }
    });
    // the grid the returned light lives on: cells no narrower than the active Gaussians and highlight geometry need
    double finestMM = 0.22;
    for (int k = 0; k < 3; k++) {
        if (hk.weights[0][k] + hk.weights[1][k] + hk.weights[2][k] > 1e-4) finestMM = std::min(finestMM, hk.sigmaMM[k]);
    }
    const double finest = finestMM * pxPerMM;
    int cell = kFilmMinCell;
    while (cell < 32 && finest / (2 * cell) >= 2) cell *= 2;
    out.cell = cell;
    out.gridW = (width + cell - 1) / cell;
    out.gridH = (height + cell - 1) / cell;
    out.pxPerMM = float(pxPerMM);
    for (int k = 0; k < 3; k++) {
        const double s = hk.sigmaMM[k] * pxPerMM;
        // the box mean and the bilinear read add about cell^2 / 4 of variance
        out.sigmaCells[k] = float(std::sqrt(std::max(s * s - cell * cell / 4.0, 0.25 * cell * cell)) / cell);
        for (int j = 0; j < 3; j++) out.weights[j][k] = float(hk.weights[j][k]);
    }
    for (int c = 0; c < 3; c++)
        for (int j = 0; j < 3; j++) out.matrix[c][j] = float(hk.matrix[c][j]);
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s halation plan: %dx%d, sigma %.3f/%.3f/%.3f mm = %.1f/%.1f/%.1f px, grid %dx%d of %d px, sources %g/%g/%g",
                        engine.film().id.c_str(), width, height, hk.sigmaMM[0], hk.sigmaMM[1], hk.sigmaMM[2], hk.sigmaMM[0] * pxPerMM,
                        hk.sigmaMM[1] * pxPerMM, hk.sigmaMM[2] * pxPerMM, out.gridW, out.gridH, cell, double(out.source[0]),
                        double(out.source[1]), double(out.source[2]));
    return true;
}
