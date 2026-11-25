// Leica M (Typ 240) film modes Standard, Vivid and Natural (kind 16, looks/leica_m240_<mode>.bin). The file layout and the tables'
// meaning are described in tools/mastercolor/m240_engine.py, which is the reference.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct M240Look : Look {
    uint32_t steps = 0;               // 5 (Standard: the menu's contrast and saturation steps) or 1 (Vivid, Natural)
    float grayCode = 0;               // the 12-bit code of the 18 % grey
    Mat3 fm1{}, fm2{}, colorcomp{};   // Adobe Standard ForwardMatrix1/2 and firmware sRGB colorcomp (group 1)
    std::vector<uint16_t> tone;       // [steps][4096], 2048 = 1.0
    std::vector<uint8_t> gamma;       // [4096]
    std::vector<uint8_t> lumisat;     // [steps][256], 128 = 1.0
    uint32_t hue = 0, sat = 0;
    float t1 = 0, t2 = 0;
    std::vector<float> hs1, hs2;      // the M240 Adobe Standard HueSatMap at t1 / t2
};

class M240Renderer : public Renderer {
public:
    std::shared_ptr<const M240Look> look;
    Mat3 xyzToCamera{}, srgbToP3{};
    double proPhotoScale = 1.0;
    int hue = 0, sat = 0;
    std::vector<float> hueSatTable;
    const uint16_t *tone = nullptr;      // the chosen contrast step
    const uint8_t *lumisat = nullptr;    // the chosen saturation step

    void render(const Vec3 &scene, float out[3]) const override {
        // F^-1: the M240 Adobe Standard HueSatMap inverted gives the colorimetric scene; its forward matrix gives the white-balanced camera RGB.
        const Vec3 a = inverseHueSat(hue, sat, hueSatTable, {scene[0] * proPhotoScale, scene[1] * proPhotoScale, scene[2] * proPhotoScale});
        Vec3 cam = xform(xyzToCamera, xform(kProPhotoToXyz, a));
        for (int i = 0; i < 3; ++i) cam[i] = std::min(std::max(cam[i], 0.0), 4095.0);
        // G: firmware sRGB colorcomp matrix -> tone gain on each channel -> gamma curve -> BT.601 chroma gain by luma.
        const Vec3 lin = xform(look->colorcomp, cam);
        Vec3 enc{};
        for (int i = 0; i < 3; ++i) {
            const int code = int(std::floor(std::min(std::max(lin[i], 0.0), 4095.0)));
            const int y = int(std::floor(std::min(double(code) * tone[code] / 2048.0, 4095.0)));
            enc[i] = look->gamma[y] / 255.0;
        }
        const double luma = 0.299 * enc[0] + 0.587 * enc[1] + 0.114 * enc[2];
        const double s = lumisat[std::min(std::max(int(std::floor(luma * 255.0)), 0), 255)] / 128.0;
        const double cb = (enc[2] - luma) * 0.564 * s, cr = (enc[0] - luma) * 0.713 * s;
        const double r = luma + 1.402 * cr, b = luma + 1.772 * cb;
        const double g = (luma - 0.299 * r - 0.114 * b) / 0.587;
        const Vec3 clipped = {std::min(std::max(r, 0.0), 1.0), std::min(std::max(g, 0.0), 1.0), std::min(std::max(b, 0.0), 1.0)};
        style::clampToDisplay(xform(srgbToP3, {srgbDecode(clipped[0]), srgbDecode(clipped[1]), srgbDecode(clipped[2])}), out);
    }
};

class M240Style : public Style {
public:
    const char *id() const override { return "m240"; }
    bool ownsKind(uint32_t kind) const override { return kind == 16; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<M240Look>();
        if (!readValue(blob, at, d->steps) || (d->steps != 1 && d->steps != 5) || !readValue(blob, at, d->grayCode) || !(d->grayCode > 0) ||
            !readMat(blob, at, d->fm1) || !readMat(blob, at, d->fm2) || !readMat(blob, at, d->colorcomp))
            return nullptr;
        const size_t toneBytes = size_t(d->steps) * 4096 * 2, lumiBytes = size_t(d->steps) * 256;
        if (at + toneBytes + 4096 + lumiBytes > blob.size()) return nullptr;
        d->tone.resize(size_t(d->steps) * 4096);
        std::memcpy(d->tone.data(), blob.data() + at, toneBytes);
        at += toneBytes;
        d->gamma.assign(blob.begin() + at, blob.begin() + at + 4096);
        at += 4096;
        d->lumisat.assign(blob.begin() + at, blob.begin() + at + lumiBytes);
        at += lumiBytes;
        const bool ok = readValue(blob, at, d->hue) && readValue(blob, at, d->sat) && d->hue >= 2 && d->hue <= 360 && d->sat >= 2 && d->sat <= 64 &&
                        readValue(blob, at, d->t1) && readValue(blob, at, d->t2) && d->t1 > 0 && d->t2 > 0 && d->t1 != d->t2 &&
                        readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs1) && readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs2);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const M240Look>(file);
        auto renderer = std::make_unique<M240Renderer>();
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
        const double s = double(look->grayCode) / 0.18;
        renderer->xyzToCamera = mul(diag(s, s, s), inv(fm));
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);
        // Standard: the menu's five steps, low .. high, stored -2 .. 2 (0 = the middle). Vivid and Natural have one fixed step.
        int contrast = 0, saturation = 0;
        if (look->steps == 5) {
            contrast = std::min(std::max(int(std::lround(style::param(key, "contrast", 0.0f, -2.0f, 2.0f))), -2), 2) + 2;
            saturation = std::min(std::max(int(std::lround(style::param(key, "saturation", 0.0f, -2.0f, 2.0f))), -2), 2) + 2;
        }
        renderer->tone = look->tone.data() + size_t(contrast) * 4096;
        renderer->lumisat = look->lumisat.data() + size_t(saturation) * 256;
        renderer->look = look;
        return renderer;
    }
};

}  // namespace

const Style &m240Style() {
    static const M240Style style;
    return style;
}

}  // namespace mastercolor
