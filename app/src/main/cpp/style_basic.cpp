// Basic: the scene front stage followed by a plain transfer curve on the P3 luma (looks/basic_<type>.bin, kind 12).
// Types: 0 pass-through (sRGB), 1 Rec.709, 2 power gamma, 3 standard (the ACR3 default tone curve, as Lightroom renders a DNG).
// Settings: gamma (1..4) for type 2.
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct BasicLook : Look {
    uint32_t type = 0;
    float gamma = 2.4f;
    std::vector<float> curve;  // type 3: ACR3 tone curve, uniform samples over [0, 1]
    float gainEv = 0;
};

double curveAt(const std::vector<float> &lut, double v) {
    double p = std::min(std::max(v, 0.0), 1.0) * double(lut.size() - 1);
    size_t i = std::min(size_t(std::floor(p)), lut.size() - 2);
    return lut[i] + (lut[i + 1] - lut[i]) * (p - double(i));
}

// Scene ProPhoto -> display-linear P3 through a transfer curve of the P3 luma. The colour is scaled by the luma ratio
// (hue preserving) and a pixel with a channel above 1 is pulled toward the grey of the same luma instead of clipped or
// scaled down with its peak, so a bright saturated colour keeps the curve's tone.
// Type 3: the curve is the ACR3 default tone curve (a linear result), on the luma like the other types so the chroma
// ratios are kept (the SDK's per-channel RGBTone changes them); the scene gain puts 18 % grey at display 0.18.
class BasicRenderer : public Renderer {
public:
    std::shared_ptr<const BasicLook> look;
    Mat3 ppToP3{};
    double gamma = 2.4, standardGain = 1, scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const BasicLook &l = *look;
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 p3 = xform(ppToP3, pp);
        for (double &v : p3) v = std::max(v, 0.0);
        if (l.type == 3)
            for (double &v : p3) v *= standardGain;
        double y = p3[0] * kP3Luma[0] + p3[1] * kP3Luma[1] + p3[2] * kP3Luma[2];
        if (!(y > 0)) {
            style::clampToDisplay({0, 0, 0}, out);
            return;
        }
        double factor;
        if (l.type == 3) {
            factor = curveAt(l.curve, y) / y;
        } else {
            double code;
            if (l.type == 0) code = srgbEncode(y);
            else if (l.type == 1) code = y < 0.018 ? 4.5 * y : 1.099 * std::pow(y, 0.45) - 0.099;
            else code = std::pow(y, 1.0 / gamma);
            factor = srgbDecode(std::min(code, 1.0)) / y;
        }
        Vec3 result = {p3[0] * factor, p3[1] * factor, p3[2] * factor};
        double peak = std::max(result[0], std::max(result[1], result[2]));
        if (peak > 1.0) {
            // Pull the colour toward the grey of the same luma until the peak fits, so the curve's tone survives.
            double white = factor * y;
            double k = white < 1.0 ? (1.0 - white) / (peak - white) : 0.0;
            for (double &v : result) v = white + (v - white) * k;
        }
        style::clampToDisplay(result, out);
    }
};

class BasicStyle : public Style {
public:
    const char *id() const override { return "basic"; }
    bool ownsKind(uint32_t kind) const override { return kind == 12; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<BasicLook>();
        bool ok = readValue(blob, at, d->type) && readValue(blob, at, d->gamma) && d->type <= 3 && d->gamma >= 1.0f &&
                  d->gamma <= 4.0f;
        uint32_t curveCount = 0;
        if (ok && d->type == 3)
            ok = readValue(blob, at, curveCount) && curveCount >= 2 && curveCount <= 65536 &&
                 readFloats(blob, at, curveCount, d->curve) && readValue(blob, at, d->gainEv) && std::fabs(d->gainEv) <= 4;
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const BasicLook>(file);
        auto renderer = std::make_unique<BasicRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->look = look;
        renderer->ppToP3 = mul(mul(inv(kP3ToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        renderer->gamma = look->type == 2 ? style::param(key, "gamma", look->gamma, 1.0f, 4.0f) : look->gamma;
        if (look->type == 3) renderer->standardGain = std::exp2(double(look->gainEv));
        return renderer;
    }
};

}  // namespace

const Style &basicStyle() {
    static const BasicStyle style;
    return style;
}

}  // namespace mastercolor
