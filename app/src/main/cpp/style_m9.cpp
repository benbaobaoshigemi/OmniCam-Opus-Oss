// Leica M9 (firmware 1.216) colour engine (kind 10, looks/leica_m9.bin). The file layout and the colour kernel are described in
// tools/mastercolor/m9_engine.py, which is the reference.
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct M9Look : Look {
    float inputScale = 1.0f;
    Mat3 fm1{}, fm2{};              // Adobe Standard forward matrices at t1 / t2: XYZ D50 = FM * white-balanced camera
    std::vector<int32_t> records;   // [saturation 5][A, D65][3x3], 1.0 = 8192
    std::vector<uint8_t> curves;    // [contrast 5][2048], sRGB encoded
    uint32_t hue = 0, sat = 0;
    float t1 = 0, t2 = 0;
    std::vector<float> hs1, hs2;    // the M9 Adobe Standard HueSatMap at t1 / t2
};

class M9Renderer : public Renderer {
public:
    std::shared_ptr<const M9Look> look;
    Mat3 xyzToCamera{}, srgbToP3{};
    double proPhotoScale = 1.0;
    int hue = 0, sat = 0;
    std::vector<float> hueSatTable;
    const int32_t *records = nullptr;   // the chosen saturation step: [A, D65][3x3]
    const uint8_t *curve = nullptr;     // the chosen contrast step

    void render(const Vec3 &scene, float out[3]) const override {
        // F^-1: the M9 Adobe Standard map inverted gives the colorimetric scene; its forward matrix gives the camera RGB.
        const Vec3 a = inverseHueSat(hue, sat, hueSatTable, {scene[0] * proPhotoScale, scene[1] * proPhotoScale, scene[2] * proPhotoScale});
        const Vec3 cam = xform(xyzToCamera, xform(kProPhotoToXyz, a));
        int px[3];
        for (int i = 0; i < 3; ++i) px[i] = int(std::floor(std::min(std::max(cam[i], 0.0), 1.0) * 16383.0));
        // G: the firmware kernel. R >= G takes the illuminant-A matrix, otherwise the D65 one.
        const int32_t *m = records + (px[0] >= px[1] ? 0 : 9);
        Vec3 encoded{};
        for (int i = 0; i < 3; ++i) {
            int32_t value = 0;
            for (int j = 0; j < 3; ++j) value += m[i * 3 + j] * px[j];
            // Floor division matches the DSP's signed high word on every host compiler.
            const int index = std::min(std::max(int(std::floor(value / 65536.0)), 0), 2047);
            encoded[i] = curve[index] / 255.0;
        }
        style::clampToDisplay(xform(srgbToP3, {srgbDecode(encoded[0]), srgbDecode(encoded[1]), srgbDecode(encoded[2])}), out);
    }
};

class M9Style : public Style {
public:
    const char *id() const override { return "m9"; }
    bool ownsKind(uint32_t kind) const override { return kind == 10; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<M9Look>();
        constexpr size_t kCurveBytes = 5 * 2048;
        bool ok = readValue(blob, at, d->inputScale) && d->inputScale > 0.0f && readMat(blob, at, d->fm1) && readMat(blob, at, d->fm2) &&
                  readInts(blob, at, 5 * 2 * 9, d->records) && at + kCurveBytes <= blob.size();
        if (!ok) return nullptr;
        d->curves.assign(blob.begin() + at, blob.begin() + at + kCurveBytes);
        at += kCurveBytes;
        ok = readValue(blob, at, d->hue) && readValue(blob, at, d->sat) && d->hue >= 2 && d->hue <= 360 && d->sat >= 2 && d->sat <= 64 &&
             readValue(blob, at, d->t1) && readValue(blob, at, d->t2) && d->t1 > 0 && d->t2 > 0 && d->t1 != d->t2 &&
             readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs1) && readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs2);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const M9Look>(file);
        auto renderer = std::make_unique<M9Renderer>();
        renderer->proPhotoScale = style::proPhotoGain(grey, key);
        if (!(renderer->proPhotoScale > 0)) return nullptr;
        // The two calibration illuminants interpolate in inverse Kelvin: the HueSatMap and the forward matrix.
        const double w = std::min(std::max((1.0 / key.cct - 1.0 / look->t1) / (1.0 / look->t2 - 1.0 / look->t1), 0.0), 1.0);
        renderer->hue = int(look->hue);
        renderer->sat = int(look->sat);
        renderer->hueSatTable.resize(look->hs1.size());
        for (size_t i = 0; i < look->hs1.size(); ++i) renderer->hueSatTable[i] = float(look->hs1[i] * (1.0 - w) + look->hs2[i] * w);
        Mat3 fm;
        for (int i = 0; i < 9; ++i) fm[i] = look->fm1[i] * (1.0 - w) + look->fm2[i] * w;
        const double s = double(look->inputScale);
        renderer->xyzToCamera = mul(diag(s, s, s), inv(fm));
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);
        // Contrast and saturation: the menu's five steps, low .. high, stored -2 .. 2 (0 = the middle).
        const int contrast = std::min(std::max(int(std::lround(style::param(key, "contrast", 0.0f, -2.0f, 2.0f))), -2), 2) + 2;
        const int saturation = std::min(std::max(int(std::lround(style::param(key, "saturation", 0.0f, -2.0f, 2.0f))), -2), 2) + 2;
        renderer->records = look->records.data() + saturation * 18;
        renderer->curve = look->curves.data() + contrast * 2048;
        renderer->look = look;
        return renderer;
    }
};

}  // namespace

const Style &m9Style() {
    static const M9Style style;
    return style;
}

}  // namespace mastercolor
