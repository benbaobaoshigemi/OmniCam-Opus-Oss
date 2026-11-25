// Adobe looks (kind 15, looks/adobe_<brand>_<name>.bin, looks/basic_standard.bin): 33^3 float16 tables from the 11-stop ProPhoto D50
// scene-linear axis (matching style_film.cpp) to display-linear P3 in [0, 1]. The input is the Adobe Standard scene; the file holds
// one table per colour temperature (the profiles' forward matrices change with it), blended at the frame's (1/T).
#include <algorithm>
#include <cmath>
#include <memory>

#include "master_engine.h"
#include "master_style.h"

namespace mastercolor {
namespace {

using namespace detail;

constexpr double kNormalTop = 2.88;
constexpr double kAxisNormal = 0.8;
constexpr double kHeadroomStops = 7.0;

inline double sceneToAxis(double x) {
    if (x <= kNormalTop) return kAxisNormal * ppEncode(x / kNormalTop);
    return std::min(kAxisNormal + (1.0 - kAxisNormal) * std::log2(x / kNormalTop) / kHeadroomStops, 1.0);
}

struct AdobeLook : Look {
    int grid = 33;
    std::vector<float> temperatures;
    std::vector<std::vector<float>> tables;  // per temperature: grid^3 * 3, display-linear P3
};

class AdobeRenderer : public Renderer {
public:
    int grid = 33;
    std::vector<float> table;  // the look's tables blended at the frame's colour temperature
    Mat3 ppToP3{};
    double scale = 1.0;

    void render(const Vec3 &scene, float out[3]) const override {
        const Vec3 pp = {scene[0] * scale, scene[1] * scale, scene[2] * scale};
        Vec3 p3 = xform(ppToP3, pp);
        double lum = 0.2289746 * p3[0] + 0.6917385 * p3[1] + 0.0792869 * p3[2];
        if (lum <= 0.0) {
            p3 = {0.0, 0.0, 0.0};
        } else {
            double mn = std::min({p3[0], p3[1], p3[2]});
            double r = (lum - mn) / lum;
            constexpr double kTh = 0.95, kMargin = 1.0 - kTh;
            if (r > kTh) {
                double u = (r - kTh) / kMargin;
                double s = (kTh + kMargin * (u / (1.0 + u))) / r;
                for (int c = 0; c < 3; ++c) p3[c] = lum + s * (p3[c] - lum);
            }
        }
        const Vec3 u = {sceneToAxis(p3[0]), sceneToAxis(p3[1]), sceneToAxis(p3[2])};
        style::clampToDisplay(cube(table, grid, u), out);
    }
};

class AdobeStyle : public Style {
public:
    const char *id() const override { return "adobe"; }
    bool ownsKind(uint32_t kind) const override { return kind == 15; }

    std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &blob, size_t &at) const override {
        auto d = std::make_shared<AdobeLook>();
        uint32_t grid = 0, count = 0;
        if (!readValue(blob, at, grid) || grid < 2 || grid > 65 || !readValue(blob, at, count) || count < 1 || count > 8) return nullptr;
        d->grid = int(grid);
        if (!readFloats(blob, at, count, d->temperatures)) return nullptr;
        for (size_t i = 1; i < d->temperatures.size(); ++i)
            if (!(d->temperatures[i] > d->temperatures[i - 1])) return nullptr;
        d->tables.resize(count);
        const size_t floats = size_t(grid) * grid * grid * 3;
        for (auto &table : d->tables)
            if (!readHalves(blob, at, floats, table)) return nullptr;
        return d;
    }

    std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &file, const Key &key, const Vec3 &grey) const override {
        auto look = std::static_pointer_cast<const AdobeLook>(file);
        auto renderer = std::make_unique<AdobeRenderer>();
        renderer->scale = style::proPhotoGain(grey, key);
        if (!(renderer->scale > 0)) return nullptr;
        renderer->ppToP3 = mul(inv(kP3ToXyz), mul(cat(kD50, kD65), kProPhotoToXyz));
        renderer->grid = look->grid;
        // The two tables around the frame's colour temperature, blended in 1/T (the ends hold).
        const std::vector<float> &t = look->temperatures;
        size_t lo = 0, hi = 0;
        double weight = 0.0;
        if (t.size() > 1) {
            const double k = double(std::max(key.cct, 1));
            if (k <= t.front()) {
                lo = hi = 0;
            } else if (k >= t.back()) {
                lo = hi = t.size() - 1;
            } else {
                hi = 1;
                while (hi + 1 < t.size() && k > t[hi]) ++hi;
                lo = hi - 1;
                weight = (1.0 / k - 1.0 / t[lo]) / (1.0 / t[hi] - 1.0 / t[lo]);
            }
        }
        renderer->table.resize(look->tables[lo].size());
        for (size_t i = 0; i < renderer->table.size(); ++i)
            renderer->table[i] = float(look->tables[lo][i] * (1.0 - weight) + look->tables[hi][i] * weight);
        return renderer;
    }
};

}  // namespace

const Style &adobeStyle() {
    static const AdobeStyle style;
    return style;
}

}  // namespace mastercolor
