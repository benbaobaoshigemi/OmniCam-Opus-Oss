// darktable tone mappers (master_dt.h): Sigmoid (kind 3), AgX (kind 4) and Filmic (kind 5), on linear BT.2020.
// Looks: looks/sigmoid.bin, agx.bin, filmic.bin; their files hold the defaults of the settings.
//   sigmoid  contrast (0.5..5), hue (0..100 %)
//   agx      black, white (EV), toe, shoulder
//   filmic   black, white (EV)
#include <memory>

#include "master_dt.h"
#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct DarktableLook : Look {
    int kind = 0;
    dt::SigmoidParams sigmoid;
    dt::AgxParams agx;
    float filmicBlack = -7.65f, filmicWhite = 4.56f;
};

class DarktableRenderer : public Renderer {
public:
    int kind = 0;
    Mat3 ppToBt2020{}, bt2020ToP3{};
    dt::SigmoidParams sigmoid;
    dt::AgxParams agx;
    dt::FilmicUniforms filmic;
    double scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 x = xform(ppToBt2020, pp);
        double y[3];
        if (kind == 3) dt::sigmoid(sigmoid, x.data(), y);
        else if (kind == 4) dt::agx(agx, x.data(), y);
        else dt::filmic(filmic, x.data(), y);
        style::clampToDisplay(xform(bt2020ToP3, {y[0], y[1], y[2]}), out);
    }
};

class DarktableStyle : public Style {
public:
    const char *id() const override { return "darktable"; }
    bool ownsKind(uint32_t kind) const override { return kind >= 3 && kind <= 5; }

    std::shared_ptr<const Look> parse(uint32_t kind, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<DarktableLook>();
        d->kind = int(kind);
        bool ok;
        if (kind == 3) {
            ok = readValue(blob, at, d->sigmoid.contrast) && readValue(blob, at, d->sigmoid.hue) &&
                 d->sigmoid.contrast > 0 && d->sigmoid.hue >= 0 && d->sigmoid.hue <= 1;
        } else if (kind == 4) {
            ok = readValue(blob, at, d->agx.black) && readValue(blob, at, d->agx.white) &&
                 readValue(blob, at, d->agx.toe) && readValue(blob, at, d->agx.shoulder);
        } else {
            ok = readValue(blob, at, d->filmicBlack) && readValue(blob, at, d->filmicWhite) &&
                 d->filmicBlack < d->filmicWhite;
        }
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const DarktableLook>(file);
        auto renderer = std::make_unique<DarktableRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->kind = look->kind;
        renderer->ppToBt2020 = mul(mul(inv(kBt2020ToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        renderer->bt2020ToP3 = mul(inv(kP3ToXyz), kBt2020ToXyz);
        if (look->kind == 3) {
            renderer->sigmoid.contrast = style::param(key, "contrast", look->sigmoid.contrast, 0.5f, 5.0f);
            renderer->sigmoid.hue = style::param(key, "hue", look->sigmoid.hue * 100, 0.0f, 100.0f) / 100.0f;
        } else if (look->kind == 4) {
            renderer->agx.black = style::param(key, "black", look->agx.black, -20.0f, -0.5f);
            renderer->agx.white = style::param(key, "white", look->agx.white, 0.5f, 20.0f);
            renderer->agx.toe = style::param(key, "toe", look->agx.toe, 0.1f, 10.0f);
            renderer->agx.shoulder = style::param(key, "shoulder", look->agx.shoulder, 0.1f, 10.0f);
        } else {
            renderer->filmic = dt::filmicUniforms(style::param(key, "black", look->filmicBlack, -16.0f, -1.0f),
                                                  style::param(key, "white", look->filmicWhite, 0.5f, 12.0f));
        }
        return renderer;
    }
};

}  // namespace

const Style &darktableStyle() {
    static const DarktableStyle style;
    return style;
}

}  // namespace mastercolor
