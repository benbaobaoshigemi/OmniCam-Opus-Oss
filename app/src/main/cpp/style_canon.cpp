// Canon EOS R5 Picture Styles (master_canon.h): EOS R5 camera RGB through the style's integer recipe and ICC profile
// (looks/canon_<style>.bin, kind 7).
#include <memory>

#include "master_canon.h"
#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct CanonLook : Look {
    float engineEv = 0, il1 = 0, il2 = 0;
    Mat3 cm1{}, cm2{};
    canon::Style style;
};

class CanonRenderer : public Renderer {
public:
    std::shared_ptr<const CanonLook> look;
    Mat3 toS9{}, srgbToP3{};
    double camScale = 1, scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 cam = xform(toS9, pp);
        double c[3] = {cam[0] * camScale, cam[1] * camScale, cam[2] * camScale}, lin[3];
        canon::render(look->style, c, lin);
        style::clampToDisplay(xform(srgbToP3, {lin[0], lin[1], lin[2]}), out);
    }
};

class CanonStyle : public Style {
public:
    const char *id() const override { return "canon"; }
    bool ownsKind(uint32_t kind) const override { return kind == 7; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<CanonLook>();
        canon::Style &cs = d->style;
        uint32_t hasIcc = 0;
        bool ok = readValue(blob, at, d->engineEv) && readValue(blob, at, d->il1) && readValue(blob, at, d->il2) &&
                  readMat(blob, at, d->cm1) && readMat(blob, at, d->cm2) && readInts(blob, at, 0x168, cs.words) &&
                  readInts(blob, at, size_t(1024) * 324, cs.atlas) && readValue(blob, at, hasIcc);
        cs.hasIcc = hasIcc != 0;
        if (ok && cs.hasIcc) {
            canon::Icc &icc = cs.icc;
            ok = readValue(blob, at, icc.grid) && readValue(blob, at, icc.n) && readValue(blob, at, icc.m) &&
                 icc.grid >= 2 && icc.grid <= 65 && icc.n >= 2 && icc.n <= 65536 && icc.m >= 2 && icc.m <= 65536 &&
                 readFloats(blob, at, size_t(3) * icc.n, icc.inputCurves) &&
                 readFloats(blob, at, size_t(icc.grid) * icc.grid * icc.grid * 3, icc.clut) &&
                 readFloats(blob, at, size_t(3) * icc.m, icc.outputCurves);
            for (int i = 0; ok && i < 9; i++) ok = readValue(blob, at, icc.xyzToTarget[i]);
            for (int i = 0; ok && i < 3; i++) ok = readValue(blob, at, icc.white[i]);
            uint32_t sizes[3] = {};
            for (int i = 0; ok && i < 3; i++) ok = readValue(blob, at, sizes[i]) && sizes[i] >= 2 && sizes[i] <= 65536;
            for (int i = 0; ok && i < 3; i++) ok = readFloats(blob, at, sizes[i], icc.trc[i]);
        }
        ok = ok && (cs.words[0xf] == 1 || cs.hasIcc);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const CanonLook>(file);
        auto renderer = std::make_unique<CanonRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->look = look;
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);
        renderer->toS9 = prophotoToCamera(look->il1, look->il2, look->cm1, look->cm2, key.cct);  // EOS R5 ColorMatrix pair
        renderer->camScale = std::exp2(double(look->engineEv));
        return renderer;
    }
};

}  // namespace

const Style &canonStyle() {
    static const CanonStyle style;
    return style;
}

}  // namespace mastercolor
