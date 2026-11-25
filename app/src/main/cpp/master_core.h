// Master LMT baker: the scene front stage and output stage around the style plugins (master_style.h), platform independent.
// tools/mastercolor/master_lmt.py and tools/mastercolor/looks.py are the reference.
#pragma once

#include <array>
#include <cmath>
#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <vector>

namespace mastercolor {

enum class Path : int { Main = 0, Thumbnail = 1, Preview = 2 };

// Scene front stage (scene.bin): the Adobe Standard profile of a rear lens. `equivalentFocal` is the
// 35 mm equivalent focal length of the physical camera; false when no lens is within 25 %.
bool sceneLensFor(double equivalentFocal, std::string &lens);

// A style's own settings, passed to it as they are: name -> value in the unit the style declares. The baker never
// interprets them; a name the style does not know is ignored, a missing one is the style's default.
using Params = std::map<std::string, float>;

// Everything a bake depends on. Two frames with equal keys share one baked table.
struct Key {
    Path path = Path::Main;
    int cct = 5000;                  // Kelvin, already bucketed by the caller
    std::array<float, 9> ccm{};      // post-AWB CCM of the frame (row major)
    std::string lens;                // scene front stage profile name (scene.bin)
    bool luxBlend = false;           // main path: trunc(luxindex) > 280
    std::string look = "hncs";       // a style's look id: "hncs", "lumix_standard", "film_<film>", ... (master_style.h)
    Params params;                   // the look's style settings
    // Blend mode: how much of the look replaces the stock LMT's tone and colour, 0..1 each.
    // 1 / 1 = plain replacement (the table and its directory name are unchanged).
    float toneWeight = 1;
    float colourWeight = 1;

    bool blended() const { return toneWeight < 1 || colourWeight < 1; }
    std::string name() const;        // stable directory name
};

// Loads hncs_engine.bin (format 4) and scene.bin next to it; false if either is missing or malformed. Look files are read from
// looks/ next to it on first use.
bool loadEngine(const std::string &path, std::string *error);
bool engineReady();

constexpr int kLutSize = 64;

// What the LMT sees: the scene light (ProPhoto D50, front stage output, exposure untouched) of an n^3 lattice of input codes,
// code = axisMax * i / (n - 1) per channel, [B][G][R] with R fastest, 3 doubles per node; `grey` is the front stage output of the
// frame's 18 % grey. False if the engine is not loaded or the key has no front stage.
bool codeLatticeScene(const Key &key, int n, double axisMax, std::vector<double> &scene, std::array<double, 3> &grey);
// The same for post-CCM linear light (what ProProc has before it encodes): an n^3 lattice with lin = (i / (n - 1))^exponent per channel.
bool linearLatticeScene(const Key &key, int n, double exponent, std::vector<double> &scene, std::array<double, 3> &grey);
// The largest input code the stock encoding reaches (the sensor clip).
double lmtInputCap();
// Precomputes a 2D vignette transfer LUT [cos4 row j in 0..h-1][code col i in 0..w-1] in [0, 1] using the full builtin curve.
bool builtinVignetteLut(float *outCode, int w, int h);
// Planes R, G, B, each [B][G][R] with R fastest (BasicTone / CombineLut LMTLut layout).
bool bake(const Key &key, std::vector<float> &lut, int threads, const std::vector<uint32_t> *only = nullptr);

constexpr int kStockTables = 6;   // LMTLut0..5: the stock master LMT interpolates them by scene level

// The look's tables: one baked table, or kStockTables slot tables for a look that keeps the stock
// per-frame selection (x8u: another model's stock master LMTs).
bool lookTables(const Key &key, std::vector<std::vector<float>> &tables, int threads);

// Blend mode. `looks` are the look's tables (lookTables(key) with the weights ignored), one for every
// slot or one per slot. Splits the slot's look table and each
// stock table stockDir/LMTLut<i> into tone (the neutral-axis curve, as a function of the scene luma of
// the input code) and colour (per-channel ratio to that curve), both in linear light of the HLG output,
// then recombines tone^(1-wt)*look^wt and colour likewise. Weights 1/1 give the look, 0/0 the stock table.
// Tone is a scalar, so it never changes the chromaticity; colour carries hue, saturation and the
// colour-dependent brightness. tools/mastercolor/blend_check.cpp / .py check it on a host.
bool blendWithStock(const Key &key, const std::vector<std::vector<float>> &looks, const std::string &stockDir,
                    std::vector<std::vector<float>> &tables, int threads);

// The same, handing each finished table to `sink` (slot index, table) as it is made, so only one table
// is held at a time; the table is valid until the sink returns. A false from the sink stops the blend.
using TableSink = std::function<bool(int slot, const std::vector<float> &table)>;
bool blendWithStock(const Key &key, const std::vector<std::vector<float>> &looks, const std::string &stockDir,
                    const TableSink &sink, int threads);

// Serialised key for the preview directory (written by the Java side, read here).
bool readKeyFile(const std::string &path, Key &key);
// Lets the key's style prepare ahead of the first bake (Style::warm); false if the engine is not ready or no style owns the look.
bool warmLook(const Key &key, bool background);
// The same from the key's bytes (MCOLKEY9).
bool readKeyBytes(const uint8_t *data, size_t size, Key &key);

// How far apart two keys' tables are, in 10-bit codes: the 99th percentile of the difference over a lattice
// of mid-tone and highlight nodes (every sixth step, about 1000 nodes), each baked exactly.
double previewDistance(const Key &a, const Key &b);

}  // namespace mastercolor
