// Print chain: a developed negative (or, for positive paper, a reversal film) is printed on paper or print film and viewed
// under the viewing light.
#include "internal.h"

namespace fotufilm {
namespace detail {

namespace {

const double kLn10 = std::log(10.0);
const double kLog10Of2 = std::log10(2.0);

inline double pow10neg(double d) { return std::exp(-d * kLn10); }

// Print dye unmix: the print's dye amounts that read a given Status A density.
class PrintDyeUnmix {
public:
    PrintDyeUnmix(const Data &data, const Spec dyes[3]) {
        for (int i = 0; i < 3; i++) dyes_[i] = dyes[i];
        // Status A reads only a few bands; the rest weigh exactly zero, so only these are summed.
        for (int k = 0; k < N; k++) {
            double w[3];
            bool any = false;
            for (int c = 0; c < 3; c++) {
                double integral = 0;
                for (double v : data.statusA[c]) integral += v;
                w[c] = data.statusA[c][k] / integral;
                any = any || w[c] != 0;
            }
            if (!any) continue;
            band_[count_] = k;
            for (int c = 0; c < 3; c++) weight_[count_][c] = w[c];
            count_++;
        }
        M3 matrix{};
        for (int d = 0; d < 3; d++) {
            V3 a{0, 0, 0};
            a[d] = 1.0;
            const V3 col = read(a);
            for (int r = 0; r < 3; r++) matrix[r * 3 + d] = col[r];
        }
        invert(matrix, inverse_);
    }

    V3 read(const V3 &a) const {
        double s[3] = {0, 0, 0};
        for (int i = 0; i < count_; i++) {
            const int k = band_[i];
            const double t = pow10neg(a[0] * dyes_[0][k] + a[1] * dyes_[1][k] + a[2] * dyes_[2][k]);
            for (int c = 0; c < 3; c++) s[c] += t * weight_[i][c];
        }
        return {-std::log10(std::max(s[0], 1e-12)), -std::log10(std::max(s[1], 1e-12)), -std::log10(std::max(s[2], 1e-12))};
    }

    V3 amounts(const V3 &target) const {
        V3 a = mul(inverse_, target);
        for (int i = 0; i < 16; i++) {
            const V3 r = read(a);
            const V3 res = {r[0] - target[0], r[1] - target[1], r[2] - target[2]};
            if (std::max(std::fabs(res[0]), std::max(std::fabs(res[1]), std::fabs(res[2]))) < 1e-6) break;
            const V3 step = mul(inverse_, res);
            for (int c = 0; c < 3; c++) a[c] -= step[c];
        }
        return {std::max(a[0], 0.0), std::max(a[1], 0.0), std::max(a[2], 0.0)};
    }

private:
    Spec dyes_[3];
    int band_[N] = {};
    double weight_[N][3] = {};
    int count_ = 0;
    M3 inverse_{};
};

class PrintChain : public FilmEngine {
public:
    PrintChain(const std::shared_ptr<const Data> &data, const Stock &film, const PrintStock &ps, const Options &o)
        : FilmEngine(data, film, o.dir), ps_(ps), basis_(*data_, film_), receiverUnmix_(*data_, ps.analyticalDyes),
          display_(*data_, viewingLight(*data_, ps, o.viewingKelvin)), preflash_(std::max(o.printerPreflash, 0.0)) {
        reversal_ = film_.reversal;
        mono_ = film_.monochrome;
        bleach_ = (film_.reversal || mono_) ? 0.0 : clampd(o.bleach, 0.0, 1.0);
        for (int l = 0; l < 3; l++) dMin_[l] = film_.curves[l].dMin;
        for (int l = 0; l < 3; l++) dMax_[l] = film_.curves[l].dMax();
        hasOffset_ = filmDensityOffset(film_, offset_);
        dir_ = ps.positiveWorking() ? -1.0 : 1.0;
        midpoints();
        // the lamp and the exposure that renders the film's middle grey as the print's aim
        V3 mid0{};
        for (int l = 0; l < 3; l++) {
            const double formed = film_.curves[l].density(0.0);
            mid0[l] = reversal_ ? dMin_[l] + dMax_[l] - formed : formed;
        }
        const V3 mid = basisOf(mid0);
        const double neutral = retainedSilverDensity(mid, dMin_, bleach_);
        const bool printerHead = o.printerFilter && !ps.projected();
        if (!ps.projected()) {
            lamp_ = data_->enlarger3200;
        } else {
            const V3 aim = printingAim();
            Spec raw = releaseLamp(mid, neutral, aim);
            double peak = 1e-12;
            for (double v : raw) peak = std::max(peak, v);
            // printer lights: each beam scaled by 10^((points - 25) * 0.025) against the aim setup, which 25-25-25 is
            if (o.printerLights[0] != 25 || o.printerLights[1] != 25 || o.printerLights[2] != 25) {
                V3 gain{};
                for (int b = 0; b < 3; b++) gain[b] = std::pow(10.0, (clampd(o.printerLights[b], 1.0, 50.0) - 25.0) * 0.025);
                raw = releaseLamp(mid, neutral, aim, &gain);
            }
            for (int k = 0; k < N; k++) lamp_[k] = raw[k] / peak;
            midEnergy_ = {aim[0] / peak, aim[1] / peak, aim[2] / peak};
        }
        if (!ps.projected()) midEnergy_ = exposureOf(mid, lamp_, neutral);
        if (printerHead) {
            lamp_ = filteredLamp(o.magenta, o.yellow);
            midEnergy_ = exposureOf(mid, filteredLamp(0.4, 0.5), neutral);
        }
        if (o.shadowDetail > 0 || o.highlightDetail > 0) buildDetail(o.shadowDetail, o.highlightDetail);
    }

protected:
    V3 view(const V3 &density) const override {
        const V3 rel0 = readOf(density);
        V3 axis{};
        for (int c = 0; c < 3; c++) {
            double rel = rel0[c];
            if (preflash_ > 0) rel = std::log10(std::max(std::exp(rel * kLn10) + preflash_, 1e-12));
            axis[c] = xMid_[c] + dir_ * rel;
        }
        if (detail_) {
            const double key = paperKey(axis), grey = paperKey(xMid_);
            const double hold = detailHold(key, grey, black_, knee_, white_, shadowDetail_, highlightDetail_);
            for (double &a : axis) a -= hold;
        }
        V3 printed{};
        for (int c = 0; c < 3; c++) {
            const Curve &cv = printCurve(c);
            printed[c] = cv.density(axis[c]) - cv.dMin;
        }
        Spec t;
        if (mono_) {
            transmittance(printed, ps_.dyes, 0.0, nullptr, t);
            const V3 rgb = display_.rgb(t);
            const double avg = (rgb[0] + rgb[1] + rgb[2]) / 3.0;
            return {avg, avg, avg};
        }
        const V3 amounts = receiverUnmix_.amounts(printed);
        transmittance(amounts, ps_.analyticalDyes, 0.0, nullptr, t);
        return display_.rgb(t);
    }

private:
    const PrintStock &ps_;
    DensityBasis basis_;
    PrintDyeUnmix receiverUnmix_;
    Display display_;
    double preflash_ = 0, bleach_ = 0, dir_ = 1;
    bool reversal_ = false, mono_ = false, hasOffset_ = false, detail_ = false;
    const Curve &printCurve(int c) const { return ps_.curves[mono_ ? 1 : c]; }
    V3 dMin_{}, dMax_{}, xMid_{}, midEnergy_{};
    Spec offset_{}, lamp_{};
    double black_ = 0, knee_ = 0, white_ = 0, shadowDetail_ = 0, highlightDetail_ = 0;

    static const Spec &viewingLight(const Data &d, const PrintStock &ps, double kelvin) {
        if (kelvin > 0) {
            if (std::fabs(kelvin - 5003) < 50) return d.d50;
            if (std::fabs(kelvin - 6504) < 50) return d.daylight6504;
            if (std::fabs(kelvin - 2856) < 50) return d.tungsten2856;
        }
        return ps.projected() ? d.xenon : d.d50;
    }

    V3 basisOf(const V3 &d) const { return reversal_ ? basis_(d) : d; }

    // sens . (lamp * 10^-(density . dye + neutral + offset))
    V3 exposureOf(const V3 &density, const Spec &lamp, double neutral) const {
        V3 e{0, 0, 0};
        for (int k = 0; k < N; k++) {
            double d = density[0] * film_.dye[0][k] + density[1] * film_.dye[1][k] + density[2] * film_.dye[2][k] + neutral;
            if (hasOffset_) d += offset_[k];
            const double lt = lamp[k] * pow10neg(d);
            for (int c = 0; c < 3; c++) e[c] += ps_.sens[c][k] * lt;
        }
        return e;
    }

    V3 readOf(const V3 &density) const {
        const V3 d = basisOf(density);
        const V3 e = exposureOf(d, lamp_, retainedSilverDensity(d, dMin_, bleach_));
        V3 r{};
        for (int c = 0; c < 3; c++) r[c] = std::log10(std::max(e[c], 1e-12) / std::max(midEnergy_[c], 1e-12));
        return r;
    }

    Spec filteredLamp(double magenta, double yellow) const {
        Spec out;
        const double mg = clampd(magenta, 0.0, 1.2), yl = clampd(yellow, 0.0, 1.2);
        for (int k = 0; k < N; k++) {
            const double wl = 380.0 + 5.0 * k;
            const double m = std::exp(-0.5 * std::pow((wl - 550) / 45, 2));
            const double y = 1.0 / (1.0 + std::exp((wl - 500) / 12));
            out[k] = data_->planck3200[k] * pow10neg(mg * m + yl * y);
        }
        return out;
    }

    void midpoints() {
        const double mid = ps_.projected() ? 1.0 : 0.744;
        for (int c = 0; c < 3; c++) {
            const Curve &cv = printCurve(c);
            if (mono_) xMid_[c] = logExposureForDensity(cv, cv.dMin + mid);
            else if (ps_.hasLad) xMid_[c] = logExposureForDensity(cv, ps_.lad[c]);
            else xMid_[c] = logExposureForDensity(cv, cv.dMin + mid + (ps_.projected() ? 0.0 : ps_.trim[c]));
        }
    }

    V3 printingAim() const {
        V3 aim{};
        for (int c = 0; c < 3; c++) {
            const double ref = logExposureForDensity(ps_.curves[c], ps_.sensAboveMin ? ps_.curves[c].dMin + 1.0 : 1.0);
            aim[c] = std::pow(10.0, xMid_[c] - ref);
        }
        return aim;
    }

    // Three-beam release printer lamp: coordinate descent for beam weights that put the film's middle grey on the aim.
    Spec releaseLamp(const V3 &density, double neutral, const V3 &target, const V3 *beamGain = nullptr) const {
        static const double edges[3][4] = {{590, 610, 700, 730}, {490, 510, 580, 610}, {400, 410, 480, 500}};
        Spec beams[3];
        double cols[3][3], norms[3];
        for (int b = 0; b < 3; b++) {
            for (int k = 0; k < N; k++) {
                const double wl = 380.0 + 5.0 * k;
                const double t = clampd(std::min((wl - edges[b][0]) / (edges[b][1] - edges[b][0]), (edges[b][3] - wl) / (edges[b][3] - edges[b][2])), 0.0, 1.0);
                beams[b][k] = data_->enlarger3200[k] * t;
            }
            const V3 response = exposureOf(density, beams[b], neutral);
            norms[b] = std::max(std::max(response[0], std::max(response[1], response[2])), 1e-12);
            for (int c = 0; c < 3; c++) cols[b][c] = response[c] / norms[b];
        }
        double lights[3] = {1, 1, 1};
        double residual[3];
        for (int c = 0; c < 3; c++) residual[c] = cols[0][c] + cols[1][c] + cols[2][c] - target[c];
        for (int iter = 0; iter < 64; iter++)
            for (int ch = 0; ch < 3; ch++) {
                double cr = 0, cc = 0;
                for (int c = 0; c < 3; c++) {
                    cr += cols[ch][c] * residual[c];
                    cc += cols[ch][c] * cols[ch][c];
                }
                const double next = std::max(0.0, lights[ch] - cr / std::max(cc, 1e-12));
                for (int c = 0; c < 3; c++) residual[c] += cols[ch][c] * (next - lights[ch]);
                lights[ch] = next;
            }
        Spec lamp{};
        for (int b = 0; b < 3; b++)
            for (int k = 0; k < N; k++) lamp[k] += beams[b][k] * (lights[b] / norms[b]) * (beamGain ? (*beamGain)[b] : 1.0);
        return lamp;
    }

    void buildDetail(double shadows, double highlights) {
        static const double w[3] = {0.25, 0.65, 0.10};
        shadowDetail_ = clampd(shadows, 0.0, 1.0);
        highlightDetail_ = clampd(highlights, 0.0, 1.0);
        knee_ = white_ = 0;
        for (int r = 0; r < 3; r++) {
            const Curve &c = printCurve(r);
            const double lightest = c.dMin + 0.03;
            const double aim = c.density(xMid_[r]);
            const double bright = std::max(aim - 1.0 * kLog10Of2, lightest + 0.01);
            knee_ += w[r] * logExposureForDensity(c, bright);
            white_ += w[r] * logExposureForDensity(c, lightest);
        }
        const V3 clear = readOf(dMin_), dense = readOf(dMax_);
        V3 a{}, b{};
        for (int c = 0; c < 3; c++) {
            a[c] = xMid_[c] + dir_ * clear[c];
            b[c] = xMid_[c] + dir_ * dense[c];
        }
        black_ = std::max(paperKey(a), paperKey(b));
        detail_ = true;
    }
};

}  // namespace

std::unique_ptr<Engine> makePrint(const std::shared_ptr<const Data> &data, const Stock &film, const PrintStock &print, const Options &o) {
    return std::make_unique<PrintChain>(data, film, print, o);
}

}  // namespace detail
}  // namespace fotufilm
