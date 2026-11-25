// Style plugins of the master colour baker.
//
// A style turns the scene into display colour; everything around it is the baker's. The contract:
//   input   scene-linear light, ProPhoto D50, straight out of the scene front stage (the Adobe Standard profile of the
//           lens) with the frame's exposure untouched, plus the Key (CCT, CCM, lens, path) and the style's own Params.
//           `greyScene` is the front stage output for the frame's 18 % grey; a style puts it where its model wants it.
//   output  display-linear P3 in [0, 1] -- what the output stage inverts through the master HDR model into LMT codes.
// The baker never reads a style's Params. A style reads, clamps and defaults them itself, and may fetch anything else
// it needs (its look file, data next to the engine) on its own.
//
// Styles are compiled in; master_styles.cpp lists them. A look file (looks/<name>.bin, "MCOLLOOK" + version + kind)
// belongs to the style that owns its kind; looks without a file (hncs, x7u, x8u) are owned by id.
#pragma once

#include <cmath>
#include <memory>
#include <string>
#include <vector>

#include "master_core.h"
#include "master_math.h"

namespace mastercolor {

// A parsed look file; each style defines its own.
struct Look {
    virtual ~Look() = default;
};

// One bake's renderer: the style prepared for a frame, called for every lattice node (many threads).
class Renderer {
public:
    virtual ~Renderer() = default;
    virtual void render(const detail::Vec3 &scene, float out[3]) const = 0;
    // Optional direct HLG-encoded HDR output in [0, 1] for Main/Thumbnail paths (bypasses invertOutput).
    virtual bool renderHdr(const detail::Vec3 &, float[3]) const { return false; }
};

class Style {
public:
    virtual ~Style() = default;
    virtual const char *id() const = 0;
    // Looks without a file.
    virtual bool ownsId(const std::string &) const { return false; }
    // Looks whose file has this kind.
    virtual bool ownsKind(uint32_t) const { return false; }
    // Reads the body of a look file after the kind, advancing `at`; null if malformed. The caller checks that the file
    // is consumed.
    virtual std::shared_ptr<const Look> parse(uint32_t, const std::vector<uint8_t> &, size_t &) const { return nullptr; }
    // A renderer for this frame, or null if the look cannot be prepared. `look` is null for looks without a file.
    virtual std::unique_ptr<Renderer> prepare(const std::shared_ptr<const Look> &, const Key &, const detail::Vec3 &) const {
        return nullptr;
    }
    // Prepares what the bake of this key needs from the style's settings alone (cached tables), ahead of the first bake.
    // Runs on its own thread in the camera; the bake that follows finds the result ready or waits for it. `background`: the camera is
    // starting, give way to it; false: the user just changed the setting and waits for the preview.
    virtual void warm(const Key &, bool /*background*/) const {}
    // A style that serves finished LMT tables instead of rendering (another model's stock master colour). `out` holds
    // one table, or kStockTables slot tables.
    virtual bool tables(const Key &, std::vector<std::vector<float>> &) const { return false; }
    virtual bool servesTables() const { return false; }
};

const Style *styleForId(const std::string &look);
const Style *styleForKind(uint32_t kind);

// ---------------------------------------------------------------- helpers shared by styles
namespace style {

// Style setting `name` of the key, clamped to [lo, hi]; `fallback` when missing or not finite.
inline float param(const Key &key, const char *name, float fallback, float lo, float hi) {
    auto it = key.params.find(name);
    float v = it != key.params.end() && std::isfinite(it->second) ? it->second : fallback;
    return std::min(std::max(v, lo), hi);
}

// The user's exposure compensation (EV, rounded to 0.01): a setting every rendering style takes.
inline float exposure(const Key &key) {
    float ev = param(key, "exposure", 0, -4, 4);
    return std::round(ev * 100) / 100;
}

// Scene gain of the ProPhoto styles: 18 % grey at 0.18, times the exposure. 0 if the grey has no luminance.
inline double proPhotoGain(const detail::Vec3 &greyScene, const Key &key) {
    using namespace detail;
    const Vec3 luma = {kProPhotoToXyz[3], kProPhotoToXyz[4], kProPhotoToXyz[5]};
    double greyY = greyScene[0] * luma[0] + greyScene[1] * luma[1] + greyScene[2] * luma[2];
    if (!(greyY > 0)) return 0;
    return (0.18 / greyY) * std::exp2(double(exposure(key)));
}

// Display P3 (linear, unclamped) to the output range.
inline void clampToDisplay(const detail::Vec3 &p3, float out[3]) {
    for (int c = 0; c < 3; c++) out[c] = float(std::min(std::max(p3[c], 0.0), 1.0));
}

}  // namespace style
}  // namespace mastercolor
