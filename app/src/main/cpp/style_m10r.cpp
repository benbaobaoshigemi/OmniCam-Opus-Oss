// Leica M10-R in-camera JPEG look (firmware 40.26.35.51, kind 17, looks/leica_m10r_<mode>.bin).
// The file layout and reference pipeline are documented in tools/mastercolor/m10r_engine.py.
#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

constexpr size_t kToneLen = 10240;
constexpr size_t kDiffGammaLen = 32768;

struct M10rLook : Look {
    uint32_t satSteps = 0;            // 3 (color: low/medium/high) or 1 (monochrome: 0.0)
    float grayCode = 0;               // 14-bit WB camera code for 18 % grey
    float tLt = 0, tHt = 0;           // CA9 calibration temperatures (2850 K, 6807 K)
    Mat3 fm1{}, fm2{};                // Adobe Standard ForwardMatrix 1/2
    Mat3 cmLt{}, cmHt{};              // Firmware ColorMatrix_LT / HT (Q12 -> float)
    Mat3 xyzToPro{}, proToSrgb{};     // Firmware ProPhoto D50 <-> XYZ / linear sRGB
    Vec3 toneWeights{};               // TONE_CONTROL luminance weights [0.25, 0.6497, 0.1001]
    std::vector<uint16_t> tone;       // [3][10240], 32768 = 1.0
    std::vector<uint16_t> diffGamma;  // [32768], outputs [0..16383]
    Mat3 ycMatrix{}, ycInv{};         // BT.601 RGB <-> YCbCr
    std::vector<float> satScales;     // [satSteps]
    uint32_t hue = 0, sat = 0;
    float dcpT1 = 0, dcpT2 = 0;
    std::vector<float> hs1, hs2;      // M10-R Adobe Standard HueSatMap at dcpT1 / dcpT2
};

class M10rRenderer : public Renderer {
public:
    std::shared_ptr<const M10rLook> look;
    Mat3 xyzToCamera{}, cc0{}, srgbToP3{};
    double proPhotoScale = 1.0;
    int hue = 0, sat = 0;
    std::vector<float> hueSatTable;
    const uint16_t *tone = nullptr;   // chosen contrast step [10240]
    double satScale = 1.0;            // chosen saturation scale

    void render(const Vec3 &scene, float out[3]) const override {
        // F^-1: M10-R Adobe Standard HueSatMap inverted -> XYZ D50 -> 14-bit WB camera RGB.
        const Vec3 a = inverseHueSat(hue, sat, hueSatTable,
                                     {scene[0] * proPhotoScale, scene[1] * proPhotoScale, scene[2] * proPhotoScale});
        Vec3 cam = xform(xyzToCamera, xform(kProPhotoToXyz, a));
        for (int i = 0; i < 3; ++i) cam[i] = std::max(cam[i], 0.0);

        // CC0: 14-bit WB camera RGB -> ProPhoto D50 linear [0..16383].
        Vec3 pro = xform(cc0, cam);
        for (int i = 0; i < 3; ++i) pro[i] = std::min(std::max(pro[i], 0.0), 16383.0);

        // TONE_CONTROL: isotropic RGB gain indexed by Y >> 1.
        const double y = std::min(std::max(
            look->toneWeights[0] * pro[0] + look->toneWeights[1] * pro[1] + look->toneWeights[2] * pro[2], 0.0), 16383.0);
        const int tIdx = std::min(std::max(int(std::floor(y * 0.5)), 0), int(kToneLen) - 1);
        const double gain = double(tone[tIdx]) / 32768.0;
        Vec3 proT{};
        for (int i = 0; i < 3; ++i) proT[i] = std::min(std::max(pro[i] * gain, 0.0), 16383.0);

        // CC1 (ProPhoto -> linear sRGB) -> 15-bit DIFF_GAMMA LUT -> normalized [0..1].
        const Vec3 lin = xform(look->proToSrgb, proT);
        Vec3 rgb{};
        for (int i = 0; i < 3; ++i) {
            const double v = std::min(std::max(lin[i], 0.0), double(kDiffGammaLen - 1));
            const int i0 = std::min(int(std::floor(v)), int(kDiffGammaLen) - 2);
            const double f = v - i0;
            const double g = look->diffGamma[i0] * (1.0 - f) + look->diffGamma[i0 + 1] * f;
            rgb[i] = g / 16383.0;
        }

        // BT.601 YCbCr + COLOR_DIF_SUPPRESSION.
        if (std::fabs(satScale - 1.0) > 1e-6) {
            Vec3 ycc = xform(look->ycMatrix, rgb);
            ycc[1] *= satScale;
            ycc[2] *= satScale;
            rgb = xform(look->ycInv, ycc);
        }
        for (int i = 0; i < 3; ++i) rgb[i] = std::min(std::max(rgb[i], 0.0), 1.0);
        style::clampToDisplay(xform(srgbToP3, {srgbDecode(rgb[0]), srgbDecode(rgb[1]), srgbDecode(rgb[2])}), out);
    }
};

class M10rStyle : public Style {
public:
    const char *id() const override { return "m10r"; }
    bool ownsKind(uint32_t kind) const override { return kind == 17; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<M10rLook>();
        std::vector<float> tw;
        if (!readValue(blob, at, d->satSteps) || (d->satSteps != 1 && d->satSteps != 3) ||
            !readValue(blob, at, d->grayCode) || !(d->grayCode > 0) ||
            !readValue(blob, at, d->tLt) || !readValue(blob, at, d->tHt) ||
            !(d->tLt > 0 && d->tHt > 0 && d->tLt != d->tHt) ||
            !readMat(blob, at, d->fm1) || !readMat(blob, at, d->fm2) ||
            !readMat(blob, at, d->cmLt) || !readMat(blob, at, d->cmHt) ||
            !readMat(blob, at, d->xyzToPro) || !readMat(blob, at, d->proToSrgb) ||
            !readFloats(blob, at, 3, tw))
            return nullptr;
        d->toneWeights = {tw[0], tw[1], tw[2]};

        const size_t toneBytes = 3 * kToneLen * 2;
        const size_t dgBytes = kDiffGammaLen * 2;
        if (at + toneBytes + dgBytes > blob.size()) return nullptr;
        d->tone.resize(3 * kToneLen);
        std::memcpy(d->tone.data(), blob.data() + at, toneBytes);
        at += toneBytes;
        d->diffGamma.resize(kDiffGammaLen);
        std::memcpy(d->diffGamma.data(), blob.data() + at, dgBytes);
        at += dgBytes;

        if (!readMat(blob, at, d->ycMatrix) || !readFloats(blob, at, d->satSteps, d->satScales)) return nullptr;
        d->ycInv = inv(d->ycMatrix);

        const bool ok = readValue(blob, at, d->hue) && readValue(blob, at, d->sat) &&
                        d->hue >= 2 && d->hue <= 360 && d->sat >= 2 && d->sat <= 64 &&
                        readValue(blob, at, d->dcpT1) && readValue(blob, at, d->dcpT2) &&
                        d->dcpT1 > 0 && d->dcpT2 > 0 && d->dcpT1 != d->dcpT2 &&
                        readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs1) &&
                        readFloats(blob, at, size_t(d->hue) * d->sat * 3, d->hs2);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const M10rLook>(file);
        auto renderer = std::make_unique<M10rRenderer>();
        renderer->proPhotoScale = style::proPhotoGain(grey, key);
        if (!(renderer->proPhotoScale > 0)) return nullptr;

        const double w = std::min(std::max((1.0 / key.cct - 1.0 / look->dcpT1) / (1.0 / look->dcpT2 - 1.0 / look->dcpT1), 0.0), 1.0);
        renderer->hue = int(look->hue);
        renderer->sat = int(look->sat);
        renderer->hueSatTable.resize(look->hs1.size());
        for (size_t i = 0; i < look->hs1.size(); ++i) {
            renderer->hueSatTable[i] = float(look->hs1[i] * (1.0 - w) + look->hs2[i] * w);
        }
        Mat3 fm{};
        for (int i = 0; i < 9; ++i) fm[i] = look->fm1[i] * (1.0 - w) + look->fm2[i] * w;
        const double s = double(look->grayCode) / 0.18;
        renderer->xyzToCamera = mul(diag(s, s, s), inv(fm));
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);

        // Dynamic CA9 CC0 at frame CCT.
        const double gCm = std::min(std::max((1.0 / key.cct - 1.0 / look->tHt) / (1.0 / look->tLt - 1.0 / look->tHt), 0.0), 1.0);
        Mat3 ab{};
        for (int i = 0; i < 9; ++i) ab[i] = gCm * look->cmLt[i] + (1.0 - gCm) * look->cmHt[i];
        const Vec3 white = whiteXyz(key.cct);
        const Vec3 camW = xform(ab, white);
        const Mat3 asn = diag(camW[0] / camW[1], 1.0, camW[2] / camW[1]);
        const Mat3 ca = cat(kD50, white);
        renderer->cc0 = mul(look->xyzToPro, mul(inv(mul(ab, ca)), asn));

        const int contrast = std::min(std::max(int(std::lround(style::param(key, "contrast", 0.0f, -1.0f, 1.0f))), -1), 1) + 1;
        const int saturation = (look->satSteps == 3)
            ? std::min(std::max(int(std::lround(style::param(key, "saturation", 0.0f, -1.0f, 1.0f))), -1), 1) + 1
            : 0;
        renderer->tone = look->tone.data() + size_t(contrast) * kToneLen;
        renderer->satScale = double(look->satScales[saturation]);
        renderer->look = look;
        return renderer;
    }
};

}  // namespace

const Style &m10rStyle() {
    static const M10rStyle style;
    return style;
}

}  // namespace mastercolor
