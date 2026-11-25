// Panasonic Lumix S9 Photo Styles: S9 white-balanced camera RGB, per-channel curve, two 33^3 tables blended by colour
// temperature (looks/lumix_<style>.bin, kind 1). tools/mastercolor/looks.py is the reference.
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

// LumixColorTemperature.coordinateForKelvin / highWeight.
double lumixHighWeight(double kelvin) {
    const double kp[4] = {3000, 5000, 6500, 7500}, cp[4] = {256, 512, 618, 725};
    int seg = kelvin < kp[1] ? 0 : kelvin < kp[2] ? 1 : 2;
    double c = cp[seg] + (kelvin - kp[seg]) * (cp[seg + 1] - cp[seg]) / (kp[seg + 1] - kp[seg]);
    int coordinate = int(std::min(std::max(c, 0.0), 65535.0));
    return std::min(std::max((coordinate - 256) / 256.0, 0.0), 1.0);
}

struct LumixLook : Look {
    float grey = 0, gain = 1, clip = 1, il1 = 0, il2 = 0;
    Mat3 cm1{}, cm2{};
    std::vector<float> curve, low, high;  // 2048; 33^3 x 3 [B][G][R][3]
};

class LumixRenderer : public Renderer {
public:
    std::shared_ptr<const LumixLook> look;
    Mat3 toS9{}, srgbToP3{};
    double camScale = 1, highWeight = 0, outputClip = 1, scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const LumixLook &l = *look;
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 cam = xform(toS9, pp), shaped, lin;
        for (int c = 0; c < 3; c++) {
            double v = std::min(std::max(cam[c] * camScale, 0.0), 1.0) * 2047.0;
            int lower = std::min(int(std::floor(v)), 2046);
            shaped[c] = l.curve[lower] + (l.curve[lower + 1] - l.curve[lower]) * (v - lower);
        }
        Vec3 lo = cube(l.low, 33, shaped), hi = cube(l.high, 33, shaped);
        for (int c = 0; c < 3; c++) {
            double e = std::min(std::max(lo[c] * (1 - highWeight) + hi[c] * highWeight, 0.0), outputClip);
            lin[c] = srgbDecode(std::floor(e * 4095.0) / 4095.0);
        }
        style::clampToDisplay(xform(srgbToP3, lin), out);
    }
};

class LumixStyle : public Style {
public:
    const char *id() const override { return "lumix"; }
    bool ownsKind(uint32_t kind) const override { return kind == 1; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        constexpr size_t kCube33 = size_t(33) * 33 * 33 * 3;
        auto d = std::make_shared<LumixLook>();
        bool ok = readValue(blob, at, d->grey) && readValue(blob, at, d->gain) && readValue(blob, at, d->clip) &&
                  readValue(blob, at, d->il1) && readValue(blob, at, d->il2) && readMat(blob, at, d->cm1) &&
                  readMat(blob, at, d->cm2) && readHalves(blob, at, 2048, d->curve) &&
                  readHalves(blob, at, kCube33, d->low) && readHalves(blob, at, kCube33, d->high) && d->grey > 0;
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const LumixLook>(file);
        auto renderer = std::make_unique<LumixRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->look = look;
        renderer->outputClip = look->clip;
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);
        renderer->toS9 = prophotoToCamera(look->il1, look->il2, look->cm1, look->cm2, key.cct);
        renderer->camScale = look->grey / 0.18 * look->gain;
        renderer->highWeight = lumixHighWeight(key.cct);
        return renderer;
    }
};

}  // namespace

const Style &lumixStyle() {
    static const LumixStyle style;
    return style;
}

}  // namespace mastercolor
