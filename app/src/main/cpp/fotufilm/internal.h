// Shared pieces of the fotufilm engine: colour science constants, the scene-to-exposure stage, the film developer.
#pragma once

#include <algorithm>
#include <cmath>
#include <limits>

#include "fotufilm.h"

namespace fotufilm {
namespace detail {

inline double clampd(double v, double lo, double hi) { return std::min(std::max(v, lo), hi); }

inline V3 mul(const M3 &m, const V3 &v) {
    return {m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
}
inline M3 mul(const M3 &a, const M3 &b) {
    M3 o{};
    for (int r = 0; r < 3; r++)
        for (int c = 0; c < 3; c++) o[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
    return o;
}
bool invert(const M3 &m, M3 &out);

inline double dot(const Spec &a, const Spec &b) {
    double s = 0;
    for (int i = 0; i < N; i++) s += a[i] * b[i];
    return s;
}

// ---- colour science (ColorScience.swift)
extern const M3 kXyzToP3, kP3ToRec2020, kRec2020ToDomain, kDomainToRec2020, kBradford, kBradfordInv, kProPhotoD50ToRec2020;
extern const V3 kRec2020Luma, kP3Luma;
constexpr double kReconAnchor = 0.25;
constexpr double kProjectionMargin = 1.5 / 32;

// ---- scene light -> three layer exposures (+ the donor layer)
class Exposure {
public:
    Exposure(const Data &data, const Stock &stock);
    // Linear Rec.2020 scene; `gain` = 2^EV / 0.18. out[3] is the donor exposure when the stock has one. Returns the count.
    int layer(const V3 &rec2020, double gain, double out[4]) const;

private:
    struct Light {
        enum Kind { None, Reflectance, Extrapolated, Locus } kind = None;
        V3 colour{}, face{}, mirror{};
        double luminance = 0, upperShare = 0, share = 0;
        int lower = 0, upper = 0;
    };
    struct Hit {
        double alpha, beta;
        int lower, upper;
    };
    const Data &d_;
    const Stock &s_;
    double filterAt(int band) const { return s_.hasFilter ? s_.filterT[band] : 1.0; }
    double filterGain() const { return s_.hasFilter ? s_.filterGain : 1.0; }
    std::vector<int> bands_;
    std::vector<std::array<double, 2>> chroma_;
    void reflectance(const V3 &rgb, Spec &out) const;
    V3 spectral(const V3 &rgb) const;
    V3 anchored(const V3 &rgb) const;
    double donorAnchored(const V3 &rgb) const;
    bool boundary(double dx, double dy, Hit &best) const;
    Light classify(const V3 &rgb) const;
    V3 monochromatic(const Light &l) const;
    double donorMonochromatic(const Light &l) const;
    void domain(const V3 &point, double out[4]) const;
};

// ---- film developer: layer exposures -> developed densities, DIR couplers included
class Developer {
public:
    Developer(const Stock &film, double couplerScale);
    V3 density(const double exposure[4], int count) const;
    const Stock &film() const { return s_; }

private:
    static constexpr int kWarp = 128;
    const Stock &s_;
    double scale_ = 1;
    M3 K_{};
    V3 gamma_{}, dMin_{}, dRng_{}, dMax_{};
    bool hasInhibition_ = false, nonlinear_ = false;
    double warp_[3][kWarp] = {};
    V3 activation(const V3 &logE) const;
    double donorActivation(double logE) const;
    void buildWarp();
    double warp(int ch, double u) const;
};

// Common base of the media: scene -> layer exposures -> developed densities -> view().
class FilmEngine : public Engine {
public:
    FilmEngine(std::shared_ptr<const Data> data, const Stock &film, double couplerScale);
    V3 render(const V3 &prophoto) const final;
    const Stock &film() const final { return film_; }
    V3 exposure(const V3 &prophoto) const final {
        double e[4];
        exposure_.layer(mul(kProPhotoD50ToRec2020, prophoto), 1.0 / 0.18, e);
        return {e[0], e[1], e[2]};
    }
    V3 develop(const V3 &exposure) const final {
        const double e[4] = {exposure[0], exposure[1], exposure[2], 0};
        return dev_.density(e, 3);
    }
    V3 display(const V3 &density) const final { return view(density); }

protected:
    virtual V3 view(const V3 &density) const = 0;
    std::shared_ptr<const Data> data_;
    Stock film_;
    Exposure exposure_;
    Developer dev_;
};

std::unique_ptr<Engine> makeDigitalReference(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o);

// ---- spectra to display colour
// Linear Display P3 of a reflectance (or transmittance) spectrum under `illuminant`, Bradford-adapted to D65.
class Display {
public:
    Display(const Data &data, const Spec &illuminant);
    V3 rgb(const Spec &refl) const;

private:
    Spec wx_, wy_, wz_;
    double whiteY_ = 1;
    M3 m_{};
};

// 10^-(amounts . dyes + neutral [+ offset]) as a spectrum.
void transmittance(const V3 &amounts, const Spec dyes[3], double neutral, const Spec *offset, Spec &out);
// DensityOffset of a film with a separated base (None otherwise): (minDensity - dMin . dye).
bool filmDensityOffset(const Stock &film, Spec &out);
double retainedSilverDensity(const V3 &density, const V3 &dMin, double fraction);

// Status A densitometry: dye amounts -> Status A density, and back.
class StatusAUnmix {
public:
    StatusAUnmix(const Data &data, const Spec dyes[3]);
    V3 amounts(const V3 &target) const;

private:
    const Data &d_;
    Spec dyes_[3];
    M3 inverse_{};
    V3 read(const V3 &amounts) const;
};

// Colour negative -> print (paper or print film) -> display colour under the viewing light.
std::unique_ptr<Engine> makePrint(const std::shared_ptr<const Data> &data, const Stock &film, const PrintStock &print, const Options &o);
// Reversal film viewed directly, or a negative viewed on a light box / scanner.
std::unique_ptr<Engine> makePositive(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o);
std::unique_ptr<Engine> makeNegativeView(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o);

// Reversal film density -> neutral dye amounts (a reversal film is measured as a neutral wedge).
// A separated-base film reports its amounts directly (identity); a transmission (projected) film is unmixed by its Status A
// read (StatusAUnmix); a reflection-print reversal film (no separated base, printed as a reflection print -- instant film) has
// no such direct read, so each layer's neutral amount is fit as a cubic of that layer's own developed density, weighted by
// how much that exposure contributes to a neutral grey (fotufilm's neutralDensityBasis, reflection branch).
class DensityBasis {
public:
    DensityBasis(const Data &data, const Stock &film);
    V3 operator()(const V3 &density) const;

private:
    enum class Kind { Identity, StatusA, ReflectionFit } kind_ = Kind::Identity;
    std::unique_ptr<StatusAUnmix> unmix_;
    double fit_[3][4] = {};
};

double inhibitorRelease(double a, double gamma);
double curveRange(const Curve &c);
double logExposureForDensity(const Curve &c, double target);
double paperKey(const V3 &x);
double detailHold(double at, double mid, double black, double knee, double white, double shadows, double highlights);

// The film with the user's processing applied: push, age, long exposure.
bool applyProcessing(const Data &data, const Stock &base, const Options &o, Stock &out, std::string *error);

}  // namespace detail
}  // namespace fotufilm
