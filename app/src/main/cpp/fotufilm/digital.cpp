// Digital reference: a colour negative read by a fixed receiver and shown on a screen (reference exposure or graded print).
#include "internal.h"

namespace fotufilm {
namespace detail {

namespace {

constexpr double kAnchorDensity = 0.744;
constexpr double kGradedAnchorVal = 0.46, kGradedBaseVal = 1.0, kGradedWhiteVal = 0.02;
const double kGradedReadPerVal = 0.73 / 0.54;
constexpr double kReferenceGrade = 2.0;
const double kGradedBaseRead = (kGradedBaseVal - kGradedAnchorVal) * kGradedReadPerVal;

Curve makeCurve(double dMin, double gamma, double toe, double toeW, double sh, double shW) {
    Curve c;
    c.dMin = dMin;
    c.gamma = gamma;
    c.toe = toe;
    c.toeW = toeW;
    c.sh = sh;
    c.shW = shW;
    return c;
}

double softplus(double x) { return x > 0 ? x + std::log1p(std::exp(-x)) : std::log1p(std::exp(x)); }

double gradedSlope(double grade) { return 2.894 * std::exp(0.225 * (clampd(grade, 0.0, 5.0) - kReferenceGrade)); }

double gradedTransmittance(double val, double grade) {
    const double k = gradedSlope(grade);
    const double vStar = 0.6955;
    const double pivot = kGradedAnchorVal - vStar / k;
    const double dMinEff = 0.004, dMaxEff = 2.295, aHl = 3.0, aSh = 6.0;
    double v = k * (val - pivot);
    v += 0.05 * 0.6 * std::tanh((v - vStar) / 0.6);
    const double v1 = dMinEff + softplus(aHl * (v - dMinEff)) / aHl;
    const double d = dMaxEff - softplus(aSh * (dMaxEff - v1)) / aSh;
    const double black = std::pow(10.0, -2.3);
    return clampd((std::pow(10.0, -d) - black) / (1 - black), 0.0, 1.0);
}

double gradedShadowRead(double relative, double black, double grade) {
    const double ratio = gradedSlope(grade) / gradedSlope(kReferenceGrade);
    if (ratio == 1) return relative;
    if (!(relative > 0)) return ratio * relative;
    if (!(relative < black && black > 0)) return relative;
    const double u = relative / black;
    return black * ratio * u / (1 + (ratio - 1) * u);
}

double stretchShadows(double v, double scale) { return v <= 0 ? v : v + (scale - 1) * v * (1 - std::exp(-v / 0.08)); }

class DigitalReference : public FilmEngine {
public:
    DigitalReference(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o)
        : FilmEngine(data, film, o.dir), style_(o.style == 1 ? 1 : 0), grade_(std::isfinite(o.grade) ? clampd(o.grade, 0.0, 5.0) : kReferenceGrade) {
        reference_ = film_.monochrome ? film_.paperCurve : makeCurve(0.07, 2.60, -0.52, 0.16, 0.42, 0.14);
        straight_ = makeCurve(0.0, 1.0, -0.8, 0.01, 0.9, 0.01);
        floor_ = std::pow(10.0, -(reference_.dMax() - reference_.dMin));
        anchor_ = logExposureForDensity(reference_, reference_.dMin + kAnchorDensity);
        baseTarget_ = logExposureForDensity(reference_, reference_.dMax() - 0.03) - anchor_;
        curve_ = style_ == 0 ? &reference_ : &straight_;
        xMid_ = logExposureForDensity(*curve_, curve_->dMin + kAnchorDensity);
        // receiver: edge paper bands -> density unmix
        const Data &d = *data_;
        double resp[9];
        for (int band = 0; band < 3; band++) {
            double wsum = 0;
            for (int k = 0; k < N; k++) wsum += d.edgeSens[band][k];
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int k = 0; k < N; k++) s += d.edgeSens[band][k] * d.kodakDyes[j][k];
                resp[band * 3 + j] = s / wsum;
            }
        }
        M3 inv{};
        invert({resp[0], resp[1], resp[2], resp[3], resp[4], resp[5], resp[6], resp[7], resp[8]}, inv);
        for (int r = 0; r < 3; r++) {
            const double sum = inv[r * 3] + inv[r * 3 + 1] + inv[r * 3 + 2];
            for (int c = 0; c < 3; c++) rows_[r * 3 + c] = inv[r * 3 + c] / sum;
        }
        hasOffset_ = film_.hasMin;
        if (hasOffset_) {
            V3 mins{film_.curves[0].dMin, film_.curves[1].dMin, film_.curves[2].dMin};
            for (int k = 0; k < N; k++)
                offset_[k] = film_.minDensity[k] - (mins[0] * film_.dye[0][k] + mins[1] * film_.dye[1][k] + mins[2] * film_.dye[2][k]);
        }
        V3 mid{film_.curves[0].density(0.0), film_.curves[1].density(0.0), film_.curves[2].density(0.0)};
        midEnergy_ = energy(mid);
        const double base = baseRead();
        shadowScale_ = style_ == 0 ? baseTarget_ / base : 1.0;
        levelScale_ = (style_ == 1 && !film_.reflectionPrint) ? kGradedBaseRead / base : 1.0;
        if (o.shadowDetail > 0 || o.highlightDetail > 0) buildDetail(o.shadowDetail, o.highlightDetail);
    }

protected:
    V3 view(const V3 &density) const override {
        V3 axis = axisOf(density);
        if (detail_) {
            const double hold = detailHold(paperKey(axis), xMid_, black_, knee_, white_, shadowDetail_, highlightDetail_);
            for (double &a : axis) a -= hold;
        }
        V3 out{};
        for (int c = 0; c < 3; c++) {
            const double dens = curve_->density(axis[c]) - curve_->dMin;
            out[c] = screen(dens, c);
        }
        V3 rgb = screenRgb(out);
        if (film_.monochrome) {
            const double avg = (rgb[0] + rgb[1] + rgb[2]) / 3.0;
            return {avg, avg, avg};
        }
        return rgb;
    }

private:
    int style_;
    double grade_;
    Curve reference_, straight_;
    const Curve *curve_ = nullptr;
    double floor_ = 0, anchor_ = 0, baseTarget_ = 0, xMid_ = 0, shadowScale_ = 1, levelScale_ = 1;
    bool detail_ = false;
    double shadowDetail_ = 0, highlightDetail_ = 0, black_ = 0, knee_ = 0, white_ = 0;
    M3 rows_{};
    bool hasOffset_ = false;
    Spec offset_{};
    V3 midEnergy_{};

    V3 axisOf(const V3 &density) const {
        V3 read = rel(density);
        if (style_ == 0)
            for (double &r : read) r = stretchShadows(r, shadowScale_);
        return {xMid_ + levelScale_ * read[0], xMid_ + levelScale_ * read[1], xMid_ + levelScale_ * read[2]};
    }

    void buildDetail(double shadows, double highlights) {
        shadowDetail_ = clampd(shadows, 0.0, 1.0);
        highlightDetail_ = clampd(highlights, 0.0, 1.0);
        V3 dMin{}, dMax{};
        for (int l = 0; l < 3; l++) {
            dMin[l] = film_.curves[l].dMin;
            dMax[l] = film_.curves[l].dMax();
        }
        black_ = std::max(paperKey(axisOf(dMin)), paperKey(axisOf(dMax)));
        if (style_ == 0) {
            const double lightest = reference_.dMin + 0.03;
            const double bright = std::max(reference_.dMin + kAnchorDensity - std::log10(2.0), lightest + 0.01);
            knee_ = logExposureForDensity(reference_, bright);
            white_ = logExposureForDensity(reference_, lightest);
        } else {
            auto luminance = [&](double d) { return screenRgb({d, d, d})[1]; };
            auto densityShowing = [&](double target) {
                double lo = 0.0, hi = kAnchorDensity;
                for (int i = 0; i < 40; i++) {
                    const double mid = 0.5 * (lo + hi);
                    if (luminance(mid) > target) lo = mid;
                    else hi = mid;
                }
                return 0.5 * (lo + hi);
            };
            const double grey = luminance(kAnchorDensity);
            const double shownKnee = densityShowing(std::min(grey * 2.0, 0.99));
            const double paperWhite = std::pow(10.0, -0.03);
            const double shownWhite = luminance(0.0) > paperWhite ? densityShowing(paperWhite) : 0.0;
            knee_ = logExposureForDensity(*curve_, curve_->dMin + shownKnee);
            white_ = logExposureForDensity(*curve_, curve_->dMin + shownWhite);
        }
        detail_ = std::isfinite(knee_) && std::isfinite(white_) && std::isfinite(black_) && knee_ > white_;
    }

    double neutralDensity(double logE) const {
        return (film_.curves[0].density(logE) + film_.curves[1].density(logE) + film_.curves[2].density(logE)) / 3.0;
    }

    double baseRead() const {
        if (film_.monochrome) {
            const double dMin = (film_.curves[0].dMin + film_.curves[1].dMin + film_.curves[2].dMin) / 3.0;
            return std::max(neutralDensity(0.0) - dMin, 0.05);
        }
        return std::max(film_.curves[1].density(0.0) - film_.curves[1].dMin, 0.05);
    }

    V3 energy(const V3 &density) const {
        V3 out{};
        double t[N];
        for (int k = 0; k < N; k++) {
            double dd = density[0] * film_.dye[0][k] + density[1] * film_.dye[1][k] + density[2] * film_.dye[2][k];
            if (hasOffset_) dd += offset_[k];
            t[k] = std::pow(10.0, -dd);
        }
        for (int c = 0; c < 3; c++) {
            double s = 0;
            for (int k = 0; k < N; k++) s += data_->edgeSens[c][k] * t[k];
            out[c] = s;
        }
        return out;
    }

    V3 rel(const V3 &density) const {
        if (film_.monochrome) {
            const double mid = neutralDensity(0.0);
            V3 r{};
            for (int c = 0; c < 3; c++) {
                const Curve &cv = film_.curves[c];
                const double d = clampd(density[c], cv.dMin, cv.dMax());
                r[c] = mid - neutralDensity(logExposureForDensity(cv, d));
            }
            return r;
        }
        const V3 e = energy(density);
        V3 r{};
        for (int c = 0; c < 3; c++) r[c] = std::log10(std::max(e[c], 1e-12) / std::max(midEnergy_[c], 1e-12));
        return {rows_[0] * r[0] + rows_[1] * r[1] + rows_[2] * r[2], rows_[3] * r[0] + rows_[4] * r[1] + rows_[5] * r[2],
                rows_[6] * r[0] + rows_[7] * r[1] + rows_[8] * r[2]};
    }

    // density of one channel on the screen curve -> display value (before rgb mapping, see screenRgb)
    double screen(double dens, int) const { return dens; }

    V3 screenRgb(const V3 &density) const {
        V3 out{};
        for (int c = 0; c < 3; c++) {
            if (style_ == 0) {
                const double t = std::pow(10.0, -density[c]);
                out[c] = std::max((t - floor_) / (1 - floor_), 0.0);
                continue;
            }
            const double relative = density[c] - kAnchorDensity;
            const double val = relative / kGradedReadPerVal + kGradedAnchorVal;
            const double highlight = gradedTransmittance(val, grade_);
            const double graded = gradedShadowRead(relative, kGradedBaseRead, grade_);
            const double shadowRead = stretchShadows(graded, baseTarget_ / kGradedBaseRead);
            const double sd = reference_.density(anchor_ + shadowRead) - reference_.dMin;
            const double shadow = std::max((std::pow(10.0, -sd) - floor_) / (1 - floor_), 0.0);
            const double t = clampd((relative + 0.04) / 0.08, 0.0, 1.0);
            const double blend = t * t * (3 - 2 * t);
            out[c] = highlight * (1 - blend) + shadow * blend;
        }
        return out;
    }
};

}  // namespace

std::unique_ptr<Engine> makeDigitalReference(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o) {
    return std::make_unique<DigitalReference>(data, film, o);
}

}  // namespace detail
}  // namespace fotufilm
