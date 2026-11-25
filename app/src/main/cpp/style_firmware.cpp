// Camera firmware adapter (PhotonCamera): the Fuji Bezier geometry with a 17^3 lattice (kind 11, looks/fuji_*.bin).
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

struct FirmwareLook : Look {
    std::vector<float> firmware;
};

// Photon firmware adapter: the Fuji Bezier geometry.
double firmwareBezier(const float *v, const float *edges, int segments, double x) {
    int k = 0;
    for (int j = 1; j < segments; ++j) if (x >= edges[j]) k = j;
    double t = (x - edges[k]) / (edges[k + 1] - edges[k]), a = 1 - t;
    return v[k * 3] * a * a * a + 3 * v[k * 3 + 1] * t * a * a + 3 * v[k * 3 + 2] * t * t * a + v[k * 3 + 3] * t * t * t;
}

class FirmwareRenderer : public Renderer {
public:
    std::shared_ptr<const FirmwareLook> look;
    Mat3 toS9{}, srgbToP3{};
    double scale = 1;

    void render(const Vec3 &scene, float out[3]) const override {
        const FirmwareLook &l = *look;
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 input = xform(toS9, pp), encoded{};
        {
            const float *v = l.firmware.data();
            constexpr float edges[14] = {0, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 6144, 8192, 12288, 16384};
            double lum = input[0] * v[162] + input[1] * v[163] + input[2] * v[164];
            double x = std::min(std::max(lum * 16384, 0.0), 16384.0);
            double gain = lum > 0 ? x / 16384 * firmwareBezier(v, edges, 13, x) / lum : 1;
            Vec3 mixed{}, shaped{}, code{};
            for (int i = 0; i < 3; ++i) {
                for (int j = 0; j < 3; ++j) mixed[i] += v[144 + i * 3 + j] * input[j] * gain;
                shaped[i] = firmwareBezier(v + 40 + i * 31, v + 133, 10, std::min(std::max(mixed[i] * 16384, 0.0), 16384.0));
            }
            int base[3];
            double frac[3];
            for (int i = 0; i < 3; ++i) {
                for (int j = 0; j < 3; ++j) code[i] += v[153 + i * 3 + j] * shaped[j];
                code[i] = std::min(std::max(code[i], 0.0), 1.0) * 4095;
                base[i] = std::min(int(std::floor(code[i] / 256)), 15);
                frac[i] = (code[i] - base[i] * 256) / (base[i] == 15 ? 255 : 256);
            }
            for (int z = 0; z < 2; ++z) for (int y = 0; y < 2; ++y) for (int x2 = 0; x2 < 2; ++x2) {
                double weight = (x2 ? frac[0] : 1 - frac[0]) * (y ? frac[1] : 1 - frac[1]) * (z ? frac[2] : 1 - frac[2]);
                int at = 165 + (((base[2] + z) * 17 + base[1] + y) * 17 + base[0] + x2) * 3;
                for (int c = 0; c < 3; ++c) encoded[c] += weight * v[at + c];
            }
        }
        style::clampToDisplay(xform(srgbToP3, {srgbDecode(encoded[0]), srgbDecode(encoded[1]), srgbDecode(encoded[2])}), out);
    }
};

class FirmwareStyle : public Style {
public:
    const char *id() const override { return "firmware"; }
    bool ownsKind(uint32_t kind) const override { return kind == 11; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<FirmwareLook>();
        const bool ok = readFloats(blob, at, 165 + 17 * 17 * 17 * 3, d->firmware);
        return ok ? d : nullptr;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const FirmwareLook>(file);
        auto renderer = std::make_unique<FirmwareRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->look = look;
        renderer->srgbToP3 = mul(inv(kP3ToXyz), kSrgbToXyz);
        renderer->toS9 = mul(mul(inv(kSrgbToXyz), cat(kD50, kD65)), kProPhotoToXyz);
        return renderer;
    }
};

}  // namespace

const Style &firmwareStyle() {
    static const FirmwareStyle style;
    return style;
}

}  // namespace mastercolor
