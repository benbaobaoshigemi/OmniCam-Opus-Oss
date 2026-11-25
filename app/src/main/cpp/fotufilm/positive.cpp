// Direct viewing: a reversal film on a light table, a negative on a light box or scanner.
#include "internal.h"

namespace fotufilm {
namespace detail {

namespace {

class ReversalDirect : public FilmEngine {
public:
    ReversalDirect(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o)
        : FilmEngine(data, film, o.dir), basis_(*data_, film_), display_(*data_, data_->d65) {
        hasOffset_ = filmDensityOffset(film_, offset_);
        V3 mid{};
        for (int l = 0; l < 3; l++) mid[l] = film_.curves[l].dMin + film_.curves[l].dMax() - film_.curves[l].density(0.0);
        const V3 raw = rgbOf(basis_(mid));
        for (int c = 0; c < 3; c++) balance_[c] = 0.18 / std::max(raw[c], 1e-6);
        if (film_.reflectionPrint) {
            // Instant film etc.: a reflection print has no fixed transmission floor to anchor 18 % grey against, so the
            // balance is renormalised by the print's own white level (fotufilm's reversalBalance, reflection branch).
            V3 clear{};
            for (int l = 0; l < 3; l++) clear[l] = film_.curves[l].dMin;
            const V3 white = rgbOf(basis_(clear));
            double level = 0;
            for (int c = 0; c < 3; c++) level += kP3Luma[c] * (white[c] * balance_[c]);
            level = std::max(level, 1e-6);
            for (int c = 0; c < 3; c++) balance_[c] /= level;
        }
        shadowDetail_ = clampd(o.shadowDetail, 0.0, 1.0);
        highlightDetail_ = clampd(o.highlightDetail, 0.0, 1.0);
        if (shadowDetail_ > 0 || highlightDetail_ > 0) {
            V3 densMin{}, densMax{};
            for (int l = 0; l < 3; l++) {
                densMin[l] = film_.curves[l].dMin;
                densMax[l] = film_.curves[l].dMax();
            }
            black_ = viewKey(rawView(densMax));
            knee_ = kViewGrey - kLog10Of2;
            white_ = viewKey(rawView(densMin)) + kWhiteDensity;
            detail_ = true;
        }
    }

protected:
    V3 view(const V3 &density) const override {
        V3 rgb = rawView(density);
        if (detail_) {
            const double hold = detailHold(viewKey(rgb), kViewGrey, black_, knee_, white_, shadowDetail_, highlightDetail_);
            if (hold != 0.0) {
                const double gain = std::pow(10.0, hold);
                for (double &c : rgb) c *= gain;
            }
        }
        return rgb;
    }

private:
    static inline const double kViewGrey = -std::log10(0.18);
    static constexpr double kLog10Of2 = 0.3010299956639812;
    static constexpr double kWhiteDensity = 0.03;

    DensityBasis basis_;
    Display display_;
    bool hasOffset_ = false, detail_ = false;
    Spec offset_{};
    V3 balance_{};
    double shadowDetail_ = 0, highlightDetail_ = 0, black_ = 0, knee_ = 0, white_ = 0;

    static double viewKey(const V3 &display) {
        const double lum = kP3Luma[0] * display[0] + kP3Luma[1] * display[1] + kP3Luma[2] * display[2];
        return -std::log10(std::max(lum, 1e-6));
    }

    V3 rawView(const V3 &density) const {
        V3 rgb = rgbOf(basis_(density));
        for (int c = 0; c < 3; c++) rgb[c] = std::max(rgb[c] * balance_[c], 0.0);
        return rgb;
    }

    V3 rgbOf(const V3 &amounts) const {
        Spec t;
        transmittance(amounts, film_.dye, 0.0, hasOffset_ ? &offset_ : nullptr, t);
        return display_.rgb(t);
    }
};

class NegativeView : public FilmEngine {
public:
    NegativeView(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o)
        : FilmEngine(data, film, o.dir), display_(*data_, data_->d65) {
        hasOffset_ = filmDensityOffset(film_, offset_);
        bleach_ = film_.monochrome ? 0.0 : clampd(o.bleach, 0.0, 1.0);
        for (int l = 0; l < 3; l++) {
            dMin_[l] = film_.curves[l].dMin;
            range_[l] = film_.curves[l].dMax() - film_.curves[l].dMin;
        }
        const V3 base = rgbOf(dMin_, 0.0);
        const double peak = std::max(base[0], std::max(base[1], base[2]));
        for (int c = 0; c < 3; c++) divisor_[c] = o.negativeScanner ? std::max(base[c], 1e-6) : std::max(peak, 1e-6) / 0.9;
    }

protected:
    V3 view(const V3 &density) const override {
        V3 act{}, d{};
        for (int c = 0; c < 3; c++) {
            act[c] = (density[c] - dMin_[c]) / range_[c];
            d[c] = dMin_[c] + act[c] * range_[c];
        }
        V3 rgb = rgbOf(d, retainedSilverDensity(d, dMin_, bleach_));
        for (int c = 0; c < 3; c++) rgb[c] = std::max(rgb[c] / divisor_[c], 0.0);
        if (film_.monochrome) {
            const double avg = (rgb[0] + rgb[1] + rgb[2]) / 3.0;
            return {avg, avg, avg};
        }
        return rgb;
    }

private:
    Display display_;
    bool hasOffset_ = false;
    Spec offset_{};
    double bleach_ = 0;
    V3 dMin_{}, range_{}, divisor_{};

    V3 rgbOf(const V3 &density, double neutral) const {
        Spec t;
        transmittance(density, film_.dye, neutral, hasOffset_ ? &offset_ : nullptr, t);
        return display_.rgb(t);
    }
};

}  // namespace

std::unique_ptr<Engine> makePositive(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o) {
    return std::make_unique<ReversalDirect>(data, film, o);
}

std::unique_ptr<Engine> makeNegativeView(const std::shared_ptr<const Data> &data, const Stock &film, const Options &o) {
    return std::make_unique<NegativeView>(data, film, o);
}

}  // namespace detail
}  // namespace fotufilm
